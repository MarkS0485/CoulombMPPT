namespace CoulombMppt.Data;

// The learned battery-model state for one controller, persisted by
// BatteryModelStore as a single small JSON blob per MAC. This is the output of
// BatteryModelLearner and the input to BatteryObserver — the calibration the
// app builds for itself (mostly overnight) so it can read the unmeasured EA SUN
// inverter draw from the voltage sag instead of differentiating SoC.
//
// Records are immutable (same style as MpptController / LiveSample) so they
// serialise cleanly and older files deserialise with sensible defaults.

/// <summary>One learned open-circuit-voltage anchor: the rested terminal
/// voltage observed at a coulomb-counted state of charge.</summary>
public sealed record OcvPoint(double SocPct, double Ocv, int SampleCount);

/// <summary>Piecewise-linear rested OCV-vs-SoC curve, points sorted ascending
/// by SocPct. For NMC this carries real information (the curve is sloped).</summary>
public sealed record OcvTable(IReadOnlyList<OcvPoint> Points)
{
    public static OcvTable Empty { get; } = new(Array.Empty<OcvPoint>());
    public int Count => Points.Count;
}

/// <summary>Internal-resistance estimate for one (SoC, temperature) cell. The
/// bins are coarse and always backed by a global fallback.</summary>
public sealed record ResistanceBin(
    double SocLo, double SocHi, double TLo, double THi, double ROhms, int SampleCount);

/// <summary>Resistance model: optional per-bin refinement plus a global
/// fallback used whenever a bin is empty or under-sampled.</summary>
public sealed record ResistanceModel(
    IReadOnlyList<ResistanceBin> Bins, double RGlobalOhms, int TotalSamples)
{
    public static ResistanceModel Empty { get; } = new(Array.Empty<ResistanceBin>(), 0, 0);
    public bool HasResistance => RGlobalOhms > 0;
}

/// <summary>Distribution of the inferred quiescent overnight bus draw — the
/// baseline the inverter wanders around, for the sag/anomaly indicator.</summary>
public sealed record BaseloadStats(
    double MeanA, double StdA, double P50A, double P90A, int SampleCount)
{
    public static BaseloadStats Empty { get; } = new(0, 0, 0, 0, 0);
}

/// <summary>The complete learned model for one controller MAC.</summary>
public sealed record BatteryModelRecord(
    string Mac,
    long   UpdatedMs,
    double CapacityAh,              // capacity anchor captured at learn time
    OcvTable Ocv,
    ResistanceModel R,
    BaseloadStats Baseload,
    double ConfidencePct,          // 0..100 rollup; gates live inference
    long   FirstLearnMs,
    long   LastBackfillThroughMs)  // HistoryStore watermark already ingested
{
    public static BatteryModelRecord Empty(string mac) =>
        new(mac, 0, 0, OcvTable.Empty, ResistanceModel.Empty, BaseloadStats.Empty, 0, 0, 0);

    public bool IsUsable(double minConfidencePct) =>
        ConfidencePct >= minConfidencePct && R.HasResistance && Ocv.Count >= 2;
}
