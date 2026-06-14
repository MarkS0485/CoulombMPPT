using System.Collections.ObjectModel;
using System.Globalization;
using System.Windows;
using System.Windows.Controls;
using CoulombMppt.Ble;
using CoulombMppt.Services;

namespace CoulombMppt.Ui.Pages;

// Code-behind page (no view-model): a live tap on the BLE transport's raw frame
// stream plus a manual register-write tool. Diagnostics are inherently
// imperative — subscribe, marshal, append, cap — so a VM would add no value.
public partial class DiagnosticsPage : UserControl
{
    private const int MaxRows = 500;

    private readonly MpptClient _ble = ServiceLocator.Ble;
    private readonly ObservableCollection<FrameRow> _rows = new();
    private bool _paused;

    public DiagnosticsPage()
    {
        InitializeComponent();
        FrameList.ItemsSource = _rows;
        // This page is cached and reused, so the live tap must be (re)attached
        // every time it is shown — not once in the constructor. Subscribing on
        // Loaded (idempotently) and detaching on Unloaded keeps the frame
        // monitor working on the second and later visits.
        Loaded   += (_, _) => { _ble.FrameSeen -= OnFrame; _ble.FrameSeen += OnFrame; };
        Unloaded += (_, _) => _ble.FrameSeen -= OnFrame;
    }

    private sealed record FrameRow(string Time, string Dir, string Hex, bool Tx);

    private void OnPauseToggled(object sender, RoutedEventArgs e)
        => _paused = (sender as System.Windows.Controls.Primitives.ToggleButton)?.IsChecked == true;

    private void OnFrame(RawFrame f)
    {
        Dispatcher.BeginInvoke(() =>
        {
            if (_paused) return;

            var time = DateTimeOffset.FromUnixTimeMilliseconds(f.TimestampMs)
                                     .ToLocalTime().ToString("HH:mm:ss.fff", CultureInfo.InvariantCulture);
            _rows.Add(new FrameRow(time, f.Tx ? "TX" : "RX", f.Hex, f.Tx));
            while (_rows.Count > MaxRows) _rows.RemoveAt(0);
            FrameScroll.ScrollToBottom();
        });
    }

    private void OnClear(object sender, RoutedEventArgs e) => _rows.Clear();

    private async void OnWriteRegister(object sender, RoutedEventArgs e)
    {
        if (!TryParseNum(RegBox.Text, out int reg) || !TryParseNum(ValBox.Text, out int val))
        {
            WriteStatus.Text = "Enter a valid register and value.";
            return;
        }

        if (_ble.State != ConnectionState.Ready)
        {
            WriteStatus.Text = "Not connected.";
            return;
        }

        WriteStatus.Text = "Writing…";
        try
        {
            bool ok = await _ble.WriteRegisterAsync(reg, val);
            WriteStatus.Text = ok ? $"Wrote 0x{reg:X4} = {val}." : "Write failed.";
        }
        catch (Exception ex)
        {
            WriteStatus.Text = $"Error: {ex.Message}";
        }
    }

    private static bool TryParseNum(string s, out int value)
    {
        s = s.Trim();
        if (s.StartsWith("0x", StringComparison.OrdinalIgnoreCase))
            return int.TryParse(s[2..], NumberStyles.HexNumber, CultureInfo.InvariantCulture, out value);
        return int.TryParse(s, NumberStyles.Integer, CultureInfo.InvariantCulture, out value);
    }
}
