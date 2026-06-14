using System.Diagnostics;
using System.IO;
using System.Text;
using System.Windows;
using System.Windows.Controls;
using CoulombMppt.Services;

namespace CoulombMppt.Ui.Pages;

// Code-behind live log viewer. Seeds from the in-memory ring buffer, then tails
// Log.LineWritten. "Follow" keeps the view pinned to the newest line; turn it
// off to scroll back without the view yanking to the bottom on each new line.
public partial class LogPage : UserControl
{
    private const int MaxChars = 200_000;   // keep the TextBox from growing unbounded

    private bool _following = true;

    public LogPage()
    {
        InitializeComponent();
        LogPathLine.Text = Log.LogPath;
        // This page is cached and reused. Reseed from the ring buffer and
        // (re)attach the live tail every time it is shown — subscribing once in
        // the constructor would leave the tail dead after the first navigation
        // away, since Unloaded detaches it and the constructor never re-runs.
        Loaded += (_, _) =>
        {
            Reload();
            Log.LineWritten -= OnLine;
            Log.LineWritten += OnLine;
        };
        Unloaded += (_, _) => Log.LineWritten -= OnLine;
    }

    private void OnFollowToggled(object sender, RoutedEventArgs e)
        => _following = (sender as System.Windows.Controls.Primitives.ToggleButton)?.IsChecked == true;

    private void OnLine(string line)
    {
        Dispatcher.BeginInvoke(() =>
        {
            LogBox.AppendText(line + "\n");
            if (LogBox.Text.Length > MaxChars)
                LogBox.Text = LogBox.Text[^MaxChars..];
            if (_following)
            {
                LogBox.CaretIndex = LogBox.Text.Length;
                LogBox.ScrollToEnd();
            }
        });
    }

    private void Reload()
    {
        var sb = new StringBuilder();
        foreach (var l in Log.Recent()) sb.Append(l).Append('\n');
        LogBox.Text = sb.ToString();
        LogBox.ScrollToEnd();
    }

    private void OnReload(object sender, RoutedEventArgs e) => Reload();

    private void OnOpenFolder(object sender, RoutedEventArgs e)
    {
        var dir = Path.GetDirectoryName(Log.LogPath);
        if (dir != null)
            Process.Start(new ProcessStartInfo("explorer.exe", dir) { UseShellExecute = true });
    }
}
