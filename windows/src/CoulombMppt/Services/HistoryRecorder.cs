using CoulombMppt.Ble;
using CoulombMppt.Data;

namespace CoulombMppt.Services;

// Owns the "write a downsampled live-sample row every HistoryEverySec seconds"
// background loop for the currently-connected controller. Single-controller
// model on Windows, so one loop is enough (the Android client runs one per
// paired unit). Ported from the Android HistoryRecorder.
//
// Kept separate from MpptClient so the BLE layer stays free of storage
// concerns, and so recording can be toggled (AppSettings.RecordHistory)
// without touching the link.
public sealed class HistoryRecorder : IDisposable
{
    private readonly MpptClient   _client;
    private readonly HistoryStore _store;
    private readonly AppSettings  _settings;

    private CancellationTokenSource? _cts;
    private Task? _task;
    private readonly object _gate = new();

    public HistoryRecorder(MpptClient client, HistoryStore store, AppSettings settings)
    {
        _client   = client;
        _store    = store;
        _settings = settings;
    }

    public void Start()
    {
        lock (_gate)
        {
            if (_task is { IsCompleted: false }) return;
            _cts = new CancellationTokenSource();
            var ct = _cts.Token;
            _task = Task.Run(() => Loop(ct), ct);
        }
        Log.I("history", "recorder started");
    }

    public void Stop()
    {
        lock (_gate)
        {
            try { _cts?.Cancel(); } catch { }
            _cts?.Dispose();
            _cts = null;
            _task = null;
        }
    }

    private async Task Loop(CancellationToken ct)
    {
        // Housekeeping on start so a long-idle app trims itself before charts query.
        try { _store.PruneAll(); } catch { }

        while (!ct.IsCancellationRequested)
        {
            try
            {
                if (_settings.RecordHistory &&
                    _client.State == ConnectionState.Ready &&
                    _client.Live is { } live &&
                    _client.CurrentMac is { } mac)
                {
                    double soc = _client.Settings?.ComputeSoc(live.BatteryVoltage) ?? live.SocEstimate;
                    _store.Append(mac, new LiveSample(
                        TimestampMs:      live.TimestampMs,
                        BatteryVoltage:   live.BatteryVoltage,
                        ChargeCurrent:    live.ChargeCurrent,
                        DischargeCurrent: live.DischargeCurrent,
                        PvWatts:          live.ApproxPvWatts,
                        LoadWatts:        live.LoadWatts,
                        TemperatureC:     live.TemperatureC,
                        SocPercent:       soc));
                }
            }
            catch (Exception ex) { Log.W("history", $"sample failed: {ex.Message}"); }

            int everySec = Math.Clamp(_settings.HistoryEverySec, 5, 3600);
            try { await Task.Delay(everySec * 1000, ct).ConfigureAwait(false); }
            catch (OperationCanceledException) { break; }
        }
    }

    public void Dispose() => Stop();
}
