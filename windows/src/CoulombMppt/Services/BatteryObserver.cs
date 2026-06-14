using CoulombMppt.Ble;
using CoulombMppt.Data;

namespace CoulombMppt.Services;

// Live inference surface for the EA SUN / bus-load estimate. Consumes the model
// learned by BatteryModelLearner and reads the inverter draw straight off the
// voltage sag (TheveninMath.InferBusLoadA) instead of differentiating SoC over
// a 30 s window. Holds a running SoC and keeps the old EnergyComputer estimate
// alongside as a labelled cross-check.
//
// Degrades gracefully: when there is no model, the model is still learning, or
// confidence is below AppSettings.MinConfidenceForLivePct, IsLearning is true
// and BusLoad falls back to the legacy EnergyComputer path so the UI is no
// worse than before.
public sealed class BatteryObserver : IDisposable
{
    private readonly MpptClient        _client;
    private readonly ControllerStore   _controllers;
    private readonly BatteryModelStore  _models;
    private readonly AppSettings        _settings;

    // Rolling 3-minute window for the legacy cross-check (mirrors InverterViewModel).
    private readonly List<LiveSample> _recent = new();
    private const long LiveWindowMs = 3 * 60_000L;

    private readonly object _lock = new();
    private string? _modelMac;
    private BatteryModelRecord? _model;
    private long _modelLoadedTick;
    private const long ModelCacheMs = 30_000L;   // re-read so learner saves are picked up
    private bool _running;

    /// <summary>One inference snapshot. BusLoadA is positive when the bus is
    /// drawing (inverter + house) and negative when net-charging the bank.</summary>
    public sealed record InverterEstimate(
        double  BusLoadA,
        double  BusLoadW,
        double  Soc,
        double  ConfidencePct,
        bool    IsLearning,
        double? CrossCheckEasunA);

    public event Action<InverterEstimate>? EstimateChanged;
    public InverterEstimate? Current { get; private set; }

    public BatteryObserver(
        MpptClient client, ControllerStore controllers, BatteryModelStore models, AppSettings settings)
    {
        _client      = client;
        _controllers = controllers;
        _models      = models;
        _settings    = settings;
    }

    public void Start()
    {
        if (_running) return;
        _running = true;
        _client.LiveChanged += OnLive;
    }

    public void Stop()
    {
        if (!_running) return;
        _running = false;
        _client.LiveChanged -= OnLive;
    }

    /// <summary>Drop the cached model so the next frame reloads it — call after
    /// the learner persists or the active controller changes.</summary>
    public void InvalidateModel()
    {
        lock (_lock) { _modelMac = null; _model = null; _modelLoadedTick = 0; }
    }

    private BatteryProfile? CurrentProfile(string mac) =>
        _controllers.Find(mac)?.ResolvedBatteryProfile();

    private BatteryModelRecord? ModelFor(string mac)
    {
        lock (_lock)
        {
            long now = Environment.TickCount64;
            if (_modelMac == mac && now - _modelLoadedTick < ModelCacheMs) return _model;
            _model          = _models.Load(mac);
            _modelMac       = mac;
            _modelLoadedTick = now;
            return _model;
        }
    }

    private void OnLive(MpptLive live)
    {
        var mac = _client.CurrentMac;
        if (mac == null) return;
        try { Current = Compute(mac, live); }
        catch (Exception ex) { Log.W("model", $"observer failed: {ex.Message}"); return; }
        if (Current != null) EstimateChanged?.Invoke(Current);
    }

    private InverterEstimate Compute(string mac, MpptLive live)
    {
        var profile = CurrentProfile(mac);

        // Maintain the rolling window for the legacy cross-check.
        _recent.Add(new LiveSample(
            TimestampMs:      live.TimestampMs,
            BatteryVoltage:   live.BatteryVoltage,
            ChargeCurrent:    live.ChargeCurrent,
            DischargeCurrent: live.DischargeCurrent,
            PvWatts:          live.ApproxPvWatts,
            LoadWatts:        live.LoadWatts,
            TemperatureC:     live.TemperatureC,
            SocPercent:       live.SocEstimate));
        long cutoff = live.TimestampMs - LiveWindowMs;
        _recent.RemoveAll(r => r.TimestampMs < cutoff);

        double soc = ResolveSoc(mac, live, profile);
        double? crossCheck = EnergyComputer.LiveEasunA(_recent, profile);

        var model = ModelFor(mac);
        bool usable = model != null && model.IsUsable(_settings.MinConfidenceForLivePct);

        if (usable)
        {
            double ocv  = TheveninMath.InterpOcv(model!.Ocv, soc);
            double? busA = TheveninMath.InferBusLoadA(
                ocv, live.BatteryVoltage, model.R.RGlobalOhms,
                live.ChargeCurrent, live.DischargeCurrent);
            if (busA is { } a)
            {
                return new InverterEstimate(
                    BusLoadA: a, BusLoadW: a * live.BatteryVoltage, Soc: soc,
                    ConfidencePct: model.ConfidencePct, IsLearning: false,
                    CrossCheckEasunA: crossCheck);
            }
        }

        // Fallback: legacy estimate (net current; bus draw ≈ −net) while learning.
        double conf = model?.ConfidencePct ?? 0;
        double busFallbackA = crossCheck is { } e ? -e : 0;
        return new InverterEstimate(
            BusLoadA: busFallbackA, BusLoadW: busFallbackA * live.BatteryVoltage, Soc: soc,
            ConfidencePct: conf, IsLearning: true, CrossCheckEasunA: crossCheck);
    }

    private double ResolveSoc(string mac, MpptLive live, BatteryProfile? profile)
    {
        if (_client.Settings?.ComputeSoc(live.BatteryVoltage) is { } a) return a;
        if (_controllers.Find(mac)?.ComputeSocFromCache(live.BatteryVoltage) is { } b) return b;
        if (profile != null) return profile.SocFromVoltage(live.BatteryVoltage);
        return live.SocEstimate;
    }

    public void Dispose() => Stop();
}
