using System.Globalization;
using System.Windows;
using System.Windows.Threading;
using CommunityToolkit.Mvvm.ComponentModel;
using CoulombMppt.Ble;
using CoulombMppt.Data;
using CoulombMppt.Services;

namespace CoulombMppt.Ui.ViewModels;

// Dedicated Inverter / EA SUN page view-model.
// Mirrors the EA SUN inference path from DashboardViewModel but surfaces it
// in full: live estimate, daily breakdown, 7-day bar chart, and the flow diagram.
public sealed partial class InverterViewModel : ObservableObject
{
    private readonly MpptClient      _ble   = ServiceLocator.Ble;
    private readonly ControllerStore _ctrls = ServiceLocator.Controllers;

    // Rolling 3-minute buffer for the live EA SUN estimate (mirrors Dashboard).
    private readonly List<LiveSample> _recentBuf = new();
    private const long LiveWindowMs = 3 * 60_000L;

    private readonly DispatcherTimer _energyTimer;
    private long _lastEnergyRefreshMs;
    private const long EnergyRefreshIntervalMs = 60_000L;

    // ---- Live estimate ----
    [ObservableProperty] private string _liveEasunLabel  = "—";
    [ObservableProperty] private double _easunWatts;
    [ObservableProperty] private double _easunNetA;
    [ObservableProperty] private bool   _hasLiveEasun;

    // ---- Inference source (Phase 2): which path produced the number above ----
    [ObservableProperty] private bool   _isModelLive;            // true = sag-based observer, false = legacy
    [ObservableProperty] private double _modelConfidencePct;
    [ObservableProperty] private string _modelStateLabel = "";   // e.g. "Live battery-model · 72%"

    // ---- Flow diagram inputs ----
    [ObservableProperty] private double _solarW;
    [ObservableProperty] private double _batteryNetW;
    [ObservableProperty] private double _soc;
    [ObservableProperty] private double _batteryVoltage;
    [ObservableProperty] private bool   _isLive;

    // ---- Daily breakdown ----
    [ObservableProperty] private double _todayEasunCharge;   // Wh
    [ObservableProperty] private double _todayEasunLoad;     // Wh
    [ObservableProperty] private string _todayEasunChargeKwh = "0.00 kWh";
    [ObservableProperty] private string _todayEasunLoadKwh   = "0.00 kWh";

    // ---- Battery profile availability ----
    [ObservableProperty] private bool   _hasBatteryProfile;
    [ObservableProperty] private string _noDataReason = "Requires a battery profile — set one in App Settings";

    // ---- 7-day bar chart data ----
    [ObservableProperty] private IReadOnlyList<double> _weekEasunCharge = Array.Empty<double>();
    [ObservableProperty] private IReadOnlyList<double> _weekEasunLoad   = Array.Empty<double>();
    [ObservableProperty] private IReadOnlyList<string> _weekDayLabels   = Array.Empty<string>();

    public InverterViewModel()
    {
        _ble.LiveChanged      += _ => RunOnUi(SyncLive);
        _ble.SettingsChanged  += _ => RunOnUi(SyncLive);
        _ble.StateChanged     += _ => RunOnUi(SyncState);
        _ctrls.Changed        += () => RunOnUi(SyncState);

        _energyTimer = new DispatcherTimer { Interval = TimeSpan.FromMinutes(1) };
        _energyTimer.Tick += (_, _) => RefreshEnergy();
        _energyTimer.Start();

        SyncState();
        SyncLive();
        RefreshEnergy();
    }

    // ---- Sync from BLE live data ----

    private void SyncState()
    {
        IsLive = _ble.State == ConnectionState.Ready || _ble.RelayActive;
    }

    private void SyncLive()
    {
        var live = _ble.Live;
        if (live == null)
        {
            HasLiveEasun   = false;
            LiveEasunLabel = "—";
            EasunWatts     = 0;
            EasunNetA      = 0;
            SolarW         = 0;
            BatteryNetW    = 0;
            Soc            = 0;
            BatteryVoltage = 0;
            IsLive         = false;
            return;
        }

        IsLive         = true;
        SolarW         = live.ApproxPvWatts;
        BatteryVoltage = live.BatteryVoltage;

        var sample = new LiveSample(
            TimestampMs:      live.TimestampMs,
            BatteryVoltage:   live.BatteryVoltage,
            ChargeCurrent:    live.ChargeCurrent,
            DischargeCurrent: live.DischargeCurrent,
            PvWatts:          live.ApproxPvWatts,
            LoadWatts:        live.LoadWatts,
            TemperatureC:     live.TemperatureC,
            SocPercent:       ResolveSoc(live));

        _recentBuf.Add(sample);
        long cutoff = live.TimestampMs - LiveWindowMs;
        _recentBuf.RemoveAll(r => r.TimestampMs < cutoff);

        var profile = CurrentProfile();
        HasBatteryProfile = profile != null;

        // SoC (for the flow diagram battery node).
        Soc = ResolveSoc(live);

        double mpptNetA = live.ChargeCurrent - live.DischargeCurrent;

        // Phase 2: prefer the sag-based observer once it clears the confidence
        // gate; otherwise fall back to the legacy 30 s differentiator. The
        // observer's BusLoadA is positive when the bus is drawing, so the EA SUN
        // net (positive = array charging the bank) is its negation.
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
            ModelStateLabel = $"Live battery-model · {est.ConfidencePct:0}%";
            LiveEasunLabel = netA >= 0
                ? $"EA SUN  +{netA:0.0} A  ·  +{EasunWatts:0} W  (array charging the bank)"
                : $"EA SUN  {netA:0.0} A  ·  {EasunWatts:0} W  (inverter drawing load)";
        }
        else if (easunA != null)
        {
            HasLiveEasun = true;
            IsModelLive  = false;
            EasunNetA    = easunA.Value;
            EasunWatts   = easunA.Value * live.BatteryVoltage;
            ModelStateLabel = est != null
                ? $"Calibrating · {est.ConfidencePct:0}% — legacy estimate"
                : "Legacy estimate";
            LiveEasunLabel = easunA.Value >= 0
                ? $"EA SUN  ~  +{easunA.Value:0.0} A  ·  +{EasunWatts:0} W  (array charging the bank)"
                : $"EA SUN  ~  {easunA.Value:0.0} A  ·  {EasunWatts:0} W  (inverter drawing load)";
        }
        else
        {
            HasLiveEasun   = false;
            IsModelLive    = false;
            EasunNetA      = 0;
            EasunWatts     = 0;
            ModelStateLabel = "";
            LiveEasunLabel = profile == null ? "—" : "Warming up…";
        }

        // Total battery throughput for the flow diagram.
        double totalNetA = mpptNetA + EasunNetA;
        BatteryNetW = totalNetA * live.BatteryVoltage;

        // Throttled daily refresh.
        long now = Environment.TickCount64;
        if (now - _lastEnergyRefreshMs > EnergyRefreshIntervalMs)
            RefreshEnergy();
    }

    // ---- Energy refresh (daily + weekly) ----

    private void RefreshEnergy()
    {
        _lastEnergyRefreshMs = Environment.TickCount64;
        var mac = _ble.CurrentMac ?? _ctrls.CurrentMac;
        if (string.IsNullOrEmpty(mac)) return;

        var profile = CurrentProfile();
        HasBatteryProfile = profile != null;

        long todayStart = DayStartMs(0);

        // Today.
        var todayRows = ServiceLocator.History.Query(mac!, todayStart);
        var today     = EnergyComputer.ComputeDay(todayRows, todayStart, profile);
        TodayEasunCharge    = today.EasunChargeWh;
        TodayEasunLoad      = today.EasunLoadWh;
        TodayEasunChargeKwh = (today.EasunChargeWh / 1000.0).ToString("0.00", CultureInfo.InvariantCulture) + " kWh";
        TodayEasunLoadKwh   = (today.EasunLoadWh   / 1000.0).ToString("0.00", CultureInfo.InvariantCulture) + " kWh";

        // 7-day series.
        var chargeArr = new double[7];
        var loadArr   = new double[7];
        var labels    = new string[7];
        for (int i = 6; i >= 0; i--)
        {
            long dayStart = DayStartMs(-i);
            long dayEnd   = DayStartMs(-i + 1);
            var rows = ServiceLocator.History.Query(mac!, dayStart)
                           .Where(r => r.TimestampMs < dayEnd)
                           .ToList();
            var day = EnergyComputer.ComputeDay(rows, dayStart, profile);
            int idx = 6 - i;
            chargeArr[idx] = day.EasunChargeWh;
            loadArr[idx]   = day.EasunLoadWh;
            labels[idx]    = DateTimeOffset.FromUnixTimeMilliseconds(dayStart)
                                 .ToLocalTime()
                                 .ToString("ddd", CultureInfo.InvariantCulture);
        }
        WeekEasunCharge = chargeArr;
        WeekEasunLoad   = loadArr;
        WeekDayLabels   = labels;
    }

    // ---- Helpers ----

    private BatteryProfile? CurrentProfile()
    {
        var mac = _ble.CurrentMac ?? _ctrls.CurrentMac;
        return mac != null ? _ctrls.Find(mac)?.ResolvedBatteryProfile() : null;
    }

    private double ResolveSoc(MpptLive live)
    {
        var v = live.BatteryVoltage;
        if (_ble.Settings?.ComputeSoc(v) is { } a) return a;
        var mac = _ble.CurrentMac ?? _ctrls.CurrentMac;
        if (mac != null && _ctrls.Find(mac)?.ComputeSocFromCache(v) is { } b) return b;
        return live.SocEstimate;
    }

    private static long DayStartMs(int offsetDays)
    {
        var d = DateTime.Now.Date.AddDays(offsetDays);
        return new DateTimeOffset(d, TimeZoneInfo.Local.GetUtcOffset(d)).ToUnixTimeMilliseconds();
    }

    private static void RunOnUi(Action a)
    {
        var d = Application.Current?.Dispatcher;
        if (d == null || d.CheckAccess()) a();
        else d.BeginInvoke(a);
    }
}
