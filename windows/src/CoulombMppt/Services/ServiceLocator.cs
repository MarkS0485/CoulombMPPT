using CoulombMppt.Api;
using CoulombMppt.Ble;
using CoulombMppt.Data;

namespace CoulombMppt.Services;

// Manual composition root — no DI container, matching the heater client. Stores
// are constructed first (they're pure data), then the BLE client, then the
// controllers/services that depend on them. Everything is reachable as a static
// singleton so the API route table and the WPF view-models can self-resolve.
public static class ServiceLocator
{
    public static AppSettings     Settings    { get; private set; } = null!;
    public static ControllerStore Controllers { get; private set; } = null!;
    public static HistoryStore    History     { get; private set; } = null!;
    public static AlertStore      Alerts      { get; private set; } = null!;
    public static MpptClient        Ble          { get; private set; } = null!;
    public static BatteryModelStore BatteryModels { get; private set; } = null!;
    public static HistoryRecorder   Recorder     { get; private set; } = null!;
    public static AlertEngine       AlertEngine  { get; private set; } = null!;
    public static BatteryModelLearner ModelLearner { get; private set; } = null!;
    public static BatteryObserver   Observer     { get; private set; } = null!;
    public static ApiServer       Api         { get; private set; } = null!;
    public static UpnpForwarder   Upnp        { get; private set; } = null!;

    private static bool _initialised;

    public static void Init()
    {
        if (_initialised) return;

        // Pure-data stores first.
        Settings      = new AppSettings();
        Controllers   = new ControllerStore();
        History       = new HistoryStore();
        Alerts        = new AlertStore();
        BatteryModels = new BatteryModelStore();

        // BLE link. Mirror the persisted auto-reconnect preference and keep it
        // synced if the user flips it at runtime.
        Ble = new MpptClient { AutoReconnect = Settings.AutoReconnect };
        Settings.Changed += () => Ble.AutoReconnect = Settings.AutoReconnect;

        // Background services hanging off the live stream.
        Recorder     = new HistoryRecorder(Ble, History, Settings);
        AlertEngine  = new AlertEngine(Ble, Controllers, Settings, Alerts);
        ModelLearner = new BatteryModelLearner(Ble, Controllers, History, BatteryModels, Settings);
        Observer     = new BatteryObserver(Ble, Controllers, BatteryModels, Settings);

        // Remote control surface.
        Api  = new ApiServer();
        Upnp = new UpnpForwarder();

        Recorder.Start();
        AlertEngine.Start();
        ModelLearner.Start();
        Observer.Start();

        // Optional auto-start of the remote API (and UPnP) honouring saved prefs.
        // Fire-and-forget: a failed bind must not block app startup, and the
        // Remote page surfaces the running state either way.
        if (Settings.ApiAutoStart)
        {
            _ = StartApiAsync();
        }

        // Reconnect to the last-used controller on launch when the user wants it.
        if (Settings.AutoReconnect && !string.IsNullOrEmpty(Controllers.CurrentMac))
        {
            _ = Ble.ConnectAsync(Controllers.CurrentMac!);
        }

        _initialised = true;
    }

    // Starts the API server on the configured port and, if enabled, opens a UPnP
    // mapping for it. Surfaced as a helper so the Remote page can call the same
    // path as the auto-start branch above.
    public static async Task StartApiAsync()
    {
        try
        {
            await Api.StartAsync(Settings.ApiPort).ConfigureAwait(false);
            if (Settings.UpnpEnabled) Upnp.Start(Settings.ApiPort);
        }
        catch (Exception ex)
        {
            Log.E("api", $"auto-start failed: {ex.Message}");
        }
    }

    public static async Task StopApiAsync()
    {
        Upnp.Stop();
        await Api.StopAsync().ConfigureAwait(false);
    }

    public static void Dispose()
    {
        if (!_initialised) return;
        _ = Api.StopAsync();
        Upnp.Stop();
        Recorder.Dispose();
        AlertEngine.Dispose();
        ModelLearner.Dispose();
        Observer.Dispose();
        _ = Ble.DisposeAsync().AsTask();
    }
}
