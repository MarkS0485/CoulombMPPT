using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Media.Imaging;
using CoulombMppt.Ble;
using CoulombMppt.Data;
using CoulombMppt.Services;
using CoulombMppt.Ui.Pages;
using Drawing = System.Drawing;
using WinForms = System.Windows.Forms;

namespace CoulombMppt;

public partial class MainWindow : Window, IDisposable
{
    // System-tray presence. Minimising hides the window to the tray; the icon's
    // double-click / "Open" restores it, "Exit" really quits. Uses WinForms
    // NotifyIcon (no extra NuGet) — fully qualified so it doesn't clash with WPF.
    private WinForms.NotifyIcon? _tray;
    private bool _trayHintShown;

    // One instance per destination so view-models (and BLE observers they own)
    // stay alive. Selecting a destination just swaps which one is in the content
    // area. Mirrors the heater client's code-behind rail router.
    private readonly Dictionary<string, UserControl> _pages = new();
    private string _current = "dashboard";

    public MainWindow()
    {
        InitializeComponent();
        Services.Navigation.Changed += OnOverlayChanged;
        ServiceLocator.Ble.StateChanged    += _ => Dispatcher.BeginInvoke(RefreshStatus);
        ServiceLocator.Ble.LiveChanged     += _ => Dispatcher.BeginInvoke(RefreshStatus);
        ServiceLocator.Ble.ScanningChanged += _ => Dispatcher.BeginInvoke(RefreshStatus);
        ServiceLocator.Controllers.Changed += () => Dispatcher.BeginInvoke(RefreshStatus);

        Select("dashboard");
        RefreshStatus();
        InitTray();
    }

    // --- System tray ----

    private void InitTray()
    {
        var iconUri = new Uri("pack://application:,,,/Assets/app.ico");
        try { Icon = BitmapFrame.Create(iconUri); } catch { /* title-bar icon is cosmetic */ }

        _tray = new WinForms.NotifyIcon { Text = "CoulombMPPT", Visible = true };
        try
        {
            var res = System.Windows.Application.GetResourceStream(iconUri);
            if (res != null) _tray.Icon = new Drawing.Icon(res.Stream);
            else _tray.Icon = Drawing.SystemIcons.Application;
        }
        catch { _tray.Icon = Drawing.SystemIcons.Application; }

        var menu = new WinForms.ContextMenuStrip();
        menu.Items.Add("Open CoulombMPPT", null, (_, _) => RestoreFromTray());
        menu.Items.Add(new WinForms.ToolStripSeparator());
        menu.Items.Add("Exit", null, (_, _) =>
        {
            // Tear the icon down first so it doesn't linger after shutdown.
            if (_tray != null) { _tray.Visible = false; _tray.Dispose(); _tray = null; }
            System.Windows.Application.Current.Shutdown();
        });
        _tray.ContextMenuStrip = menu;
        _tray.DoubleClick += (_, _) => RestoreFromTray();
    }

    private void RestoreFromTray()
    {
        Show();
        WindowState = WindowState.Normal;
        ShowInTaskbar = true;
        Activate();
    }

    protected override void OnStateChanged(EventArgs e)
    {
        base.OnStateChanged(e);
        if (WindowState == WindowState.Minimized)
        {
            // Drop out of the taskbar and live only in the tray.
            Hide();
            ShowInTaskbar = false;
            if (!_trayHintShown)
            {
                _trayHintShown = true;
                _tray?.ShowBalloonTip(2000, "CoulombMPPT",
                    "Still running — click the tray icon to reopen.", WinForms.ToolTipIcon.Info);
            }
        }
    }

    protected override void OnClosed(EventArgs e)
    {
        Dispose();
        base.OnClosed(e);
    }

    public void Dispose()
    {
        if (_tray != null) { _tray.Visible = false; _tray.Dispose(); _tray = null; }
        GC.SuppressFinalize(this);
    }

    // --- Rail nav ----

    private void OnNav(object sender, RoutedEventArgs e)
    {
        if (sender is ToggleButton t && t.Tag is string key)
            Select(key);
    }

    private void Select(string key)
    {
        _current = key;
        Services.Navigation.PopToRoot();   // pop any overlaid sub-page

        UserControl page = key switch
        {
            "dashboard" => Cached("dashboard", () => new DashboardPage()),
            "inverter"  => Cached("inverter",  () => new InverterPage()),
            "battery"   => Cached("battery",   () => new BatteryModelPage()),
            "live"      => Cached("live",      () => new LivePage()),
            "scan"      => Cached("scan",      () => new ScanPage()),
            "settings"  => Cached("settings",  () => new SettingsPage()),
            "app"       => Cached("app",       () => new AppSettingsPage()),
            "history"   => Cached("history",   () => new HistoryPage()),
            "alerts"    => Cached("alerts",    () => new AlertsPage()),
            "diag"      => Cached("diag",      () => new DiagnosticsPage()),
            "log"       => Cached("log",       () => new LogPage()),
            "api"       => Cached("api",       () => new RemotePage()),
            "about"     => Cached("about",     () => new AboutPage()),
            _           => Cached("dashboard", () => new DashboardPage()),
        };
        ContentHost.Content = page;
        UpdateRailSelection(key);
    }

    private UserControl Cached(string key, Func<UserControl> create)
    {
        if (!_pages.TryGetValue(key, out var p)) _pages[key] = p = create();
        return p;
    }

    private void UpdateRailSelection(string key)
    {
        var rail = new (ToggleButton btn, string key)[]
        {
            (NavDashboard, "dashboard"), (NavInverter, "inverter"), (NavBattery, "battery"), (NavLive, "live"), (NavScan, "scan"),
            (NavSettings, "settings"), (NavApp, "app"),
            (NavHistory, "history"), (NavAlerts, "alerts"),
            (NavDiag, "diag"), (NavLog, "log"),
            (NavApi, "api"), (NavAbout, "about"),
        };
        foreach (var (b, k) in rail) b.IsChecked = (k == key);
    }

    // --- Overlay (pushed sub-pages) ----

    private void OnOverlayChanged(UserControl? page)
    {
        if (page == null)
        {
            OverlayHost.Content = null;
            OverlayHost.Visibility = Visibility.Collapsed;
        }
        else
        {
            OverlayHost.Content = page;
            OverlayHost.Visibility = Visibility.Visible;
        }
    }

    // --- Status bar + app-bar live status ----

    private void RefreshStatus()
    {
        var ble  = ServiceLocator.Ble;
        var s    = ble.State;
        var live = ble.Live;
        var mac  = ble.CurrentMac ?? ServiceLocator.Controllers.CurrentMac;
        var name = mac != null ? ServiceLocator.Controllers.Find(mac)?.Label : null;

        // A live phone relay means the desktop has no BLE link of its own but is
        // still receiving fresh telemetry — show that rather than "Failed".
        var stateLabel = ble.RelayActive ? "Relay (phone)" : s switch
        {
            ConnectionState.Ready               => "Connected",
            ConnectionState.Connecting          => "Connecting…",
            ConnectionState.DiscoveringServices => "Discovering…",
            ConnectionState.Reconnecting        => "Reconnecting…",
            ConnectionState.Failed              => "Failed",
            ConnectionState.Scanning            => "Scanning…",
            _                                   => "Disconnected",
        };
        if (ble.DemoMode) stateLabel += " (demo)";

        StatusInline.Text   = name != null ? $"{stateLabel} · {name}" : stateLabel;
        StatusBarText.Text  = StatusInline.Text;
        CurrentMacText.Text = mac ?? "";

        HeroInline.Text = live != null
            ? $"{live.BatteryVoltage:0.0} V · {live.ChargerState.Label()}"
            : "";

        if (live != null)
        {
            var dt = DateTimeOffset.FromUnixTimeMilliseconds(live.TimestampMs).ToLocalTime();
            LastRxText.Text = $"telemetry {dt:HH:mm:ss}";
        }
        else
        {
            LastRxText.Text = "";
        }
    }
}
