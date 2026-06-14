using System.Collections.Concurrent;
using System.Globalization;
using System.IO;

namespace CoulombMppt.Services;

// File logger with per-session archiving + size-capped rolling. A Windows app
// is meant to do logging well (the whole reason this client exists), so this
// is a step up from the heater's truncate-per-session logger:
//
//   • Everything goes to %APPDATA%\CoulombMppt\logs\log.txt.
//   • At process start the previous session's log is archived to
//     session-<yyyyMMdd-HHmmss>.log; the newest MaxArchives are kept.
//   • If the live log passes MaxBytes mid-session it's rolled into an archive
//     and a fresh file is opened, so a runaway BLE loop can't fill the disk.
//   • An in-memory ring buffer + LineWritten event lets the Log viewer page
//     live-tail without re-reading the file.
public static class Log
{
    public static readonly string Dir = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
        "CoulombMppt", "logs");

    public static readonly string LogPath = Path.Combine(Dir, "log.txt");

    private const long MaxBytes    = 5 * 1024 * 1024;   // roll the live log past 5 MB
    private const int  MaxArchives = 12;                 // keep this many old sessions
    private const int  RingSize    = 500;                // recent lines held in memory

    private static readonly object Gate = new();
    private static readonly ConcurrentQueue<string> Ring = new();

    /// <summary>Raised on each line written, marshal to the UI thread yourself.</summary>
    public static event Action<string>? LineWritten;

    static Log()
    {
        try
        {
            Directory.CreateDirectory(Dir);
            ArchivePrevious();
            PruneArchives();

            var asm     = System.Reflection.Assembly.GetExecutingAssembly();
            var asmName = asm.GetName().Name ?? "CoulombMppt";
            var asmVer  = asm.GetName().Version?.ToString() ?? "?";
            var built   = File.Exists(asm.Location)
                ? File.GetLastWriteTime(asm.Location).ToString("O")
                : "?";
            File.WriteAllText(LogPath,
                $"=== {asmName} v{asmVer} (built {built}) ===\n" +
                $"[{DateTime.Now:O}] session start — log open\n");
        }
        catch { /* never let the logger throw into hot paths */ }
    }

    public static void I(string tag, string msg) => Write("I", tag, msg);
    public static void W(string tag, string msg) => Write("W", tag, msg);
    public static void E(string tag, string msg) => Write("E", tag, msg);

    /// <summary>Last <paramref name="n"/> lines from the live log file.</summary>
    public static string[] Tail(int n)
    {
        try
        {
            lock (Gate)
            {
                using var fs = new FileStream(LogPath, FileMode.Open, FileAccess.Read, FileShare.ReadWrite);
                using var sr = new StreamReader(fs);
                var all = sr.ReadToEnd().Split('\n');
                int take = Math.Min(n, all.Length);
                return all[^take..];
            }
        }
        catch { return Array.Empty<string>(); }
    }

    /// <summary>Recent lines held in memory (cheap, no file I/O).</summary>
    public static IReadOnlyList<string> Recent() => Ring.ToArray();

    public static IReadOnlyList<string> Archives()
    {
        try
        {
            return Directory.GetFiles(Dir, "session-*.log")
                .OrderByDescending(f => f)
                .ToArray();
        }
        catch { return Array.Empty<string>(); }
    }

    private static void Write(string lvl, string tag, string msg)
    {
        var line = $"[{DateTime.Now:HH:mm:ss.fff}] {lvl}/{tag}  {msg}";
        try
        {
            lock (Gate)
            {
                RollIfTooBig();
                File.AppendAllText(LogPath, line + "\n");
            }
            Ring.Enqueue(line);
            while (Ring.Count > RingSize) Ring.TryDequeue(out _);
        }
        catch { /* swallow */ }
        try { LineWritten?.Invoke(line); } catch { }
        try { System.Diagnostics.Debug.WriteLine(line); } catch { }
    }

    // --- Rolling / archiving --------------------------------------------

    private static void RollIfTooBig()
    {
        try
        {
            var fi = new FileInfo(LogPath);
            if (!fi.Exists || fi.Length < MaxBytes) return;
            var stamp = DateTime.Now.ToString("yyyyMMdd-HHmmss", CultureInfo.InvariantCulture);
            File.Move(LogPath, Path.Combine(Dir, $"session-{stamp}-rolled.log"), overwrite: true);
            PruneArchives();
        }
        catch { }
    }

    private static void ArchivePrevious()
    {
        try
        {
            if (!File.Exists(LogPath)) return;
            // Name the archive after the file's last-write time, which is the
            // end of the previous session.
            var stamp = File.GetLastWriteTime(LogPath).ToString("yyyyMMdd-HHmmss", CultureInfo.InvariantCulture);
            File.Move(LogPath, Path.Combine(Dir, $"session-{stamp}.log"), overwrite: true);
        }
        catch { }
    }

    private static void PruneArchives()
    {
        try
        {
            var old = Directory.GetFiles(Dir, "session-*.log")
                .OrderByDescending(f => f)
                .Skip(MaxArchives);
            foreach (var f in old) { try { File.Delete(f); } catch { } }
        }
        catch { }
    }
}
