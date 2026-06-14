using System.Globalization;

namespace CoulombMppt.Data.BatteryModel;

// The pure, IO-free heart of BatteryModelLearner. All the classification,
// coulomb counting, and accumulation lives here so the live 1 Hz path and the
// replayed-history path run *exactly* the same code, and so the unit tests can
// drive it headless without a BLE link or a store.
//
// One LearnerCore instance per controller MAC. It is NOT thread-safe; the
// learner serialises access (the BLE callback is single-threaded and the
// backfill runs before live frames are processed for that MAC). Everything is
// best-effort: the methods never throw on bad data, they just skip.

/// <summary>A normalised frame — the minimal set of fields the classifiers
/// need, shared by live <see cref="MpptLive"/> and replayed <see cref="LiveSample"/>.</summary>
internal readonly record struct LearnerFrame(
    long   TimestampMs,
    double BatteryVoltage,   // V
    double ChargeCurrent,    // A, PV → battery (≥0)
    double DischargeCurrent, // A, battery → load terminal (≥0, ~0 on this bus)
    double TemperatureC,
    bool   LinkReady,        // true for live Ready frames and all replayed rows
    // The controller's own coarse V→SoC estimate. Used only to BOOTSTRAP the
    // coulomb anchor before the learner has ≥2 OCV points of its own — after
    // that, rest re-anchoring against the learned curve takes over.
    double SocHint = double.NaN)
{
    public static LearnerFrame From(MpptLive l) => new(
        l.TimestampMs, l.BatteryVoltage, l.ChargeCurrent, l.DischargeCurrent,
        l.TemperatureC, LinkReady: true, SocHint: l.SocEstimate);

    public static LearnerFrame From(LiveSample s) => new(
        s.TimestampMs, s.BatteryVoltage, s.ChargeCurrent, s.DischargeCurrent,
        s.TemperatureC, LinkReady: true, SocHint: s.SocPercent);
}

/// <summary>Streaming mean/std + a coarse fixed-bucket histogram for the
/// overnight bus-load distribution (P50/P90 without keeping every sample).</summary>
internal sealed class BaseloadAccumulator
{
    // 0..40 A in 0.5 A buckets covers a domestic inverter bus comfortably;
    // anything beyond is clamped into the top bucket (still counted).
    private const double BucketA = 0.5;
    private const int    Buckets = 80;          // 0..40 A
    private readonly long[] _hist = new long[Buckets];

    public long   Count { get; private set; }
    private double _mean;                         // Welford running mean
    private double _m2;                           // Welford sum of squares

    public void Add(double amps)
    {
        if (!double.IsFinite(amps)) return;
        Count++;
        double delta = amps - _mean;
        _mean += delta / Count;
        _m2   += delta * (amps - _mean);

        int b = (int)Math.Floor(amps / BucketA);
        b = Math.Clamp(b, 0, Buckets - 1);        // negatives (net charge) → bucket 0
        _hist[b]++;
    }

    public double Mean => Count > 0 ? _mean : 0;
    public double Std  => Count > 1 ? Math.Sqrt(_m2 / (Count - 1)) : 0;

    /// <summary>Histogram percentile (bucket mid-point) — coarse but robust and
    /// O(buckets), no need to retain the raw samples.</summary>
    public double Percentile(double p)
    {
        if (Count == 0) return 0;
        long target = (long)Math.Ceiling(p / 100.0 * Count);
        if (target < 1) target = 1;
        long cum = 0;
        for (int i = 0; i < Buckets; i++)
        {
            cum += _hist[i];
            if (cum >= target) return (i + 0.5) * BucketA;
        }
        return (Buckets - 0.5) * BucketA;
    }

    public BaseloadStats ToStats() =>
        Count == 0 ? BaseloadStats.Empty
                   : new BaseloadStats(Mean, Std, Percentile(50), Percentile(90), (int)Math.Min(Count, int.MaxValue));
}

/// <summary>Running average voltage AND SoC per SoC bucket for the rested-OCV
/// table. Averaging the actual SoC (not snapping to the bucket midpoint) keeps
/// the curve's x-axis honest so interpolation lands where it should.</summary>
internal sealed class OcvBucket
{
    public double SumV;
    public double SumSoc;
    public int    Count;
    public void Add(double v, double soc) { SumV += v; SumSoc += soc; Count++; }
    public double MeanV   => Count > 0 ? SumV / Count : double.NaN;
    public double MeanSoc => Count > 0 ? SumSoc / Count : double.NaN;
}

/// <summary>Robust-slope accumulator for one (SoC, temp) resistance bin.</summary>
internal sealed class RBin
{
    public readonly List<(double dI, double dV)> Pairs = new();
}

internal sealed class LearnerCore
{
    private readonly string _mac;
    private readonly int    _socBinWidthPct;
    private readonly int    _tempBinWidthC;
    private readonly double _restDvDtThresholdMv;
    private readonly long   _restSettleMs;
    private readonly double _rTransientMinDeltaA;
    private readonly long   _rTransientMaxStepMs;
    private readonly int    _quietStartHour;
    private readonly int    _quietEndHour;

    // Gaps larger than this break coulomb integration (matches EnergyComputer).
    private const long  MaxGapMs            = 5 * 60_000L;
    private const double ChargeDeadbandA    = 0.2;     // |I| below this ≈ "no current"
    private const double RMinOhms           = 0.0005;  // sanity window for an R candidate
    private const double RMaxOhms           = 2.0;
    private const int    RBinMinSamples     = 5;       // per-bin needs this many to emit
    private const double BusLoadLowPassA    = 0.3;     // EMA factor for the integrator feed
    private const double RestBusDeadbandA   = 1.0;     // inferred draw above this ⇒ not rest

    // Capacity anchor (Ah). Set by the learner from the resolved profile.
    public double CapacityAh { get; set; }

    // --- live running state -------------------------------------------------
    private double _soc = double.NaN;          // coulomb-counted SoC, % (NaN until anchored)
    private long   _lastFrameMs;               // for dt and gap detection
    private bool   _haveLast;
    private double _lastVoltage;
    private double _lastChargeA;
    private double _busLoadEma;                // low-passed inferred bus load (A)
    private bool   _haveBusEma;

    // Rest detection: timestamp the current flat-voltage run began.
    private long   _restRunStartMs;
    private bool   _inRestRun;
    private double _restRunStartV;

    // --- accumulators -------------------------------------------------------
    private readonly Dictionary<int, OcvBucket>  _ocv   = new();   // key: SoC bucket index
    private readonly List<(double dI, double dV)> _rAll = new();   // global R candidates
    private readonly Dictionary<(int soc, int temp), RBin> _rBins = new();
    private readonly BaseloadAccumulator _baseload = new();

    public bool Dirty { get; private set; }
    public long FirstLearnMs { get; private set; }
    public long LastFrameSeenMs => _lastFrameMs;

    public LearnerCore(
        string mac, int socBinWidthPct, int tempBinWidthC, double restDvDtThresholdMv,
        int restSettleSec, double rTransientMinDeltaA, int rTransientMaxStepMs,
        int quietStartHour, int quietEndHour)
    {
        _mac                 = mac;
        _socBinWidthPct      = Math.Max(1, socBinWidthPct);
        _tempBinWidthC       = Math.Max(1, tempBinWidthC);
        _restDvDtThresholdMv = restDvDtThresholdMv;
        _restSettleMs        = (long)restSettleSec * 1000L;
        _rTransientMinDeltaA = rTransientMinDeltaA;
        _rTransientMaxStepMs = rTransientMaxStepMs;
        _quietStartHour      = quietStartHour;
        _quietEndHour        = quietEndHour;
    }

    /// <summary>Seed the running state from a previously persisted record so a
    /// restart (or backfill) continues rather than relearning from zero.</summary>
    public void Seed(BatteryModelRecord rec)
    {
        if (rec.FirstLearnMs > 0) FirstLearnMs = rec.FirstLearnMs;
        foreach (var p in rec.Ocv.Points)
        {
            int k = SocBucket(p.SocPct);
            var b = GetOcv(k);
            // Re-weight by the persisted sample count so live points blend in.
            int n = Math.Max(1, p.SampleCount);
            b.SumV   += p.Ocv * n;
            b.SumSoc += p.SocPct * n;
            b.Count  += n;
        }
    }

    private int SocBucket(double socPct) => (int)Math.Floor(socPct / _socBinWidthPct);
    private int TempBucket(double tempC) => (int)Math.Floor(tempC / _tempBinWidthC);
    private OcvBucket GetOcv(int key)
    {
        if (!_ocv.TryGetValue(key, out var b)) _ocv[key] = b = new OcvBucket();
        return b;
    }

    /// <summary>Build a piecewise OCV table from the buckets, sorted ascending by
    /// SoC and forced monotonic-increasing in voltage (NMC) — drop any point that
    /// would invert the curve so InterpOcv/InvertOcv stay well-defined.</summary>
    public OcvTable BuildOcvTable()
    {
        var raw = _ocv
            .Where(kv => kv.Value.Count > 0)
            .Select(kv => (soc: kv.Value.MeanSoc, v: kv.Value.MeanV, n: kv.Value.Count))
            .OrderBy(t => t.soc)
            .ToList();

        var pts = new List<OcvPoint>(raw.Count);
        double lastV = double.NegativeInfinity;
        foreach (var (soc, v, n) in raw)
        {
            if (!double.IsFinite(v)) continue;
            if (v <= lastV) continue;            // would invert monotonic OCV — drop it
            pts.Add(new OcvPoint(Math.Clamp(soc, 0, 100), v, n));
            lastV = v;
        }
        return new OcvTable(pts);
    }

    private OcvTable _liveTable = OcvTable.Empty;   // cached for in-tick OCV/anchor use

    public ResistanceModel BuildResistanceModel()
    {
        double? global = TheveninMath.RobustSlope(_rAll, _rTransientMinDeltaA);
        var bins = new List<ResistanceBin>();
        foreach (var ((socK, tempK), rb) in _rBins)
        {
            if (rb.Pairs.Count < RBinMinSamples) continue;
            double? r = TheveninMath.RobustSlope(rb.Pairs, _rTransientMinDeltaA);
            if (r is not { } rv || rv < RMinOhms || rv > RMaxOhms) continue;
            bins.Add(new ResistanceBin(
                SocLo: socK * _socBinWidthPct, SocHi: (socK + 1) * _socBinWidthPct,
                TLo:   tempK * _tempBinWidthC, THi:  (tempK + 1) * _tempBinWidthC,
                ROhms: rv, SampleCount: rb.Pairs.Count));
        }
        double g = global is { } gv && gv >= RMinOhms && gv <= RMaxOhms ? gv : 0;
        return new ResistanceModel(bins, g, _rAll.Count);
    }

    public BaseloadStats BuildBaseload() => _baseload.ToStats();

    /// <summary>Process one frame through the full classifier pipeline. Pure:
    /// touches only this instance's accumulators. Never throws on finite-input
    /// edge cases.</summary>
    public void Process(in LearnerFrame f)
    {
        if (!double.IsFinite(f.BatteryVoltage) || f.BatteryVoltage <= 1.0) { Remember(f); return; }

        // Bootstrap the coulomb anchor from the controller's coarse SoC the first
        // time we have no anchor of our own; the learned-curve re-anchor at rest
        // refines it from there.
        if (double.IsNaN(_soc) && double.IsFinite(f.SocHint))
            _soc = Math.Clamp(f.SocHint, 0, 100);

        long dtMs = _haveLast ? f.TimestampMs - _lastFrameMs : 0;
        bool gap  = dtMs <= 0 || dtMs > MaxGapMs;

        // --- coulomb integration (always, when we have an anchor + a clean dt) ---
        // Bus load (inferred, low-passed) feeds the integrator so SoC tracks the
        // *real* depletion including the invisible inverter. Use the PREVIOUS
        // tick's SoC inside the OCV term to avoid same-tick feedback.
        if (_haveLast && !gap && double.IsFinite(_soc))
        {
            double netBattA = f.ChargeCurrent - f.DischargeCurrent;
            double? busA = InferBus(f, _soc);
            if (busA is { } raw)
            {
                _busLoadEma = _haveBusEma ? _busLoadEma + BusLoadLowPassA * (raw - _busLoadEma) : raw;
                _haveBusEma = true;
                netBattA -= _busLoadEma;            // subtract the invisible draw
            }
            double cap = CapacityAh > 0 ? CapacityAh : 0;
            if (cap > 0) _soc = TheveninMath.CoulombStep(_soc, netBattA, dtMs, cap);
        }

        // --- REST: flat voltage + ~no charge current, sustained ---------------
        bool noCharge = Math.Abs(f.ChargeCurrent) < ChargeDeadbandA
                     && Math.Abs(f.DischargeCurrent) < ChargeDeadbandA;
        if (noCharge && _haveLast && !gap)
        {
            double dvMv   = (f.BatteryVoltage - _lastVoltage) * 1000.0;
            double dvDtMv = dvMv / (dtMs / 1000.0);
            bool flat = Math.Abs(dvDtMv) <= _restDvDtThresholdMv;
            // Flat AND no current still isn't rest if a usable model says the bus
            // is drawing meaningfully — that's a steady overnight discharge whose
            // sagged voltage would pollute the OCV table. Only gate once we have a
            // model to judge with; before that, every flat run is a candidate.
            double? infBus = InferBus(f, _soc);
            bool drawing = infBus is { } b && b > RestBusDeadbandA;
            if (flat && !drawing)
            {
                if (!_inRestRun) { _inRestRun = true; _restRunStartMs = _lastFrameMs; _restRunStartV = _lastVoltage; }
                if (f.TimestampMs - _restRunStartMs >= _restSettleMs)
                    RecordRest(f);
            }
            else { _inRestRun = false; }
        }
        else { _inRestRun = false; }

        // --- PV-TRANSIENT: fast step in charge current ⇒ R candidate ----------
        if (_haveLast && dtMs > 0 && dtMs <= _rTransientMaxStepMs)
        {
            double dI = f.ChargeCurrent - _lastChargeA;
            double dV = f.BatteryVoltage - _lastVoltage;
            if (Math.Abs(dI) >= _rTransientMinDeltaA)
            {
                double rCand = dV / dI;
                if (double.IsFinite(rCand) && rCand >= RMinOhms && rCand <= RMaxOhms)
                {
                    _rAll.Add((dI, dV));
                    int sk = double.IsFinite(_soc) ? SocBucket(_soc) : 0;
                    int tk = TempBucket(f.TemperatureC);
                    if (!_rBins.TryGetValue((sk, tk), out var rb)) _rBins[(sk, tk)] = rb = new RBin();
                    rb.Pairs.Add((dI, dV));
                    Dirty = true;
                }
            }
        }

        // --- QUIET-NIGHT: overnight, PV≈0, discharging ⇒ baseload sample -------
        if (f.LinkReady && noCharge && IsQuietHour(f.TimestampMs))
        {
            double? busA = InferBus(f, _soc);
            // Only count genuine draw (positive) — the band the inverter wanders.
            if (busA is { } a && a > 0) { _baseload.Add(a); Dirty = true; }
        }

        // --- TOP-OF-CHARGE anchor: charge tapering near full ------------------
        // (Charging/float otherwise just advanced the coulomb counter above.)
        // Handled implicitly by REST once the taper settles; nothing extra here.

        Remember(f);
    }

    private void Remember(in LearnerFrame f)
    {
        _lastFrameMs = f.TimestampMs;
        _lastVoltage = f.BatteryVoltage;
        _lastChargeA = f.ChargeCurrent;
        _haveLast    = true;
    }

    // Inferred bus draw using the live OCV table + global R, at a given SoC.
    private double? InferBus(in LearnerFrame f, double socForOcv)
    {
        if (!double.IsFinite(socForOcv) || _liveR <= 0 || _liveTable.Count == 0) return null;
        double ocv = TheveninMath.InterpOcv(_liveTable, socForOcv);
        return TheveninMath.InferBusLoadA(ocv, f.BatteryVoltage, _liveR, f.ChargeCurrent, f.DischargeCurrent);
    }

    private double _liveR;   // global R cached after each rest/transient refresh

    private void RecordRest(in LearnerFrame f)
    {
        if (FirstLearnMs == 0) FirstLearnMs = f.TimestampMs;

        // At rest terminal V ≈ OCV, so we have a clean SoC↔OCV pair. The SoC we
        // attach it to, in priority order:
        //   1. the controller's coarse SoC hint (always live, reliable enough at
        //      rest, and crucially independent per rest so distinct rests build
        //      a sloped curve rather than collapsing to one bucket),
        //   2. else our own curve inverted from V (drift correction once learned),
        //   3. else the running coulomb _soc, else 50%.
        double socForPoint;
        if (double.IsFinite(f.SocHint))
            socForPoint = f.SocHint;
        else if (_liveTable.Count >= 2)
            socForPoint = TheveninMath.InvertOcv(_liveTable, f.BatteryVoltage);
        else
            socForPoint = double.IsNaN(_soc) ? 50.0 : _soc;

        if (!double.IsFinite(socForPoint)) socForPoint = 50.0;
        socForPoint = Math.Clamp(socForPoint, 0, 100);

        int k = SocBucket(socForPoint);
        GetOcv(k).Add(f.BatteryVoltage, socForPoint);
        _soc = socForPoint;                 // rest re-anchors the coulomb counter
        Dirty = true;

        // Refresh the live table + global R so the rest of this run uses them.
        _liveTable = BuildOcvTable();
        var rm = BuildResistanceModel();
        _liveR = rm.RGlobalOhms;

        _inRestRun = false;   // one anchor per settled run
    }

    // Quiet-night window in LOCAL clock hours, handling the wrap past midnight.
    private bool IsQuietHour(long tsMs)
    {
        int hour = DateTimeOffset.FromUnixTimeMilliseconds(tsMs).ToLocalTime().Hour;
        return _quietStartHour <= _quietEndHour
            ? hour >= _quietStartHour && hour < _quietEndHour
            : hour >= _quietStartHour || hour < _quietEndHour;   // wraps midnight
    }

    /// <summary>Saturating-product confidence rollup (0..100): OCV SoC-span
    /// coverage × R sample count × baseload sample count, each mapped to 0..1.</summary>
    public static double ConfidencePct(OcvTable ocv, ResistanceModel r, BaseloadStats bl)
    {
        // OCV coverage: span of learned SoC over the full 0..100 range.
        double ocvCov = 0;
        if (ocv.Count >= 2)
        {
            double span = ocv.Points[^1].SocPct - ocv.Points[0].SocPct;
            ocvCov = Math.Clamp(span / 60.0, 0, 1);   // ~60% SoC span ⇒ full marks
        }
        double rCov  = Math.Clamp(r.TotalSamples / 20.0, 0, 1);   // ~20 R candidates
        double blCov = Math.Clamp(bl.SampleCount / 200.0, 0, 1);  // ~200 baseload samples
        return Math.Clamp(ocvCov * rCov * blCov * 100.0, 0, 100);
    }

    public BatteryModelRecord BuildRecord(long updatedMs, long backfillThroughMs)
    {
        var ocv = BuildOcvTable();
        var r   = BuildResistanceModel();
        var bl  = BuildBaseload();
        double conf = ConfidencePct(ocv, r, bl);
        Dirty = false;
        return new BatteryModelRecord(
            Mac: _mac, UpdatedMs: updatedMs, CapacityAh: CapacityAh,
            Ocv: ocv, R: r, Baseload: bl, ConfidencePct: conf,
            FirstLearnMs: FirstLearnMs, LastBackfillThroughMs: backfillThroughMs);
    }

    public override string ToString() =>
        string.Create(CultureInfo.InvariantCulture,
            $"LearnerCore[{_mac}] soc={_soc:F1} ocvBuckets={_ocv.Count} rAll={_rAll.Count} baseN={_baseload.Count}");
}
