using System.ComponentModel;
using System.Globalization;
using System.Windows;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using CoulombMppt.Ble;
using CoulombMppt.Data;
using CoulombMppt.Services;

namespace CoulombMppt.Ui.ViewModels;

// Editable view of the controller's writable settings. Reads the current values
// into the form, lets the user change them, and writes each changed register
// back.
//
// Important: the poll loop re-reads the settings block on a timer, which fires
// SettingsChanged. If we blindly re-populated the form on every such read we'd
// wipe whatever the user is mid-way through editing — most visibly the "Manual
// load output" checkbox, which would silently snap back to the controller's
// reported state before Apply ran, so the write always sent the OLD value. To
// avoid that we track a "dirty" flag: once the user touches any field we stop
// auto-syncing until they Apply or explicitly Read.
public sealed partial class SettingsViewModel : ObservableObject
{
    private readonly MpptClient _ble = ServiceLocator.Ble;

    // True while SyncForm is writing the properties, so the dirty-tracker can
    // tell our own programmatic updates apart from genuine user edits.
    private bool _syncing;
    // True once the user has changed a field that Apply hasn't yet committed.
    private bool _dirty;

    private static readonly HashSet<string> EditableFields = new()
    {
        nameof(BatteryTypeIndex), nameof(OutputModeIndex), nameof(ManualLoadOn),
        nameof(ChargeVoltageInput), nameof(CutoffVoltageInput), nameof(RecoveryVoltageInput),
        nameof(TimerHoursInput), nameof(TimerMinutesInput), nameof(VoltageMonitorModeInput),
    };

    public SettingsViewModel()
    {
        _ble.SettingsChanged += _ => RunOnUi(() => { if (!_dirty) SyncForm(); });
        _ble.StateChanged    += _ => RunOnUi(() => IsReady = _ble.State == ConnectionState.Ready);
        _ble.LastErrorChanged += e => RunOnUi(() => Status = e);
        IsReady = _ble.State == ConnectionState.Ready;
        SyncForm();
    }

    // Any user edit (a property change that didn't originate inside SyncForm)
    // marks the form dirty so background polls stop overwriting it.
    protected override void OnPropertyChanged(PropertyChangedEventArgs e)
    {
        base.OnPropertyChanged(e);
        if (!_syncing && e.PropertyName != null && EditableFields.Contains(e.PropertyName))
            _dirty = true;
    }

    [ObservableProperty] private bool   _isReady;
    [ObservableProperty] private bool   _hasSettings;
    [ObservableProperty] private bool   _busy;
    [ObservableProperty] private string _status = "";

    // Battery type combo index == firmware code (0 unknown … 4 lithium).
    [ObservableProperty] private int _batteryTypeIndex;
    // Output mode combo index == firmware code (0 manual, 1 auto, 2 timer).
    [ObservableProperty] private int _outputModeIndex;
    [ObservableProperty] private bool _manualLoadOn;

    [ObservableProperty] private string _chargeVoltageInput = "";
    [ObservableProperty] private string _cutoffVoltageInput = "";
    [ObservableProperty] private string _recoveryVoltageInput = "";
    [ObservableProperty] private string _timerHoursInput = "0";
    [ObservableProperty] private string _timerMinutesInput = "0";
    [ObservableProperty] private string _voltageMonitorModeInput = "0";

    private void SyncForm()
    {
        var s = _ble.Settings;
        HasSettings = s != null;
        if (s == null) return;

        _syncing = true;
        try
        {
            BatteryTypeIndex        = Math.Clamp(s.BatteryType, 0, 4);
            OutputModeIndex         = s.OutputMode is >= 0 and <= 2 ? s.OutputMode : 0;
            ManualLoadOn            = s.ManualLoadOn;
            ChargeVoltageInput      = s.ChargeVoltageSetpoint.ToString("0.0", CultureInfo.InvariantCulture);
            CutoffVoltageInput      = s.CutoffVoltageSetpoint.ToString("0.0", CultureInfo.InvariantCulture);
            RecoveryVoltageInput    = s.RecoveryVoltageSetpoint.ToString("0.0", CultureInfo.InvariantCulture);
            TimerHoursInput         = s.TimerHours.ToString(CultureInfo.InvariantCulture);
            TimerMinutesInput       = s.TimerMinutes.ToString(CultureInfo.InvariantCulture);
            VoltageMonitorModeInput = s.VoltageMonitorMode.ToString(CultureInfo.InvariantCulture);
        }
        finally { _syncing = false; }

        // A fresh populate is, by definition, in sync with the controller.
        _dirty = false;
    }

    [RelayCommand]
    private async Task Read()
    {
        if (!await Ensure()) return;
        Busy = true;
        try
        {
            // Explicit Read discards any in-progress edits and re-pulls.
            _dirty = false;
            await _ble.ReadSettingsAsync();
            SyncForm();
            Status = "Settings read.";
        }
        finally { Busy = false; }
    }

    [RelayCommand]
    private async Task Apply()
    {
        if (!await Ensure()) return;
        Busy = true;
        try
        {
            bool ok = true;
            ok &= await _ble.SetBatteryTypeAsync(BatteryTypeIndex);
            ok &= await _ble.SetOutputModeAsync(OutputModeIndex);
            ok &= await _ble.SetManualLoadAsync(ManualLoadOn);

            if (TryV(ChargeVoltageInput, out var cv))   ok &= await _ble.SetChargeVoltageAsync(cv);
            if (TryV(CutoffVoltageInput, out var ov))   ok &= await _ble.SetCutoffVoltageAsync(ov);
            if (TryV(RecoveryVoltageInput, out var rv)) ok &= await _ble.SetRecoveryVoltageAsync(rv);

            if (int.TryParse(VoltageMonitorModeInput, out var vm))
                ok &= await _ble.SetVoltageMonitorModeAsync(vm);

            if (int.TryParse(TimerHoursInput, out var th) && int.TryParse(TimerMinutesInput, out var tm))
                ok &= await _ble.SetTimerAsync(th, tm);

            Status = ok ? "Settings written." : "One or more writes failed — check the log.";
        }
        finally
        {
            // Writes re-read the block; let the accepted values repopulate now
            // that the user's edit is committed.
            _dirty = false;
            SyncForm();
            Busy = false;
        }
    }

    [RelayCommand]
    private async Task ToggleLoad()
    {
        if (!await Ensure()) return;
        Busy = true;
        try
        {
            // Flip the controller's *actual* current load state (not the form's,
            // which may be mid-edit). Source of truth is the last settings read.
            bool current = _ble.Settings?.ManualLoadOn ?? ManualLoadOn;
            bool target  = !current;
            bool ok = await _ble.SetManualLoadAsync(target);
            if (!ok) { Status = "Load command not acknowledged — check the log."; return; }

            // The manual-load register only takes effect in Manual output mode;
            // warn rather than let the toggle look like a silent no-op.
            int mode = _ble.Settings?.OutputMode ?? OutputModeIndex;
            Status = (target, mode == 0) switch
            {
                (true,  true)  => "Load turned ON.",
                (true,  false) => "Load ON sent — but output mode isn't Manual, so the controller may ignore it. Set Output mode = Manual and Apply.",
                (false, true)  => "Load turned OFF.",
                (false, false) => "Load OFF sent — output mode isn't Manual; the controller may ignore it.",
            };
        }
        finally { Busy = false; }
    }

    private async Task<bool> Ensure()
    {
        if (_ble.State == ConnectionState.Ready) return true;
        var mac = ServiceLocator.Controllers.CurrentMac;
        if (string.IsNullOrEmpty(mac)) { Status = "No controller selected."; return false; }
        Status = "Connecting…";
        _ = _ble.ConnectAsync(mac);
        bool ready = await _ble.WaitReadyAsync(12_000);
        if (!ready) Status = "Could not connect.";
        return ready;
    }

    private static bool TryV(string s, out double v)
        => double.TryParse(s, NumberStyles.Float, CultureInfo.InvariantCulture, out v);

    private static void RunOnUi(Action a)
    {
        var d = Application.Current?.Dispatcher;
        if (d == null || d.CheckAccess()) a();
        else d.BeginInvoke(a);
    }
}
