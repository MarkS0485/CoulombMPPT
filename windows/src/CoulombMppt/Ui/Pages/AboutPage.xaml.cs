using System.Diagnostics;
using System.IO;
using System.Reflection;
using System.Windows;
using System.Windows.Controls;
using CoulombMppt.Services;

namespace CoulombMppt.Ui.Pages;

public partial class AboutPage : UserControl
{
    private static readonly string DataDir = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "CoulombMppt");

    public AboutPage()
    {
        InitializeComponent();
        var ver = Assembly.GetExecutingAssembly().GetName().Version?.ToString() ?? "?";
        VersionLine.Text = $"Version {ver}";
        LogPathLine.Text = Log.LogPath;
        DataPathLine.Text = DataDir;
    }

    private void OnOpenLog(object sender, RoutedEventArgs e)
        => OpenFolder(Path.GetDirectoryName(Log.LogPath));

    private void OnOpenData(object sender, RoutedEventArgs e)
        => OpenFolder(DataDir);

    private static void OpenFolder(string? dir)
    {
        if (!string.IsNullOrEmpty(dir) && Directory.Exists(dir))
            Process.Start(new ProcessStartInfo("explorer.exe", dir) { UseShellExecute = true });
    }
}
