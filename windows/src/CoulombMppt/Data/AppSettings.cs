using System.IO;
using System.Text.Json;

namespace CoulombMppt.Data;

// App-wide preferences (not per-controller). JSON blob in %APPDATA%. Mirrors
// the heater client's AppSettings pattern.
public sealed class AppSettings
{
    private static readonly JsonSerializerOptions s_jsonOpts = new() { WriteIndented = true };

    private readonly string _path;
    private readonly object _lock = new();
    private State _state = new();

    private sealed class State
    {
        public bool   AutoReconnect      { get; set; } = true;
        public int    ApiPort            { get; set; } = 8800;
        public bool   ApiAutoStart       { get; set; }
        public bool   UpnpEnabled        { get; set; }
        public bool   RecordHistory      { get; set; } = true;
        public int    HistoryEverySec    { get; set; } = 30;   // sample cadence
        public bool   AlertsEnabled      { get; set; } = true;

        // --- Battery-model observer (self-calibrating EA SUN / bus-load inference) ---
        public bool   BatteryModelEnabled     { get; set; } = true;
        public int    QuietWindowStartHour    { get; set; } = 22;   // overnight baseload window
        public int    QuietWindowEndHour      { get; set; } = 6;
        public int    ModelPersistEverySec    { get; set; } = 300;  // rebuild + save cadence
        public double RestDvDtThresholdMv     { get; set; } = 5.0;  // mV/s "voltage flat" gate
        public int    RestSettleSec           { get; set; } = 60;   // flat for this long ⇒ rest
        public double RTransientMinDeltaA     { get; set; } = 1.0;  // min PV step to fit R
        public int    RTransientMaxStepMs     { get; set; } = 2000; // step must be this fast
        public int    SocBinWidthPct          { get; set; } = 10;
        public int    TempBinWidthC           { get; set; } = 10;
        public double MinConfidenceForLivePct { get; set; } = 40.0; // gate for live inference
    }

    public AppSettings()
    {
        var dir = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "CoulombMppt");
        Directory.CreateDirectory(dir);
        _path = Path.Combine(dir, "settings.json");
        Load();
    }

    public event Action? Changed;

    public bool AutoReconnect   { get { lock (_lock) return _state.AutoReconnect; } }
    public int  ApiPort         { get { lock (_lock) return _state.ApiPort; } }
    public bool ApiAutoStart    { get { lock (_lock) return _state.ApiAutoStart; } }
    public bool UpnpEnabled     { get { lock (_lock) return _state.UpnpEnabled; } }
    public bool RecordHistory   { get { lock (_lock) return _state.RecordHistory; } }
    public int  HistoryEverySec { get { lock (_lock) return _state.HistoryEverySec; } }
    public bool AlertsEnabled   { get { lock (_lock) return _state.AlertsEnabled; } }

    public bool   BatteryModelEnabled     { get { lock (_lock) return _state.BatteryModelEnabled; } }
    public int    QuietWindowStartHour    { get { lock (_lock) return _state.QuietWindowStartHour; } }
    public int    QuietWindowEndHour      { get { lock (_lock) return _state.QuietWindowEndHour; } }
    public int    ModelPersistEverySec    { get { lock (_lock) return _state.ModelPersistEverySec; } }
    public double RestDvDtThresholdMv     { get { lock (_lock) return _state.RestDvDtThresholdMv; } }
    public int    RestSettleSec           { get { lock (_lock) return _state.RestSettleSec; } }
    public double RTransientMinDeltaA     { get { lock (_lock) return _state.RTransientMinDeltaA; } }
    public int    RTransientMaxStepMs     { get { lock (_lock) return _state.RTransientMaxStepMs; } }
    public int    SocBinWidthPct          { get { lock (_lock) return _state.SocBinWidthPct; } }
    public int    TempBinWidthC           { get { lock (_lock) return _state.TempBinWidthC; } }
    public double MinConfidenceForLivePct { get { lock (_lock) return _state.MinConfidenceForLivePct; } }

    public void SetAutoReconnect(bool v)  => Mutate(s => s.AutoReconnect = v);
    public void SetApiPort(int v)         => Mutate(s => s.ApiPort = Math.Clamp(v, 1, 65535));
    public void SetApiAutoStart(bool v)   => Mutate(s => s.ApiAutoStart = v);
    public void SetUpnpEnabled(bool v)    => Mutate(s => s.UpnpEnabled = v);
    public void SetRecordHistory(bool v)  => Mutate(s => s.RecordHistory = v);
    public void SetHistoryEverySec(int v) => Mutate(s => s.HistoryEverySec = Math.Clamp(v, 5, 3600));
    public void SetAlertsEnabled(bool v)  => Mutate(s => s.AlertsEnabled = v);

    public void SetBatteryModelEnabled(bool v)    => Mutate(s => s.BatteryModelEnabled = v);
    public void SetQuietWindowStartHour(int v)    => Mutate(s => s.QuietWindowStartHour = Math.Clamp(v, 0, 23));
    public void SetQuietWindowEndHour(int v)      => Mutate(s => s.QuietWindowEndHour = Math.Clamp(v, 0, 23));
    public void SetModelPersistEverySec(int v)    => Mutate(s => s.ModelPersistEverySec = Math.Clamp(v, 30, 3600));
    public void SetRestDvDtThresholdMv(double v)  => Mutate(s => s.RestDvDtThresholdMv = Math.Clamp(v, 0.1, 100.0));
    public void SetRestSettleSec(int v)           => Mutate(s => s.RestSettleSec = Math.Clamp(v, 10, 1800));
    public void SetRTransientMinDeltaA(double v)  => Mutate(s => s.RTransientMinDeltaA = Math.Clamp(v, 0.1, 50.0));
    public void SetRTransientMaxStepMs(int v)     => Mutate(s => s.RTransientMaxStepMs = Math.Clamp(v, 200, 30000));
    public void SetSocBinWidthPct(int v)          => Mutate(s => s.SocBinWidthPct = Math.Clamp(v, 2, 50));
    public void SetTempBinWidthC(int v)           => Mutate(s => s.TempBinWidthC = Math.Clamp(v, 2, 50));
    public void SetMinConfidenceForLivePct(double v) => Mutate(s => s.MinConfidenceForLivePct = Math.Clamp(v, 0.0, 100.0));

    private void Mutate(Action<State> fn)
    {
        lock (_lock) { fn(_state); Save(); }
        Changed?.Invoke();
    }

    private void Load()
    {
        if (!File.Exists(_path)) return;
        try
        {
            var s = JsonSerializer.Deserialize<State>(File.ReadAllText(_path));
            if (s != null) _state = s;
        }
        catch { }
    }

    private void Save()
    {
        try
        {
            File.WriteAllText(_path,
                JsonSerializer.Serialize(_state, s_jsonOpts));
        }
        catch { }
    }
}
