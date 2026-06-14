using System.Globalization;
using System.Windows;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using CoulombMppt.Data;
using CoulombMppt.Services;

namespace CoulombMppt.Ui.ViewModels;

// Editable view of the app-wide preferences (AppSettings). Each property writes
// straight through to the store via the generated OnXxxChanged partials; the
// store's Changed event re-syncs the form so external mutations stay reflected.
// A guard flag stops the write-back ↔ re-sync round-trip from looping.
public sealed partial class AppSettingsViewModel : ObservableObject
{
    private readonly AppSettings _settings = ServiceLocator.Settings;
    private bool _syncing;

    [ObservableProperty] private bool _autoReconnect;
    [ObservableProperty] private int  _apiPort;
    [ObservableProperty] private bool _apiAutoStart;
    [ObservableProperty] private bool _upnpEnabled;
    [ObservableProperty] private bool _recordHistory;
    [ObservableProperty] private int  _historyEverySec;
    [ObservableProperty] private bool _alertsEnabled;

    private void SyncFromStore()
    {
        _syncing = true;
        try
        {
            AutoReconnect   = _settings.AutoReconnect;
            ApiPort         = _settings.ApiPort;
            ApiAutoStart    = _settings.ApiAutoStart;
            UpnpEnabled     = _settings.UpnpEnabled;
            RecordHistory   = _settings.RecordHistory;
            HistoryEverySec = _settings.HistoryEverySec;
            AlertsEnabled   = _settings.AlertsEnabled;
        }
        finally { _syncing = false; }
    }

    partial void OnAutoReconnectChanged(bool value)   { if (!_syncing) _settings.SetAutoReconnect(value); }
    partial void OnApiPortChanged(int value)          { if (!_syncing) _settings.SetApiPort(value); }
    partial void OnApiAutoStartChanged(bool value)    { if (!_syncing) _settings.SetApiAutoStart(value); }
    partial void OnUpnpEnabledChanged(bool value)     { if (!_syncing) _settings.SetUpnpEnabled(value); }
    partial void OnRecordHistoryChanged(bool value)   { if (!_syncing) _settings.SetRecordHistory(value); }
    partial void OnHistoryEverySecChanged(int value)  { if (!_syncing) _settings.SetHistoryEverySec(value); }
    partial void OnAlertsEnabledChanged(bool value)   { if (!_syncing) _settings.SetAlertsEnabled(value); }

    // --- Battery pack profile (per current controller) ----
    private readonly ControllerStore _ctrls = ServiceLocator.Controllers;

    // String inputs — empty = not set. Saved via SaveBatteryPack command.
    [ObservableProperty] private string _packNominalVInput  = "";
    [ObservableProperty] private string _packFullVInput     = "";
    [ObservableProperty] private string _packEmptyVInput    = "";
    [ObservableProperty] private string _packCapacityAhInput = "";
    [ObservableProperty] private string _packSummary        = "No controller selected.";

    public AppSettingsViewModel()
    {
        _settings.Changed += () => RunOnUi(SyncFromStore);
        _ctrls.Changed    += () => RunOnUi(SyncPackFromController);
        SyncFromStore();
        SyncPackFromController();
    }

    private void SyncPackFromController()
    {
        var mac = ServiceLocator.Ble.CurrentMac ?? _ctrls.CurrentMac;
        var ctrl = mac != null ? _ctrls.Find(mac) : null;
        if (ctrl == null) { PackSummary = "No controller selected."; return; }

        _syncing = true;
        try
        {
            PackNominalVInput   = ctrl.PackNominalV?.ToString("0.##", CultureInfo.InvariantCulture)  ?? "";
            PackFullVInput      = ctrl.PackUserFullV?.ToString("0.##", CultureInfo.InvariantCulture) ?? "";
            PackEmptyVInput     = ctrl.PackUserEmptyV?.ToString("0.##", CultureInfo.InvariantCulture) ?? "";
            PackCapacityAhInput = ctrl.PackCapacityAh?.ToString("0.#", CultureInfo.InvariantCulture)  ?? "";

            var p = ctrl.ResolvedBatteryProfile();
            PackSummary = p != null
                ? $"Profile active: {p.EmptyV}–{p.FullV} V · {p.CapacityAh} Ah · ≈{p.CapacityWh / 1000.0:0.2f} kWh"
                : "Enter full V, empty V, and Ah to enable battery energy tracking.";
        }
        finally { _syncing = false; }
    }

    [RelayCommand]
    private void SaveBatteryPack()
    {
        var mac = ServiceLocator.Ble.CurrentMac ?? _ctrls.CurrentMac;
        if (string.IsNullOrEmpty(mac)) return;
        _ctrls.UpdateEnergyProfile(
            mac,
            nominalV:   PackNominalVInput.ToNullableDouble(),
            fullV:      PackFullVInput.ToNullableDouble(),
            emptyV:     PackEmptyVInput.ToNullableDouble(),
            capacityAh: PackCapacityAhInput.ToNullableDouble());
        SyncPackFromController();
    }

    private static void RunOnUi(Action a)
    {
        var d = Application.Current?.Dispatcher;
        if (d == null || d.CheckAccess()) a();
        else d.BeginInvoke(a);
    }
}

file static class StringExtensions
{
    public static double? ToNullableDouble(this string s)
        => double.TryParse(s, System.Globalization.NumberStyles.Any,
                           System.Globalization.CultureInfo.InvariantCulture, out var d) ? d : null;
}
