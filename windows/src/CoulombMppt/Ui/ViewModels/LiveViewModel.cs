using System.Globalization;
using System.Windows;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using CoulombMppt.Ble;
using CoulombMppt.Data;
using CoulombMppt.Services;

namespace CoulombMppt.Ui.ViewModels;

// Full live-telemetry readout — every decoded field plus the raw status
// registers, for when you want to see exactly what the controller reported.
public sealed partial class LiveViewModel : ObservableObject
{
    private readonly MpptClient _ble = ServiceLocator.Ble;

    // Rolling in-memory buffer of the last hour of decoded frames, fed straight
    // off LiveChanged so the charts advance at the poll cadence (~1 Hz) without
    // a round-trip through the history file. Capped by age, so it self-trims.
    private const long LiveWindowMs = 60L * 60 * 1000;     // 1 hour
    private readonly List<(long Ts, double V, double ICharge, double Pv)> _liveBuf = new();

    public LiveViewModel()
    {
        _ble.LiveChanged  += _ => RunOnUi(Sync);
        _ble.StateChanged += _ => RunOnUi(SyncState);
        Sync();
        SyncState();
    }

    [ObservableProperty] private bool   _isReady;
    [ObservableProperty] private bool   _hasLive;
    [ObservableProperty] private string _updatedLabel = "—";

    [ObservableProperty] private string _batteryVolts = "—";
    [ObservableProperty] private string _chargeCurrent = "—";
    [ObservableProperty] private string _dischargeCurrent = "—";
    [ObservableProperty] private string _batteryWatts = "—";
    [ObservableProperty] private string _loadWatts = "—";
    [ObservableProperty] private string _pvWatts = "—";
    [ObservableProperty] private string _temperature = "—";
    [ObservableProperty] private string _totalAh = "—";
    [ObservableProperty] private string _soc = "—";
    [ObservableProperty] private string _chargerState = "—";

    [ObservableProperty] private string _solarStatusRaw = "—";
    [ObservableProperty] private string _workStatusRaw = "—";
    [ObservableProperty] private string _powerStatusRaw = "—";

    // --- Live rolling chart series (last hour, ~1 Hz) ----
    [ObservableProperty] private IReadOnlyList<double> _voltageHistory = Array.Empty<double>();
    [ObservableProperty] private IReadOnlyList<double> _chargeHistory  = Array.Empty<double>();
    [ObservableProperty] private IReadOnlyList<double> _pvHistory      = Array.Empty<double>();
    // Shared time axis (unix-ms) for all three live charts — drives the hover
    // tooltip's timestamp readout.
    [ObservableProperty] private IReadOnlyList<long>   _timeAxis        = Array.Empty<long>();
    [ObservableProperty] private string _liveChartCaption = "Waiting for telemetry…";

    [RelayCommand]
    private async Task Refresh()
    {
        if (_ble.State == ConnectionState.Ready) await _ble.ReadSettingsAsync();
    }

    private void SyncState() => IsReady = _ble.State == ConnectionState.Ready;

    private void Sync()
    {
        var l = _ble.Live;
        HasLive = l != null;
        if (l == null) return;

        UpdatedLabel     = DateTimeOffset.FromUnixTimeMilliseconds(l.TimestampMs).ToLocalTime().ToString("HH:mm:ss", CultureInfo.InvariantCulture);
        BatteryVolts     = $"{l.BatteryVoltage:0.00} V";
        ChargeCurrent    = $"{l.ChargeCurrent:0.00} A";
        DischargeCurrent = $"{l.DischargeCurrent:0.00} A";
        BatteryWatts     = $"{l.BatteryWatts:0.0} W";
        LoadWatts        = $"{l.LoadWatts:0.0} W";
        PvWatts          = $"{l.ApproxPvWatts:0.0} W";
        Temperature      = $"{l.TemperatureC:0.0} °C";
        TotalAh          = $"{l.TotalAccumulatedAh:0.0} Ah";
        Soc              = $"{l.SocEstimate:0} %";
        ChargerState     = l.ChargerState.Label();

        SolarStatusRaw   = $"0x{l.SolarStatusRaw:X4} ({l.SolarStatusRaw})";
        WorkStatusRaw    = $"0x{l.WorkStatusRaw:X4} ({l.WorkStatusRaw})";
        PowerStatusRaw   = $"0x{l.PowerStatusRaw:X4} ({l.PowerStatusRaw})";

        PushLiveSample(l);
    }

    // Append the newest frame to the rolling buffer, drop anything older than
    // the window, and republish the series so the live charts redraw.
    private void PushLiveSample(MpptLive l)
    {
        _liveBuf.Add((l.TimestampMs, l.BatteryVoltage, l.ChargeCurrent, l.ApproxPvWatts));

        long cutoff = l.TimestampMs - LiveWindowMs;
        int drop = 0;
        while (drop < _liveBuf.Count && _liveBuf[drop].Ts < cutoff) drop++;
        if (drop > 0) _liveBuf.RemoveRange(0, drop);

        VoltageHistory = _liveBuf.Select(x => x.V).ToArray();
        ChargeHistory  = _liveBuf.Select(x => x.ICharge).ToArray();
        PvHistory      = _liveBuf.Select(x => x.Pv).ToArray();
        TimeAxis       = _liveBuf.Select(x => x.Ts).ToArray();

        var span = (_liveBuf[^1].Ts - _liveBuf[0].Ts) / 60000.0;
        LiveChartCaption = _liveBuf.Count < 2
            ? "Collecting… charts fill as telemetry streams in"
            : $"Last {span:0} min · {_liveBuf.Count} samples (1 Hz, in-memory)";
    }

    private static void RunOnUi(Action a)
    {
        var d = Application.Current?.Dispatcher;
        if (d == null || d.CheckAccess()) a();
        else d.BeginInvoke(a);
    }
}
