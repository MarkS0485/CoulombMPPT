using System.Collections.ObjectModel;
using System.Globalization;
using System.Windows;
using System.Windows.Threading;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using CoulombMppt.Ble;
using CoulombMppt.Data;
using CoulombMppt.Services;

namespace CoulombMppt.Ui.ViewModels;

// Top-level overview: connection status, a SoC ring, the headline live metrics,
// the charger state, and any active alerts. Also the place to switch between
// paired controllers and to flip demo mode on for a no-hardware walkthrough.
public sealed partial class DashboardViewModel : ObservableObject
{
    private readonly MpptClient      _ble    = ServiceLocator.Ble;
    private readonly ControllerStore _ctrls  = ServiceLocator.Controllers;
    private readonly AlertStore      _alerts = ServiceLocator.Alerts;

    public DashboardViewModel()
    {
        _ble.StateChanged     += _ => RunOnUi(SyncState);
        _ble.LiveChanged      += _ => RunOnUi(SyncLive);
        _ble.SettingsChanged  += _ => RunOnUi(SyncLive);
        _ble.LastErrorChanged += e => RunOnUi(() => LastError = e);
        _ctrls.Changed        += () => RunOnUi(RebuildControllers);
        _alerts.Changed       += () => RunOnUi(SyncAlerts);

        _energyTimer = new DispatcherTimer { Interval = TimeSpan.FromMinutes(1) };
        _energyTimer.Tick += (_, _) => RefreshEnergy();
        _energyTimer.Start();

        SyncState();
        SyncLive();
        RebuildControllers();
        SyncAlerts();
        LastError = _ble.LastError;
    }

    // --- Connection ----
    [ObservableProperty] private string _statusLabel = "Disconnected";
    [ObservableProperty] private bool   _isReady;
    [ObservableProperty] private bool   _isDemo;
    [ObservableProperty] private string _lastError = "";
    [ObservableProperty] private string _controllerName = "No controller";
    [ObservableProperty] private string _controllerMac = "";

    // --- Headline metrics ----
    [ObservableProperty] private double _soc;
    [ObservableProperty] private string _socCaption = "SoC";
    [ObservableProperty] private string _batteryVoltsLabel = "—";
    [ObservableProperty] private string _chargeLabel = "—";
    [ObservableProperty] private string _loadLabel = "—";
    [ObservableProperty] private string _pvLabel = "—";
    [ObservableProperty] private string _tempLabel = "—";
    [ObservableProperty] private string _totalAhLabel = "—";
    [ObservableProperty] private string _chargerStateLabel = "—";
    [ObservableProperty] private bool   _hasLive;

    // --- Numeric live values for the gauges ----
    [ObservableProperty] private double _batteryVoltage;
    [ObservableProperty] private double _chargeCurrentA;
    [ObservableProperty] private double _pvWatts;
    [ObservableProperty] private double _temperatureC;
    [ObservableProperty] private double _batteryNetA;     // total into battery (MPPT + EA SUN est)
    [ObservableProperty] private double _batteryNetW;
    [ObservableProperty] private double _easunNetA;       // inferred EA SUN contribution (signed)
    [ObservableProperty] private double _easunWatts;

    // --- Auto-scaling gauge bounds ----
    [ObservableProperty] private double _voltMin = 20;
    [ObservableProperty] private double _voltMax = 27;
    [ObservableProperty] private double _pvScaleMax = 200;
    [ObservableProperty] private double _chargeScaleMax = 10;
    [ObservableProperty] private double _battWScaleMax = 200;
    [ObservableProperty] private double _easunScaleMax = 10;

    // Running maxima used to grow the gauge scales (reset on disconnect).
    private double _maxPv, _maxCharge, _maxBattW, _maxEasun;

    // --- Alerts ----
    [ObservableProperty] private int    _activeAlertCount;
    [ObservableProperty] private string _activeAlertText = "";
    [ObservableProperty] private bool   _hasActiveAlert;

    // --- Hybrid relay ----
    [ObservableProperty] private bool   _relayActive;
    [ObservableProperty] private string _relayLabel = "";

    // --- Energy / EA SUN ----
    [ObservableProperty] private string _todayPvKwh     = "—";
    [ObservableProperty] private string _todayBattIn    = "—";
    [ObservableProperty] private string _todayBattOut   = "—";
    [ObservableProperty] private string _todayEasun     = "—";
    [ObservableProperty] private string _yesterdayPvKwh = "—";
    [ObservableProperty] private bool   _hasEnergyProfile;
    [ObservableProperty] private string _liveEasunLabel = "";
    [ObservableProperty] private bool   _hasLiveEasun;
    [ObservableProperty] private bool   _isModelLive;          // sag-based observer vs legacy estimate
    [ObservableProperty] private double _modelConfidencePct;

    // --- Live trend series for the dashboard mini-charts (last 30 min) ----
    [ObservableProperty] private IReadOnlyList<double> _voltTrend = Array.Empty<double>();
    [ObservableProperty] private IReadOnlyList<double> _pvTrend   = Array.Empty<double>();
    [ObservableProperty] private IReadOnlyList<double> _netTrend  = Array.Empty<double>();
    [ObservableProperty] private IReadOnlyList<long>   _trendAxis = Array.Empty<long>();
    private readonly List<(long Ts, double V, double Pv, double Net)> _trendBuf = new();
    private const long TrendWindowMs = 120 * 60_000L;   // keep 2 h so the user can scrub/zoom back

    // --- Charger state + flow direction (hero strip + badge + flow diagram) ----
    [ObservableProperty] private ChargerState _chargerStateValue = ChargerState.Unknown;
    [ObservableProperty] private string _flowStatusLabel = "Idle";
    [ObservableProperty] private string _updatedLabel = "";

    // --- Interactive trend viewport — the three mini-charts share these so they
    //     pan, zoom and scrub in lock-step (MiniChart's viewport mode). ----
    [ObservableProperty] private long   _trendViewStartMs;
    [ObservableProperty] private long   _trendViewEndMs;
    [ObservableProperty] private long   _trendCrosshairMs;
    [ObservableProperty] private long   _trendMinViewMs;
    [ObservableProperty] private long   _trendMaxViewMs;
    [ObservableProperty] private int    _trendRangeMin = 30;
    [ObservableProperty] private string _trendRangeLabel = "30 min";
    [ObservableProperty] private string _trendReadout = "";
    private bool _followLive = true;     // keep the window pinned to "now" until the user pans
    private bool _pushingViewport;       // re-entrancy guard while we drive the viewport
    private long _lastPushedStartMs, _lastPushedEndMs;

    // Rolling 3-minute buffer for the live EA SUN estimate.
    private readonly List<LiveSample> _recentBuf = new();
    private const long LiveWindowMs = 3 * 60_000L;
    private long _lastEnergyRefreshMs;
    private const long EnergyRefreshIntervalMs = 60_000L;
    private readonly DispatcherTimer _energyTimer;

    public ObservableCollection<MpptController> Controllers { get; } = new();

    // --- Commands ----

    [RelayCommand]
    private async Task Connect()
    {
        var mac = _ctrls.CurrentMac;
        if (string.IsNullOrEmpty(mac)) return;
        await _ble.ConnectAsync(mac);
    }

    [RelayCommand]
    private async Task Disconnect() => await _ble.DisconnectAsync();

    [RelayCommand]
    private void ToggleDemo()
    {
        if (_ble.DemoMode) _ble.StopDemo();
        else _ble.StartDemo();
    }

    [RelayCommand]
    private async Task SelectController(MpptController? c)
    {
        if (c == null) return;
        _ctrls.SetCurrent(c.Mac);
        await _ble.ConnectAsync(c.Mac);
    }

    [RelayCommand]
    private void DismissAlerts()
    {
        var mac = _ble.CurrentMac ?? _ctrls.CurrentMac;
        if (!string.IsNullOrEmpty(mac)) _alerts.DismissAllFor(mac);
    }

    // --- Interactive trend controls ----

    // Pick the visible span (minutes) and re-pin to the live edge.
    [RelayCommand]
    private void SetTrendRange(string? minutes)
    {
        if (!int.TryParse(minutes, out int m) || m <= 0) return;
        TrendRangeMin   = m;
        TrendRangeLabel = m >= 60 ? $"{m / 60} h" : $"{m} min";
        _followLive = true;
        long now = LatestTrendMs();
        PushViewport(now - m * 60_000L, now);
        TrendCrosshairMs = 0;
    }

    // Jump back to "now", keeping whatever zoom the user has dialled in.
    [RelayCommand]
    private void SnapLive()
    {
        _followLive = true;
        long now = LatestTrendMs();
        long span = TrendViewEndMs > TrendViewStartMs ? TrendViewEndMs - TrendViewStartMs : TrendRangeMin * 60_000L;
        PushViewport(now - span, now);
        TrendCrosshairMs = 0;
    }

    private long LatestTrendMs() =>
        _trendBuf.Count > 0 ? _trendBuf[^1].Ts : DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();

    // Follow the live edge until the user drags/zooms the charts themselves; the
    // charts write the viewport back two-way, so a change we didn't push means the
    // user took over and we should stop auto-advancing.
    private void UpdateTrendViewport(long nowMs)
    {
        TrendMinViewMs = _trendBuf.Count > 0 ? _trendBuf[0].Ts : nowMs;
        TrendMaxViewMs = nowMs;
        if (_followLive || TrendViewEndMs <= 0)
            PushViewport(nowMs - Math.Max(60_000L, TrendRangeMin * 60_000L), nowMs);
    }

    private void PushViewport(long start, long end)
    {
        _pushingViewport = true;
        TrendViewStartMs   = start;
        TrendViewEndMs     = end;
        _lastPushedStartMs = start;
        _lastPushedEndMs   = end;
        _pushingViewport = false;
    }

    partial void OnTrendViewStartMsChanged(long value)
    {
        if (!_pushingViewport && Math.Abs(value - _lastPushedStartMs) > 2000) _followLive = false;
    }

    partial void OnTrendViewEndMsChanged(long value)
    {
        if (!_pushingViewport && Math.Abs(value - _lastPushedEndMs) > 2000) _followLive = false;
    }

    partial void OnTrendCrosshairMsChanged(long value) => UpdateTrendReadout();

    // Read off the sample nearest the crosshair (or the latest while not scrubbing).
    private void UpdateTrendReadout()
    {
        if (_trendBuf.Count == 0) { TrendReadout = ""; return; }
        long target = TrendCrosshairMs > 0 ? TrendCrosshairMs : _trendBuf[^1].Ts;
        int best = 0; long bestD = long.MaxValue;
        for (int i = 0; i < _trendBuf.Count; i++)
        {
            long d = Math.Abs(_trendBuf[i].Ts - target);
            if (d < bestD) { bestD = d; best = i; }
        }
        var s = _trendBuf[best];
        string when = TrendCrosshairMs > 0
            ? DateTimeOffset.FromUnixTimeMilliseconds(s.Ts).ToLocalTime().ToString("HH:mm:ss", CultureInfo.InvariantCulture)
            : "live";
        TrendReadout = $"{when}   ·   {s.V:0.00} V   ·   {s.Pv:0} W PV   ·   net {s.Net:+0;-0;0} W";
    }

    // --- Sync ----

    private void SyncState()
    {
        var s = _ble.State;
        // A live relay from the phone takes precedence: the desktop has no BLE
        // link of its own in that case, so showing "Failed/Disconnected" would
        // be wrong — we're getting fresh data, just over the network.
        StatusLabel = _ble.RelayActive ? "Relay (phone)" : s switch
        {
            ConnectionState.Ready               => "Connected",
            ConnectionState.Connecting          => "Connecting…",
            ConnectionState.DiscoveringServices => "Discovering…",
            ConnectionState.Reconnecting        => "Reconnecting…",
            ConnectionState.Failed              => "Failed",
            ConnectionState.Scanning            => "Scanning…",
            _                                   => "Disconnected",
        };
        IsReady = s == ConnectionState.Ready;
        IsDemo  = _ble.DemoMode;
        if (s is ConnectionState.Idle or ConnectionState.Failed) { _maxPv = _maxCharge = _maxBattW = _maxEasun = 0; }

        var mac = _ble.CurrentMac ?? _ctrls.CurrentMac;
        ControllerMac  = mac ?? "";
        ControllerName = mac != null ? (_ctrls.Find(mac)?.Label ?? mac) : "No controller";
    }

    private void SyncLive()
    {
        var live = _ble.Live;
        HasLive = live != null;
        if (live == null)
        {
            BatteryVoltsLabel = ChargeLabel = LoadLabel = PvLabel = TempLabel =
                TotalAhLabel = ChargerStateLabel = "—";
            Soc = 0;
            BatteryVoltage = ChargeCurrentA = PvWatts = TemperatureC = 0;
            BatteryNetA = BatteryNetW = EasunNetA = EasunWatts = 0;
            ChargerStateValue = ChargerState.Unknown;
            FlowStatusLabel = "—";
            UpdatedLabel = "";
            return;
        }

        BatteryVoltsLabel = $"{live.BatteryVoltage:0.0} V";
        ChargeLabel       = $"{live.ChargeCurrent:0.0} A";
        LoadLabel         = $"{live.DischargeCurrent:0.0} A";
        PvLabel           = $"{live.ApproxPvWatts:0} W";
        TempLabel         = $"{live.TemperatureC:0.0} °C";
        TotalAhLabel      = $"{live.TotalAccumulatedAh:0.0} Ah";
        ChargerStateLabel = live.ChargerState.Label();
        ChargerStateValue = live.ChargerState;
        UpdatedLabel      = $"Updated {DateTimeOffset.FromUnixTimeMilliseconds(live.TimestampMs).ToLocalTime():HH:mm:ss}";
        Soc = ResolveSoc(live);

        // Numeric values for the gauges.
        BatteryVoltage = live.BatteryVoltage;
        ChargeCurrentA = live.ChargeCurrent;
        PvWatts        = live.ApproxPvWatts;
        TemperatureC   = live.TemperatureC;

        RelayActive = _ble.RelayActive;
        RelayLabel  = RelayActive ? "Live relay from Android phone" : "";
        if (RelayActive)
        {
            // We're being fed by the phone; the desktop's own BLE attempts (and
            // their errors) are just noise in this mode — reflect relay status.
            StatusLabel = "Relay (phone)";
            if (!string.IsNullOrEmpty(LastError)) LastError = "";
        }

        // Maintain 3-minute rolling buffer for live EA SUN estimate.
        var sample = new LiveSample(
            TimestampMs: live.TimestampMs,
            BatteryVoltage: live.BatteryVoltage,
            ChargeCurrent: live.ChargeCurrent,
            DischargeCurrent: live.DischargeCurrent,
            PvWatts: live.ApproxPvWatts,
            LoadWatts: live.LoadWatts,
            TemperatureC: live.TemperatureC,
            SocPercent: Soc);
        _recentBuf.Add(sample);
        long cutoff = live.TimestampMs - LiveWindowMs;
        _recentBuf.RemoveAll(r => r.TimestampMs < cutoff);

        var profile = CurrentProfile();
        if (profile != null) { VoltMin = profile.EmptyV; VoltMax = profile.FullV; }

        double mpptNetA = live.ChargeCurrent - live.DischargeCurrent;

        // Phase 2: prefer the sag-based observer once it clears the confidence
        // gate, else fall back to the legacy differentiator. Observer BusLoadA is
        // positive when drawing, so EA SUN net (positive = charging) is −BusLoadA.
        var est    = ServiceLocator.Observer.Current;
        var easunA = EnergyComputer.LiveEasunA(_recentBuf, profile);
        ModelConfidencePct = est?.ConfidencePct ?? 0;

        if (est is { IsLearning: false })
        {
            double netA = -est.BusLoadA;
            HasLiveEasun = true;
            IsModelLive  = true;
            EasunNetA    = netA;
            EasunWatts   = -est.BusLoadW;
            LiveEasunLabel = netA >= 0
                ? $"EA SUN  +{netA:0.0} A · +{EasunWatts:0} W  (array charging the bank)"
                : $"EA SUN  {netA:0.0} A · {EasunWatts:0} W  (inverter drawing load)";
        }
        else if (easunA != null)
        {
            HasLiveEasun = true;
            IsModelLive  = false;
            EasunNetA    = easunA.Value;
            EasunWatts   = easunA.Value * live.BatteryVoltage;
            LiveEasunLabel = easunA.Value >= 0
                ? $"EA SUN  +{easunA.Value:0.0} A · +{EasunWatts:0} W  (array charging the bank)"
                : $"EA SUN  {easunA.Value:0.0} A · {EasunWatts:0} W  (inverter drawing load)";
        }
        else
        {
            IsModelLive = false;
            EasunNetA = 0; EasunWatts = 0;
        }

        // Total battery throughput = MPPT net + inferred EA SUN net.
        BatteryNetA = mpptNetA + EasunNetA;
        BatteryNetW = BatteryNetA * live.BatteryVoltage;
        FlowStatusLabel = BatteryNetW > 5 ? "Charging ↑" : BatteryNetW < -5 ? "Discharging ↓" : "Idle";

        UpdateScales();

        // Live trend series for the dashboard mini-charts.
        _trendBuf.Add((live.TimestampMs, live.BatteryVoltage, live.ApproxPvWatts, BatteryNetW));
        long tcut = live.TimestampMs - TrendWindowMs;
        _trendBuf.RemoveAll(x => x.Ts < tcut);
        VoltTrend = _trendBuf.Select(x => x.V).ToArray();
        PvTrend   = _trendBuf.Select(x => x.Pv).ToArray();
        NetTrend  = _trendBuf.Select(x => x.Net).ToArray();
        TrendAxis = _trendBuf.Select(x => x.Ts).ToArray();
        UpdateTrendViewport(live.TimestampMs);
        if (TrendCrosshairMs <= 0) UpdateTrendReadout();   // keep the "live" readout fresh

        // Throttled daily energy refresh.
        long nowTick = Environment.TickCount64;
        if (nowTick - _lastEnergyRefreshMs > EnergyRefreshIntervalMs)
            RefreshEnergy();
    }

    private BatteryProfile? CurrentProfile()
    {
        var mac = _ble.CurrentMac ?? _ctrls.CurrentMac;
        return mac != null ? _ctrls.Find(mac)?.ResolvedBatteryProfile() : null;
    }

    // Grow the gauge ranges so the needle uses most of the dial, but never shrink
    // below sensible floors so a quiet moment doesn't make the gauges twitchy.
    private void UpdateScales()
    {
        _maxPv     = Math.Max(_maxPv, PvWatts);
        _maxCharge = Math.Max(_maxCharge, ChargeCurrentA);
        _maxBattW  = Math.Max(_maxBattW, Math.Abs(BatteryNetW));
        _maxEasun  = Math.Max(_maxEasun, Math.Abs(EasunNetA));

        PvScaleMax     = NiceCeil(Math.Max(_maxPv, 50));
        ChargeScaleMax = NiceCeil(Math.Max(_maxCharge, 5));
        BattWScaleMax  = NiceCeil(Math.Max(_maxBattW, 50));
        EasunScaleMax  = NiceCeil(Math.Max(_maxEasun, 5));
    }

    // Round up to a "nice" 1/2/5 × 10ⁿ ceiling so the dial labels read cleanly.
    private static double NiceCeil(double v)
    {
        if (v <= 0) return 1;
        double mag = Math.Pow(10, Math.Floor(Math.Log10(v)));
        double n = v / mag;
        double step = n <= 1 ? 1 : n <= 2 ? 2 : n <= 5 ? 5 : 10;
        return step * mag;
    }

    private void RefreshEnergy()
    {
        _lastEnergyRefreshMs = Environment.TickCount64;
        var mac = _ble.CurrentMac ?? _ctrls.CurrentMac;
        if (string.IsNullOrEmpty(mac)) return;
        var profile = CurrentProfile();
        HasEnergyProfile = profile != null;

        long now      = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        long todayStart = DayStartMs(0);
        long yesterdayStart = DayStartMs(-1);

        var todayRows = ServiceLocator.History.Query(mac!, todayStart);
        var yesterRows = ServiceLocator.History.Query(mac!, yesterdayStart)
            .Where(r => r.TimestampMs < todayStart)
            .ToList();

        var today = EnergyComputer.ComputeDay(todayRows, todayStart, profile);
        var yest  = EnergyComputer.ComputeDay(yesterRows, yesterdayStart, profile);

        TodayPvKwh     = $"{today.PvWh / 1000.0:0.00} kWh";
        YesterdayPvKwh = $"{yest.PvWh / 1000.0:0.00} kWh";

        if (profile != null)
        {
            TodayBattIn  = $"{today.BattInWh / 1000.0:0.00} kWh";
            TodayBattOut = $"{today.BattOutWh / 1000.0:0.00} kWh";
            if (today.EasunNetWh != null)
            {
                double wh  = Math.Abs(today.EasunNetWh.Value);
                string dir = today.EasunNetWh.Value >= 0 ? "net charging" : "net draw";
                TodayEasun = $"{wh / 1000.0:0.00} kWh ({dir})";
            }
            else TodayEasun = "—";
        }
    }

    private static long DayStartMs(int offsetDays)
    {
        var d = DateTime.Now.Date.AddDays(offsetDays);
        return new DateTimeOffset(d, TimeZoneInfo.Local.GetUtcOffset(d)).ToUnixTimeMilliseconds();
    }

    // Prefer the controller's own calibration (settings setpoints), then the
    // cached profile on the paired record, then the crude V→SoC fallback.
    private double ResolveSoc(MpptLive live)
    {
        var v = live.BatteryVoltage;
        if (_ble.Settings?.ComputeSoc(v) is { } a) return a;
        var mac = _ble.CurrentMac ?? _ctrls.CurrentMac;
        if (mac != null && _ctrls.Find(mac)?.ComputeSocFromCache(v) is { } b) return b;
        return live.SocEstimate;
    }

    private void RebuildControllers()
    {
        Controllers.Clear();
        foreach (var c in _ctrls.All) Controllers.Add(c);
        SyncState();
    }

    private void SyncAlerts()
    {
        var mac = _ble.CurrentMac ?? _ctrls.CurrentMac;
        if (string.IsNullOrEmpty(mac))
        {
            ActiveAlertCount = 0; HasActiveAlert = false; ActiveAlertText = "";
            return;
        }
        long since = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() - 7L * 24 * 60 * 60 * 1000;
        var active = _alerts.ActiveFor(mac, since);
        ActiveAlertCount = active.Count;
        HasActiveAlert   = active.Count > 0;
        ActiveAlertText  = active.Count == 0
            ? ""
            : active.Count == 1
                ? active[0].Message
                : $"{active.Count} active alerts — newest: {active[0].Message}";
    }

    private static void RunOnUi(Action a)
    {
        var d = Application.Current?.Dispatcher;
        if (d == null || d.CheckAccess()) a();
        else d.BeginInvoke(a);
    }
}
