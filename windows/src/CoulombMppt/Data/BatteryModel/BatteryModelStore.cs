using System.IO;
using System.Text.Json;

namespace CoulombMppt.Data;

// Per-controller persistence for the learned battery model. One small JSON
// object per MAC under %APPDATA%\CoulombMppt\models\. Unlike HistoryStore (an
// append-only NDJSON time-series) the model is a single rolling blob, rewritten
// whole on each periodic persist — the same pattern as ControllerStore.Save().
//
// Best-effort throughout: the model is a learned convenience, never load-bearing
// for the BLE link, so every path swallows IO/parse errors rather than throwing
// into the learner's hot loop.
public sealed class BatteryModelStore
{
    private readonly string _dir;
    private readonly object _lock = new();
    private static readonly JsonSerializerOptions Json = new() { WriteIndented = false };

    public BatteryModelStore()
    {
        _dir = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "CoulombMppt", "models");
        Directory.CreateDirectory(_dir);
    }

    private string FileFor(string mac)
    {
        var safe = mac.Replace(':', '-').Replace('/', '-').Replace('\\', '-');
        return Path.Combine(_dir, safe + ".json");
    }

    /// <summary>Load the model for <paramref name="mac"/>, or null if none/unreadable.</summary>
    public BatteryModelRecord? Load(string mac)
    {
        try
        {
            var path = FileFor(mac);
            if (!File.Exists(path)) return null;
            string text;
            lock (_lock) text = File.ReadAllText(path);
            return JsonSerializer.Deserialize<BatteryModelRecord>(text);
        }
        catch { return null; }
    }

    /// <summary>Persist the model for <paramref name="mac"/>. Best-effort.</summary>
    public void Save(string mac, BatteryModelRecord model)
    {
        try
        {
            var line = JsonSerializer.Serialize(model, Json);
            lock (_lock) File.WriteAllText(FileFor(mac), line);
        }
        catch { /* swallow — model is non-critical */ }
    }

    /// <summary>Drop the learned model for <paramref name="mac"/> (e.g. on re-pair
    /// or a battery/chemistry change) so it relearns from scratch.</summary>
    public void Clear(string mac)
    {
        try { lock (_lock) { var p = FileFor(mac); if (File.Exists(p)) File.Delete(p); } }
        catch { }
    }
}
