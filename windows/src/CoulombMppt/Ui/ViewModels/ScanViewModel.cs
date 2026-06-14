using System.Collections.ObjectModel;
using System.Windows;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using CoulombMppt.Ble;
using CoulombMppt.Data;
using CoulombMppt.Services;
using CoulombMppt.Ui.Dialogs;

namespace CoulombMppt.Ui.ViewModels;

// BLE discovery + pairing. Lists nearby advertisers (known-MPPT ones flagged),
// pairs the chosen one into the ControllerStore and connects, and manages the
// list of already-paired controllers.
public sealed partial class ScanViewModel : ObservableObject
{
    private readonly MpptClient      _ble   = ServiceLocator.Ble;
    private readonly ControllerStore _ctrls = ServiceLocator.Controllers;

    public ScanViewModel()
    {
        _ble.ScanningChanged += _ => RunOnUi(() => IsScanning = _ble.Scanning);
        _ble.DevicesChanged  += () => RunOnUi(RebuildDevices);
        _ctrls.Changed       += () => RunOnUi(RebuildPaired);
        IsScanning = _ble.Scanning;
        RebuildDevices();
        RebuildPaired();
    }

    [ObservableProperty] private bool _isScanning;

    public ObservableCollection<DiscoveredDevice> Devices { get; } = new();
    public ObservableCollection<MpptController> Paired { get; } = new();

    [RelayCommand]
    private void ToggleScan()
    {
        if (_ble.Scanning) _ble.StopScan();
        else _ble.StartScan();
    }

    [RelayCommand]
    private async Task Pair(DiscoveredDevice? d)
    {
        if (d == null) return;
        if (d.IsVictron)
        {
            // Victron needs its per-device Instant Readout key before we can
            // decode anything — prompt, and bail if cancelled/invalid.
            var key = VictronKeyPrompt.Show(d.Display);
            if (!VictronDecoder.IsValidKey(key)) return;
            _ctrls.Pair(d.Mac, d.Name, DeviceType.VictronInstantReadout, key);
        }
        else
        {
            _ctrls.Pair(d.Mac, d.Name);
        }
        _ble.StopScan();
        await _ble.ConnectAsync(d.Mac);
    }

    [RelayCommand]
    private async Task Connect(MpptController? c)
    {
        if (c == null) return;
        _ctrls.SetCurrent(c.Mac);
        await _ble.ConnectAsync(c.Mac);
    }

    [RelayCommand]
    private async Task Forget(MpptController? c)
    {
        if (c == null) return;
        // Drop the BLE link first if we're connected to (or chasing) this one,
        // otherwise the reconnect loop keeps targeting a MAC we just deleted —
        // the desktop equivalent of the Android "had to delete and re-add it"
        // confusion.
        await _ble.DisconnectIfTargetingAsync(c.Mac);
        _ctrls.Remove(c.Mac);
    }

    private void RebuildDevices()
    {
        Devices.Clear();
        foreach (var d in _ble.Devices.OrderByDescending(x => x.IsKnownMppt).ThenByDescending(x => x.Rssi))
            Devices.Add(d);
    }

    private void RebuildPaired()
    {
        Paired.Clear();
        foreach (var c in _ctrls.All) Paired.Add(c);
    }

    private static void RunOnUi(Action a)
    {
        var d = Application.Current?.Dispatcher;
        if (d == null || d.CheckAccess()) a();
        else d.BeginInvoke(a);
    }
}
