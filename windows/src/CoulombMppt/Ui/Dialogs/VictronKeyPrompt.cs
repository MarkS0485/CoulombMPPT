using System.Windows;
using System.Windows.Controls;
using CoulombMppt.Ble;

namespace CoulombMppt.Ui.Dialogs;

// Tiny modal prompt for a Victron Instant Readout key, built in code so it
// needs no XAML/resource wiring. Returns the trimmed key, or null if the user
// cancelled or entered something that isn't a 16-byte hex key.
public static class VictronKeyPrompt
{
    public static string? Show(string deviceName)
    {
        var win = new Window
        {
            Title = "Victron device",
            Width = 480,
            SizeToContent = SizeToContent.Height,
            WindowStartupLocation = WindowStartupLocation.CenterOwner,
            ResizeMode = ResizeMode.NoResize,
            Owner = Application.Current?.MainWindow,
            ShowInTaskbar = false,
        };

        var root = new StackPanel { Margin = new Thickness(16) };
        root.Children.Add(new TextBlock
        {
            Text = $"“{deviceName}” broadcasts encrypted Instant Readout data. " +
                   "Paste its advertisement key — in VictronConnect open the device, then " +
                   "Settings → Product info → “Instant readout via Bluetooth” → " +
                   "Show encryption key.",
            TextWrapping = TextWrapping.Wrap,
            Margin = new Thickness(0, 0, 0, 10),
        });

        var box = new TextBox { FontFamily = new System.Windows.Media.FontFamily("Consolas") };
        root.Children.Add(box);

        var hint = new TextBlock
        {
            Text = "32 hexadecimal characters.",
            Opacity = 0.6,
            Margin = new Thickness(0, 4, 0, 0),
        };
        root.Children.Add(hint);

        var buttons = new StackPanel
        {
            Orientation = Orientation.Horizontal,
            HorizontalAlignment = HorizontalAlignment.Right,
            Margin = new Thickness(0, 14, 0, 0),
        };
        string? result = null;
        var ok = new Button { Content = "Pair", Width = 84, Margin = new Thickness(0, 0, 8, 0), IsDefault = true, IsEnabled = false };
        var cancel = new Button { Content = "Cancel", Width = 84, IsCancel = true };
        box.TextChanged += (_, _) => ok.IsEnabled = VictronDecoder.IsValidKey(box.Text);
        ok.Click += (_, _) => { result = box.Text.Trim(); win.DialogResult = true; };
        buttons.Children.Add(ok);
        buttons.Children.Add(cancel);
        root.Children.Add(buttons);

        win.Content = root;
        return win.ShowDialog() == true && VictronDecoder.IsValidKey(result) ? result : null;
    }
}
