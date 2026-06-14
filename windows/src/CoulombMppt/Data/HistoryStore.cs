using System.IO;
using System.Text;
using System.Text.Json;

namespace CoulombMppt.Data;

// Append-only time-series store, one NDJSON file per controller MAC under
// %APPDATA%\CoulombMppt\history\. NDJSON (one JSON object per line) is chosen over
// a single JSON array so appends are O(1) — we never rewrite the whole file on
// the hot sampling path, only on the periodic prune.
//
// At the default 30 s cadence a controller writes ~2,880 rows/day, so a 30-day
// window is well under a megabyte — cheap to read whole for the chart, cheap to
// prune by rewrite.
public sealed class HistoryStore
{
    public const int RetentionDays = 30;

    private readonly string _dir;
    private readonly object _lock = new();
    private static readonly JsonSerializerOptions Json = new() { WriteIndented = false };

    public HistoryStore()
    {
        _dir = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "CoulombMppt", "history");
        Directory.CreateDirectory(_dir);
    }

    private string FileFor(string mac)
    {
        var safe = mac.Replace(':', '-').Replace('/', '-').Replace('\\', '-');
        return Path.Combine(_dir, safe + ".ndjson");
    }

    /// <summary>Append one sample. Best-effort — never throws into the caller.</summary>
    public void Append(string mac, LiveSample sample)
    {
        try
        {
            var line = JsonSerializer.Serialize(sample, Json);
            lock (_lock) File.AppendAllText(FileFor(mac), line + "\n");
        }
        catch { /* swallow — history is non-critical */ }
    }

    /// <summary>All samples for <paramref name="mac"/> newer than <paramref name="sinceMs"/>, oldest first.</summary>
    public IReadOnlyList<LiveSample> Query(string mac, long sinceMs)
    {
        var result = new List<LiveSample>();
        try
        {
            var path = FileFor(mac);
            if (!File.Exists(path)) return result;
            string[] lines;
            lock (_lock) lines = File.ReadAllLines(path);
            foreach (var line in lines)
            {
                if (string.IsNullOrWhiteSpace(line)) continue;
                LiveSample? s;
                try { s = JsonSerializer.Deserialize<LiveSample>(line); }
                catch { continue; }
                if (s != null && s.TimestampMs >= sinceMs) result.Add(s);
            }
        }
        catch { /* return what we have */ }
        return result;
    }

    /// <summary>
    /// Merge a batch of samples for <paramref name="mac"/> into the store,
    /// skipping any whose TimestampMs already exists, and rewriting the file
    /// time-sorted so the charts (which render in file order) stay monotonic
    /// even when a peer backfills frames out of order. Returns the count added.
    ///
    /// This is the ingest path used by the /api/v1/history/ingest endpoint so
    /// the other device (e.g. the Android app, which holds the BLE link while
    /// this PC is away) can fill the gaps in our time-series. Unlike Append it
    /// rewrites the whole file, but ingest is a periodic sync — not the hot
    /// per-frame path — and the 30-day file stays well under a megabyte.
    /// </summary>
    public int Merge(string mac, IReadOnlyList<LiveSample> samples)
    {
        if (samples == null || samples.Count == 0) return 0;
        try
        {
            lock (_lock)
            {
                var path = FileFor(mac);
                var byTs = new SortedDictionary<long, LiveSample>();

                if (File.Exists(path))
                {
                    foreach (var line in File.ReadAllLines(path))
                    {
                        if (string.IsNullOrWhiteSpace(line)) continue;
                        try
                        {
                            var s = JsonSerializer.Deserialize<LiveSample>(line);
                            if (s != null) byTs[s.TimestampMs] = s;
                        }
                        catch { /* skip unparseable lines */ }
                    }
                }

                int before = byTs.Count;
                foreach (var s in samples)
                    if (s != null && !byTs.ContainsKey(s.TimestampMs))
                        byTs[s.TimestampMs] = s;

                int added = byTs.Count - before;
                if (added > 0)
                {
                    var sb = new StringBuilder(byTs.Count * 96);
                    foreach (var s in byTs.Values)
                        sb.Append(JsonSerializer.Serialize(s, Json)).Append('\n');
                    File.WriteAllText(path, sb.ToString());
                }
                return added;
            }
        }
        catch { return 0; }
    }

    public int Count(string mac)
    {
        try
        {
            var path = FileFor(mac);
            if (!File.Exists(path)) return 0;
            lock (_lock) return File.ReadAllLines(path).Count(l => !string.IsNullOrWhiteSpace(l));
        }
        catch { return 0; }
    }

    public void Clear(string mac)
    {
        try { lock (_lock) { var p = FileFor(mac); if (File.Exists(p)) File.Delete(p); } }
        catch { }
    }

    /// <summary>Drop rows older than <paramref name="cutoffMs"/> from one file (rewrite).</summary>
    public int Prune(string mac, long cutoffMs)
    {
        try
        {
            var path = FileFor(mac);
            if (!File.Exists(path)) return 0;
            lock (_lock)
            {
                var lines = File.ReadAllLines(path);
                var kept = new List<string>(lines.Length);
                foreach (var line in lines)
                {
                    if (string.IsNullOrWhiteSpace(line)) continue;
                    try
                    {
                        var s = JsonSerializer.Deserialize<LiveSample>(line);
                        if (s != null && s.TimestampMs >= cutoffMs) kept.Add(line);
                    }
                    catch { /* drop unparseable lines */ }
                }
                int dropped = lines.Length - kept.Count;
                if (dropped > 0) File.WriteAllText(path, string.Join('\n', kept) + (kept.Count > 0 ? "\n" : ""));
                return dropped;
            }
        }
        catch { return 0; }
    }

    /// <summary>Prune every controller file to the retention window.</summary>
    public void PruneAll()
    {
        try
        {
            long cutoff = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()
                          - (long)RetentionDays * 24 * 60 * 60 * 1000;
            foreach (var f in Directory.GetFiles(_dir, "*.ndjson"))
            {
                var mac = Path.GetFileNameWithoutExtension(f);
                Prune(mac, cutoff);
            }
        }
        catch { }
    }
}
