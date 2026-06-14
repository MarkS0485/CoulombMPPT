using System.Globalization;
using System.Windows;
using System.Windows.Threading;
using CommunityToolkit.Mvvm.ComponentModel;
using CoulombMppt.Ble;
using CoulombMppt.Data;
using CoulombMppt.Services;
using CoulombMppt.Ui.Controls;

namespace CoulombMppt.Ui.ViewModels;

// Read-only view-model for the Battery Model / Calibration page. Visualises the
// learned battery model (OCV table, resistance, baseload) that BatteryObserver
// consumes — it changes no inference behaviour. Mirrors InverterViewModel's
// construction/threading: subscribe to BLE + controller change events, marshal
// to the UI via RunOnUi, and re-read the persisted model on a DispatcherTimer.
public sealed partial class BatteryModelViewModel : ObservableObject
{
    private readonly MpptClient        _ble    = ServiceLocator.Ble;
    private readonly ControllerStore   _ctrls  = ServiceLocator.Controllers;
    private readonly BatteryModelStore _models = ServiceLocator.BatteryModels;
    private readonly BatteryObserver   _obs    = ServiceLocator.Observer;
    private readonly AppSettings       _settings = ServiceLocator.Settings;

    private readonly DispatcherTimer _modelTimer;
    private const double ModelRefreshSeconds = 5.0;

    // Most recent model loaded from disk (drives the chart rebuild in code-behind).
    public BatteryModelRecord? Model { get; private set; }

    // ---- Status / header ----
    [ObservableProperty] private string _statusLabel    = "No model yet";
    [ObservableProperty] private bool   _isCalibrated;
    [ObservableProperty] private StatusKind _pillKind   = StatusKind.Offline;
    [ObservableProperty] private string _gateContext    = "";
    [ObservableProperty] private bool   _hasModel;

    // ---- OCV chart inputs (consumed by the code-behind, not bound directly) ----
    [ObservableProperty] private IReadOnlyList<OcvPoint> _ocvPoints = Array.Empty<OcvPoint>();
    // Old linear reference line endpoints (EmptyV @ 0%, FullV @ 100%); null when no profile.
    [ObservableProperty] private double? _linearEmptyV;
    [ObservableProperty] private double? _linearFullV;
    [ObservableProperty] private bool    _hasOcv;

    // ---- Internal resistance ----
    [ObservableProperty] private bool   _hasResistance;
    [ObservableProperty] private string _rGlobalMilliohms = "—";
    [ObservableProperty] private string _rTotalSamples    = "0";
    [ObservableProperty] private IReadOnlyList<ResistanceBinRow> _resistanceBins = Array.Empty<ResistanceBinRow>();

    // ---- Baseload band ----
    [ObservableProperty] private bool   _hasBaseload;
    [ObservableProperty] private string _baseloadMean   = "—";
    [ObservableProperty] private string _baseloadP50    = "—";
    [ObservableProperty] private string _baseloadP90    = "—";
    [ObservableProperty] private string _baseloadSamples = "0";

    // ---- Live estimate vs legacy ----
    [ObservableProperty] private bool   _hasEstimate;
    [ObservableProperty] private bool   _estimateIsLearning;
    [ObservableProperty] private string _liveBusLabel     = "—";
    [ObservableProperty] private string _liveSocLabel     = "—";
    [ObservableProperty] private string _legacyEstLabel   = "—";
    [ObservableProperty] private string _estimateModeNote = "";

    // ---- Freshness / anchors ----
    [ObservableProperty] private string _learnedAtLabel = "—";
    [ObservableProperty] private string _capacityLabel  = "—";

    public BatteryModelViewModel()
    {
        _ble.LiveChanged    += _ => RunOnUi(SyncEstimate);
        _ble.StateChanged   += _ => RunOnUi(SyncEstimate);
        _ctrls.Changed      += () => RunOnUi(RefreshModel);
        _obs.EstimateChanged += _ => RunOnUi(SyncEstimate);

        _modelTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(ModelRefreshSeconds) };
        _modelTimer.Tick += (_, _) => RefreshModel();
        _modelTimer.Start();

        RefreshModel();
        SyncEstimate();
    }

    // ---- Periodic model reload ----

    private void RefreshModel()
    {
        var mac = _ble.CurrentMac ?? _ctrls.CurrentMac;
        var model = string.IsNullOrEmpty(mac) ? null : _models.Load(mac!);
        Model = model;
        HasModel = model != null;

        var profile = CurrentProfile();

        if (model == null)
        {
            StatusLabel  = "No model learned yet";
            IsCalibrated = false;
            PillKind     = StatusKind.Offline;
            GateContext  = "Leave the app running overnight with a battery profile set so it can calibrate.";

            OcvPoints     = Array.Empty<OcvPoint>();
            HasOcv        = false;
            HasResistance = false;
            ResistanceBins = Array.Empty<ResistanceBinRow>();
            HasBaseload   = false;
            LearnedAtLabel = "—";
            CapacityLabel  = "—";
            // Still publish the linear reference so the chart can show "what we used to assume".
            PublishLinearReference(profile);
            SyncEstimate();
            return;
        }

        bool usable    = model.IsUsable(_settings.MinConfidenceForLivePct);
        bool learning  = _obs.Current?.IsLearning ?? !usable;
        IsCalibrated   = usable && !learning;
        string conf    = model.ConfidencePct.ToString("0", CultureInfo.InvariantCulture);
        StatusLabel    = IsCalibrated
            ? $"Calibrated · {conf}% confidence"
            : $"Calibrating · {conf}% confidence";
        PillKind       = IsCalibrated ? StatusKind.Online : StatusKind.Stale;
        GateContext    = $"Live inference engages at {_settings.MinConfidenceForLivePct.ToString("0", CultureInfo.InvariantCulture)}% confidence.";

        // ---- OCV ----
        OcvPoints = model.Ocv.Points;
        HasOcv    = model.Ocv.Count >= 1;
        PublishLinearReference(profile);

        // ---- Resistance ----
        var r = model.R;
        HasResistance    = r.HasResistance;
        RGlobalMilliohms = (r.RGlobalOhms * 1000.0).ToString("0.0", CultureInfo.InvariantCulture) + " mΩ";
        RTotalSamples    = r.TotalSamples.ToString("N0", CultureInfo.InvariantCulture);
        ResistanceBins   = r.Bins
            .Select(b => new ResistanceBinRow(
                SocRange: $"{b.SocLo.ToString("0", CultureInfo.InvariantCulture)}–{b.SocHi.ToString("0", CultureInfo.InvariantCulture)} %",
                TempRange: $"{b.TLo.ToString("0", CultureInfo.InvariantCulture)}–{b.THi.ToString("0", CultureInfo.InvariantCulture)} °C",
                Milliohms: (b.ROhms * 1000.0).ToString("0.0", CultureInfo.InvariantCulture) + " mΩ",
                Samples: b.SampleCount.ToString("N0", CultureInfo.InvariantCulture)))
            .ToList();

        // ---- Baseload ----
        var bl = model.Baseload;
        HasBaseload = bl.SampleCount > 0;
        double v    = _ble.Live?.BatteryVoltage ?? 0;
        BaseloadMean    = FormatAmpsWatts(bl.MeanA, v);
        BaseloadP50     = FormatAmpsWatts(bl.P50A, v);
        BaseloadP90     = FormatAmpsWatts(bl.P90A, v);
        BaseloadSamples = bl.SampleCount.ToString("N0", CultureInfo.InvariantCulture);

        // ---- Freshness / anchors ----
        LearnedAtLabel = model.UpdatedMs > 0
            ? DateTimeOffset.FromUnixTimeMilliseconds(model.UpdatedMs).ToLocalTime()
                  .ToString("d MMM yyyy HH:mm", CultureInfo.InvariantCulture)
            : "—";
        CapacityLabel = model.CapacityAh > 0
            ? model.CapacityAh.ToString("0.#", CultureInfo.InvariantCulture) + " Ah"
            : "—";

        SyncEstimate();
    }

    private void PublishLinearReference(BatteryProfile? profile)
    {
        if (profile != null && profile.FullV > profile.EmptyV)
        {
            LinearEmptyV = profile.EmptyV;
            LinearFullV  = profile.FullV;
        }
        else
        {
            LinearEmptyV = null;
            LinearFullV  = null;
        }
    }

    // ---- Live estimate vs legacy cross-check ----

    private void SyncEstimate()
    {
        var est = _obs.Current;
        if (est == null)
        {
            HasEstimate        = false;
            EstimateIsLearning = false;
            LiveBusLabel       = "—";
            LiveSocLabel       = "—";
            LegacyEstLabel     = "—";
            EstimateModeNote   = "";
            return;
        }

        HasEstimate        = true;
        EstimateIsLearning = est.IsLearning;

        // BusLoadA: positive = bus drawing (inverter + house), negative = net charging.
        string dir = est.BusLoadA >= 0 ? "drawing" : "charging";
        LiveBusLabel = string.Format(CultureInfo.InvariantCulture,
            "{0:+0.0;-0.0;0.0} A · {1:+0;-0;0} W ({2})", est.BusLoadA, est.BusLoadW, dir);
        LiveSocLabel = est.Soc.ToString("0.0", CultureInfo.InvariantCulture) + " %";

        LegacyEstLabel = est.CrossCheckEasunA is { } a
            ? string.Format(CultureInfo.InvariantCulture, "{0:+0.0;-0.0;0.0} A net", a)
            : "unavailable";

        EstimateModeNote = est.IsLearning
            ? "Still learning — the figure above is the legacy fallback until confidence clears the gate."
            : "Live model inference active — read straight from the voltage sag.";
    }

    // ---- Helpers ----

    private static string FormatAmpsWatts(double amps, double voltage)
    {
        string a = amps.ToString("0.00", CultureInfo.InvariantCulture) + " A";
        if (voltage > 0)
            a += " · " + (amps * voltage).ToString("0", CultureInfo.InvariantCulture) + " W";
        return a;
    }

    private BatteryProfile? CurrentProfile()
    {
        var mac = _ble.CurrentMac ?? _ctrls.CurrentMac;
        return mac != null ? _ctrls.Find(mac)?.ResolvedBatteryProfile() : null;
    }

    private static void RunOnUi(Action a)
    {
        var d = Application.Current?.Dispatcher;
        if (d == null || d.CheckAccess()) a();
        else d.BeginInvoke(a);
    }
}

// One row in the per-bin internal-resistance table (display-only projection).
public sealed record ResistanceBinRow(string SocRange, string TempRange, string Milliohms, string Samples);
