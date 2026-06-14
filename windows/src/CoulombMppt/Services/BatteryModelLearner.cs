using CoulombMppt.Ble;
using CoulombMppt.Data;
using CoulombMppt.Data.BatteryModel;

namespace CoulombMppt.Services;

// Background service that learns the per-controller battery model (OCV curve,
// internal resistance, baseload band) from the live 1 Hz stream — mostly
// overnight, when the house is quiet — and persists it via BatteryModelStore.
// Lifecycle mirrors AlertEngine/HistoryRecorder: subscribe to LiveChanged,
// classify each frame (rest / PV-transient / quiet-night / charging), feed the
// accumulators, and persist a rebuilt BatteryModelRecord periodically.
//
// All the actual math/classification lives in the pure LearnerCore so the live
// path and the history-backfill path run identical code (and the tests drive it
// headless). This class is just the IO/threading shell: wire frames in, persist
// out, and never throw into the BLE callback.
public sealed class BatteryModelLearner : IDisposable
{
    private readonly MpptClient        _client;
    private readonly ControllerStore   _controllers;
    private readonly HistoryStore       _history;
    private readonly BatteryModelStore  _models;
    private readonly AppSettings        _settings;

    private readonly object _lock = new();
    // One core per MAC. Persist bookkeeping is tracked alongside.
    private readonly Dictionary<string, CoreEntry> _cores = new(StringComparer.OrdinalIgnoreCase);
    private bool _running;

    private sealed class CoreEntry
    {
        public required LearnerCore Core;
        public long LastPersistTick;          // Environment.TickCount64 of last save
        public long BackfillThroughMs;        // HistoryStore watermark ingested
    }

    public BatteryModelLearner(
        MpptClient client, ControllerStore controllers, HistoryStore history,
        BatteryModelStore models, AppSettings settings)
    {
        _client      = client;
        _controllers = controllers;
        _history     = history;
        _models      = models;
        _settings    = settings;
    }

    public void Start()
    {
        if (_running) return;
        _running = true;
        _client.LiveChanged += OnLive;
        Log.I("model", "learner started");

        // Retroactive backfill for the active MAC, off the BLE thread.
        var mac = _client.CurrentMac ?? _controllers.CurrentMac;
        if (!string.IsNullOrEmpty(mac))
            _ = Task.Run(() => SafeBackfill(mac!));
    }

    public void Stop()
    {
        if (!_running) return;
        _running = false;
        _client.LiveChanged -= OnLive;
        try { PersistAll(force: true); } catch (Exception ex) { Log.W("model", $"final persist failed: {ex.Message}"); }
    }

    private void OnLive(MpptLive live)
    {
        if (!_settings.BatteryModelEnabled) return;
        var mac = _client.CurrentMac;
        if (mac == null) return;
        try
        {
            var entry = GetOrLoad(mac);
            lock (_lock) entry.Core.Process(LearnerFrame.From(live));
            MaybePersist(mac, entry);
        }
        catch (Exception ex) { Log.W("model", $"onlive failed: {ex.Message}"); }
    }

    // --- core lifecycle -----------------------------------------------------

    private CoreEntry GetOrLoad(string mac)
    {
        lock (_lock)
        {
            if (_cores.TryGetValue(mac, out var e)) return e;

            var core = NewCore(mac);
            long through = 0;
            var existing = _models.Load(mac);
            if (existing != null)
            {
                try { core.Seed(existing); } catch { /* seed is best-effort */ }
                through = existing.LastBackfillThroughMs;
            }
            e = new CoreEntry { Core = core, LastPersistTick = Environment.TickCount64, BackfillThroughMs = through };
            _cores[mac] = e;
            return e;
        }
    }

    private LearnerCore NewCore(string mac)
    {
        var core = new LearnerCore(
            mac,
            socBinWidthPct:      _settings.SocBinWidthPct,
            tempBinWidthC:       _settings.TempBinWidthC,
            restDvDtThresholdMv: _settings.RestDvDtThresholdMv,
            restSettleSec:       _settings.RestSettleSec,
            rTransientMinDeltaA: _settings.RTransientMinDeltaA,
            rTransientMaxStepMs: _settings.RTransientMaxStepMs,
            quietStartHour:      _settings.QuietWindowStartHour,
            quietEndHour:        _settings.QuietWindowEndHour);
        core.CapacityAh = ResolveCapacityAh(mac);
        return core;
    }

    private double ResolveCapacityAh(string mac)
    {
        var ctrl = _controllers.Find(mac);
        if (ctrl?.ResolvedBatteryProfile()?.CapacityAh is { } ah && ah > 0) return ah;
        if (ctrl?.PackCapacityAh is { } cap && cap > 0) return cap;
        return 0;
    }

    // --- persistence --------------------------------------------------------

    private void MaybePersist(string mac, CoreEntry entry)
    {
        long everyMs = Math.Max(30, _settings.ModelPersistEverySec) * 1000L;
        if (Environment.TickCount64 - entry.LastPersistTick < everyMs) return;
        PersistOne(mac, entry, force: false);
    }

    private void PersistOne(string mac, CoreEntry entry, bool force)
    {
        BatteryModelRecord? rec = null;
        lock (_lock)
        {
            if (!force && !entry.Core.Dirty) { entry.LastPersistTick = Environment.TickCount64; return; }
            // Keep the capacity anchor current in case the profile was entered late.
            double cap = ResolveCapacityAh(mac);
            if (cap > 0) entry.Core.CapacityAh = cap;
            long now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            rec = entry.Core.BuildRecord(now, entry.BackfillThroughMs);
            entry.LastPersistTick = Environment.TickCount64;
        }
        if (rec != null)
        {
            _models.Save(mac, rec);
            Log.I("model", $"persisted {mac}: ocv={rec.Ocv.Count} R={rec.R.RGlobalOhms:F4}Ω base={rec.Baseload.SampleCount} conf={rec.ConfidencePct:F0}%");
        }
    }

    private void PersistAll(bool force)
    {
        KeyValuePair<string, CoreEntry>[] snapshot;
        lock (_lock) snapshot = _cores.ToArray();
        foreach (var kv in snapshot) PersistOne(kv.Key, kv.Value, force);
    }

    // --- history backfill ---------------------------------------------------

    private void SafeBackfill(string mac)
    {
        try { Backfill(mac); }
        catch (Exception ex) { Log.W("model", $"backfill failed for {mac}: {ex.Message}"); }
    }

    // Replay stored history through the SAME core/classifier code so rest +
    // baseload (and any luck with R) learn from before the app was running.
    // 30 s rows are coarse — rest/baseload still work, R-transients mostly won't,
    // and that's fine: live 1 Hz fills R in.
    private void Backfill(string mac)
    {
        var entry = GetOrLoad(mac);
        long watermark = entry.BackfillThroughMs;
        var rows = _history.Query(mac, watermark);
        if (rows.Count == 0) return;

        long newest = watermark;
        lock (_lock)
        {
            foreach (var s in rows)
            {
                if (s.TimestampMs <= watermark) continue;   // already ingested
                entry.Core.Process(LearnerFrame.From(s));
                if (s.TimestampMs > newest) newest = s.TimestampMs;
            }
            entry.BackfillThroughMs = newest;
        }
        Log.I("model", $"backfilled {mac}: replayed {rows.Count} rows through {newest}");
        PersistOne(mac, entry, force: true);
    }

    // --- testable seam ------------------------------------------------------
    // Exposed for the headless replay tests: drive the pure core directly with
    // explicit timestamps (no BLE link, no wall-clock dependency). Returns the
    // record the live path would have persisted.

    internal LearnerCore CoreForTest(string mac, double capacityAh)
    {
        var core = NewCore(mac);
        core.CapacityAh = capacityAh;
        return core;
    }

    public void Dispose() => Stop();
}
