using CoulombMppt.Ble;
using CoulombMppt.Data;

namespace CoulombMppt.Services;

// Watches the connected controller's live frames and raises alerts when a
// metric crosses a configured threshold. Hysteresis is enforced per alert
// kind: we insert + raise only on the rising edge and clear the "active" flag
// once the metric retreats into the safe band, so a slow recovery doesn't spam.
//
// Ported from the Android AlertEngine. The Windows build surfaces alerts
// in-app (AlertRaised / CriticalAlert events drive a dashboard banner) and to
// the log + AlertStore, rather than posting OS toasts — the desktop monitor is
// normally on-screen, and this keeps the build free of a toast dependency.
public sealed class AlertEngine : IDisposable
{
    // Thresholds — identical to the Android client.
    public const double OvHeadroomV = 0.5;   // alert this far above "full"
    public const double UvFloorV    = 0.3;   // alert this far below "empty"
    public const double HysteresisV = 0.2;   // clear once retreated by this much
    public const double SolarOcA    = 15.0;
    public const double LoadOcA     = 10.0;
    public const double HysteresisA = 0.5;

    public enum Severity { Critical, Warn }

    public sealed record AlertKind(string Name, Severity Severity, string DisplayName)
    {
        public static readonly AlertKind BatteryOverVoltage  = new("BatteryOverVoltage",  Severity.Critical, "Battery overvoltage");
        public static readonly AlertKind BatteryUnderVoltage = new("BatteryUnderVoltage", Severity.Critical, "Battery undervoltage");
        public static readonly AlertKind SolarOverCurrent    = new("SolarOverCurrent",    Severity.Warn,     "Solar overcurrent");
        public static readonly AlertKind LoadOverCurrent     = new("LoadOverCurrent",     Severity.Warn,     "Load overcurrent");
    }

    private readonly MpptClient      _client;
    private readonly ControllerStore _controllers;
    private readonly AppSettings     _settings;
    private readonly AlertStore      _store;

    // Per-controller active flags (kind name → still triggered?). In-memory:
    // on restart we re-evaluate fresh, which is the desired behaviour.
    private readonly Dictionary<string, HashSet<string>> _active = new(StringComparer.OrdinalIgnoreCase);
    private readonly object _lock = new();
    private bool _running;

    /// <summary>Raised for every new alert (both Warn and Critical).</summary>
    public event Action<AlertRecord>? AlertRaised;
    /// <summary>Raised only for Critical alerts — the dashboard surfaces these prominently.</summary>
    public event Action<AlertRecord>? CriticalAlert;

    public AlertEngine(MpptClient client, ControllerStore controllers, AppSettings settings, AlertStore store)
    {
        _client      = client;
        _controllers = controllers;
        _settings    = settings;
        _store       = store;
    }

    public void Start()
    {
        if (_running) return;
        _running = true;
        _client.LiveChanged += OnLive;
        Log.I("alerts", "engine started");
    }

    public void Stop()
    {
        if (!_running) return;
        _running = false;
        _client.LiveChanged -= OnLive;
        lock (_lock) _active.Clear();
    }

    private void OnLive(MpptLive live)
    {
        if (!_settings.AlertsEnabled) return;
        var mac = _client.CurrentMac;
        if (mac == null) return;
        try { Evaluate(mac, live); }
        catch (Exception ex) { Log.W("alerts", $"evaluate failed: {ex.Message}"); }
    }

    private void Evaluate(string mac, MpptLive live)
    {
        var ctrl = _controllers.Find(mac);
        var cs   = _client.Settings;

        // Battery overvoltage. Threshold = user "full" V (or controller
        // setpoint, or cached) + headroom. Skip entirely without a source.
        double? fullV  = ctrl?.PackUserFullV  ?? cs?.ChargeVoltageSetpoint ?? ctrl?.CachedFullV;
        double? emptyV = ctrl?.PackUserEmptyV ?? cs?.CutoffVoltageSetpoint ?? ctrl?.CachedEmptyV;

        if (fullV is { } full)
        {
            double threshold = full + OvHeadroomV;
            string src = ctrl?.PackUserFullV != null ? "user pack" : "controller setpoint";
            Check(mac, AlertKind.BatteryOverVoltage,
                observed:  live.BatteryVoltage,
                triggered: live.BatteryVoltage > threshold,
                cleared:   live.BatteryVoltage < threshold - HysteresisV,
                threshold: threshold,
                message:   $"Battery {live.BatteryVoltage:F2} V > {threshold:F2} V (full +{OvHeadroomV:F2} V over {src})",
                tsMs:      live.TimestampMs);
        }

        if (emptyV is { } empty)
        {
            double threshold = empty - UvFloorV;
            Check(mac, AlertKind.BatteryUnderVoltage,
                observed:  live.BatteryVoltage,
                triggered: live.BatteryVoltage < threshold && live.BatteryVoltage > 1.0, // ignore disconnected probe
                cleared:   live.BatteryVoltage > threshold + HysteresisV,
                threshold: threshold,
                message:   $"Battery {live.BatteryVoltage:F2} V < {threshold:F2} V (empty {empty:F2} V minus {UvFloorV:F2} V floor)",
                tsMs:      live.TimestampMs);
        }

        Check(mac, AlertKind.SolarOverCurrent,
            observed:  live.ChargeCurrent,
            triggered: live.ChargeCurrent > SolarOcA,
            cleared:   live.ChargeCurrent < SolarOcA - HysteresisA,
            threshold: SolarOcA,
            message:   $"Solar charge {live.ChargeCurrent:F2} A > {SolarOcA:F0} A",
            tsMs:      live.TimestampMs);

        Check(mac, AlertKind.LoadOverCurrent,
            observed:  live.DischargeCurrent,
            triggered: live.DischargeCurrent > LoadOcA,
            cleared:   live.DischargeCurrent < LoadOcA - HysteresisA,
            threshold: LoadOcA,
            message:   $"Load draw {live.DischargeCurrent:F2} A > {LoadOcA:F0} A",
            tsMs:      live.TimestampMs);
    }

    // Rising-edge insert + clear-on-recovery.
    private void Check(string mac, AlertKind kind, double observed, bool triggered,
                       bool cleared, double threshold, string message, long tsMs)
    {
        bool fire = false;
        lock (_lock)
        {
            if (!_active.TryGetValue(mac, out var set))
                _active[mac] = set = new HashSet<string>();
            bool wasActive = set.Contains(kind.Name);
            if (triggered && !wasActive) { set.Add(kind.Name); fire = true; }
            else if (cleared && wasActive) { set.Remove(kind.Name);
                Log.I("alerts", $"cleared {kind.DisplayName} {mac} — back inside safe band"); }
        }
        if (!fire) return;

        string sev = kind.Severity == Severity.Critical ? "CRIT" : "WARN";
        Log.W("alerts", $"ALERT [{sev}] {mac} {kind.DisplayName}: {message}");
        long id = _store.Add(mac, tsMs, sev, kind.Name, observed, threshold, message);
        var record = new AlertRecord(id, mac, tsMs, sev, kind.Name, observed, threshold, message);
        try { AlertRaised?.Invoke(record); } catch { }
        if (kind.Severity == Severity.Critical)
            try { CriticalAlert?.Invoke(record); } catch { }
    }

    public void Dispose() => Stop();
}
