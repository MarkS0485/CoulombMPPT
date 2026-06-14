using System.IO;
using System.Text.Json;

namespace CoulombMppt.Data;

// Persists alert events to alerts.json in %APPDATA%\CoulombMppt. Alerts are
// low-volume (rising-edge only, with hysteresis), so unlike the history store
// we can keep the whole list in memory and rewrite the file on each mutation —
// same JSON-blob pattern as the other stores. Mirrors the Android AlertsDao.
public sealed class AlertStore
{
    public const int RetentionDays = 7;

    private static readonly JsonSerializerOptions s_jsonOpts = new() { WriteIndented = true };

    private readonly string _path;
    private readonly object _lock = new();
    private List<AlertRecord> _alerts = new();
    private long _nextId = 1;

    public AlertStore()
    {
        var dir = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "CoulombMppt");
        Directory.CreateDirectory(dir);
        _path = Path.Combine(dir, "alerts.json");
        Load();
        PruneOlderThan(DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()
                       - (long)RetentionDays * 24 * 60 * 60 * 1000);
    }

    public event Action? Changed;

    /// <summary>Insert a new alert. Returns the assigned id.</summary>
    public long Add(string mac, long tsMs, string severity, string kind,
                    double observed, double threshold, string message)
    {
        long id;
        lock (_lock)
        {
            id = _nextId++;
            _alerts.Add(new AlertRecord(id, mac, tsMs, severity, kind, observed, threshold, message));
            Save();
        }
        Changed?.Invoke();
        return id;
    }

    /// <summary>Newest-first, all controllers, newer than <paramref name="sinceMs"/>.</summary>
    public IReadOnlyList<AlertRecord> RecentSince(long sinceMs, int limit = 200)
    {
        lock (_lock)
            return _alerts.Where(a => a.TimestampMs >= sinceMs)
                          .OrderByDescending(a => a.TimestampMs)
                          .Take(limit)
                          .ToArray();
    }

    /// <summary>Undismissed alerts for one controller within the window — drives the dashboard banner.</summary>
    public IReadOnlyList<AlertRecord> ActiveFor(string mac, long sinceMs)
    {
        lock (_lock)
            return _alerts.Where(a => a.IsActive
                                   && a.TimestampMs >= sinceMs
                                   && a.ControllerMac.Equals(mac, StringComparison.OrdinalIgnoreCase))
                          .OrderByDescending(a => a.TimestampMs)
                          .ToArray();
    }

    public void Dismiss(long id)
    {
        bool changed = false;
        long now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        lock (_lock)
        {
            for (int i = 0; i < _alerts.Count; i++)
            {
                if (_alerts[i].Id == id && _alerts[i].IsActive)
                {
                    _alerts[i] = _alerts[i] with { DismissedMs = now };
                    changed = true;
                    break;
                }
            }
            if (changed) Save();
        }
        if (changed) Changed?.Invoke();
    }

    public void DismissAllFor(string mac)
    {
        bool changed = false;
        long now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        lock (_lock)
        {
            for (int i = 0; i < _alerts.Count; i++)
            {
                if (_alerts[i].IsActive &&
                    _alerts[i].ControllerMac.Equals(mac, StringComparison.OrdinalIgnoreCase))
                {
                    _alerts[i] = _alerts[i] with { DismissedMs = now };
                    changed = true;
                }
            }
            if (changed) Save();
        }
        if (changed) Changed?.Invoke();
    }

    public void Clear()
    {
        lock (_lock) { _alerts.Clear(); Save(); }
        Changed?.Invoke();
    }

    private void PruneOlderThan(long cutoffMs)
    {
        lock (_lock)
        {
            int before = _alerts.Count;
            _alerts.RemoveAll(a => a.TimestampMs < cutoffMs);
            if (_alerts.Count != before) Save();
        }
    }

    // --- Persistence ---

    private sealed class State
    {
        public long NextId { get; set; } = 1;
        public List<AlertRecord> Alerts { get; set; } = new();
    }

    private void Load()
    {
        if (!File.Exists(_path)) return;
        try
        {
            var s = JsonSerializer.Deserialize<State>(File.ReadAllText(_path));
            if (s != null)
            {
                _alerts = s.Alerts;
                _nextId = Math.Max(s.NextId, _alerts.Count == 0 ? 1 : _alerts.Max(a => a.Id) + 1);
            }
        }
        catch { }
    }

    private void Save()
    {
        try
        {
            var s = new State { NextId = _nextId, Alerts = _alerts };
            File.WriteAllText(_path,
                JsonSerializer.Serialize(s, s_jsonOpts));
        }
        catch { }
    }
}
