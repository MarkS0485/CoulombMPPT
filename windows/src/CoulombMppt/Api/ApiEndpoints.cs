using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Routing;
using CoulombMppt.Ble;
using CoulombMppt.Data;
using CoulombMppt.Services;

namespace CoulombMppt.Api;

// All v1 endpoints. Thin wrappers over MpptClient / the stores. Where an action
// needs an active BLE link we ensure it before firing (idempotent connect with
// a short wait) and report back what happened. Reaches singletons via
// ServiceLocator so the route table needs no DI.
public static class ApiEndpoints
{
    public static void Map(WebApplication app)
    {
        // Open ping for pairing-test (no HMAC). Returns the cert thumbprint so
        // a client can sanity-check it matches the QR.
        app.MapGet("/api/ping", () => Results.Json(new
        {
            ok = true,
            app = "coulomb-mppt",
            cert = ServiceLocator.Api.CertSha256,
        }));

        // --- Status / live --------------------------------------------

        app.MapGet("/api/v1/status", () =>
        {
            var ble = ServiceLocator.Ble;
            var mac = ble.CurrentMac;
            long since = NowMs() - 7L * 24 * 60 * 60 * 1000;
            int activeAlerts = mac == null ? 0 : ServiceLocator.Alerts.ActiveFor(mac, since).Count;
            return Results.Json(new
            {
                state      = ble.State.ToString(),
                isReady    = ble.State == ConnectionState.Ready,
                demoMode   = ble.DemoMode,
                lastError  = ble.LastError,
                currentMac = mac,
                live       = LiveDto(ble.Live),
                settings   = SettingsDto(ble.Settings),
                activeAlerts,
            });
        });

        app.MapGet("/api/v1/live", () =>
            Results.Json(LiveDto(ServiceLocator.Ble.Live) ?? (object)new { error = "no telemetry" }));

        // --- Settings -------------------------------------------------

        app.MapGet("/api/v1/settings", () =>
            Results.Json(SettingsDto(ServiceLocator.Ble.Settings) ?? (object)new { error = "not read yet" }));

        app.MapPost("/api/v1/settings/read", () => Fire(ble => ble.ReadSettingsAsync()));

        app.MapPut("/api/v1/settings", async (SettingsBody body) =>
        {
            var ble = ServiceLocator.Ble;
            if (!await EnsureReady(ble)) return Results.Json(new { ok = false, error = ble.LastError });
            bool ok = true;
            if (body.ChargeVoltage   is { } cv) ok &= await ble.SetChargeVoltageAsync(cv);
            if (body.CutoffVoltage   is { } ov) ok &= await ble.SetCutoffVoltageAsync(ov);
            if (body.RecoveryVoltage is { } rv) ok &= await ble.SetRecoveryVoltageAsync(rv);
            if (body.BatteryType     is { } bt) ok &= await ble.SetBatteryTypeAsync(bt);
            if (body.OutputMode      is { } om) ok &= await ble.SetOutputModeAsync(om);
            if (body.ManualLoadOn    is { } ml) ok &= await ble.SetManualLoadAsync(ml);
            if (body.VoltageMonitorMode is { } vm) ok &= await ble.SetVoltageMonitorModeAsync(vm);
            if (body.TimerHours is { } th && body.TimerMinutes is { } tm) ok &= await ble.SetTimerAsync(th, tm);
            return Results.Json(new { ok, settings = SettingsDto(ble.Settings) });
        });

        app.MapPost("/api/v1/settings/register", (RegisterBody body) =>
            Fire(ble => ble.WriteRegisterAsync(body.Address, body.Value)));

        // --- Load control (convenience) -------------------------------

        app.MapPost("/api/v1/load/on",  () => Fire(ble => ble.SetManualLoadAsync(true)));
        app.MapPost("/api/v1/load/off", () => Fire(ble => ble.SetManualLoadAsync(false)));

        // --- Connection management ------------------------------------

        app.MapPost("/api/v1/connect", async (ConnectBody? body) =>
        {
            var mac = body?.Mac ?? ServiceLocator.Controllers.CurrentMac;
            if (string.IsNullOrEmpty(mac)) return Results.BadRequest(new { error = "no MAC and no current controller" });
            ServiceLocator.Controllers.SetCurrent(mac);
            _ = ServiceLocator.Ble.ConnectAsync(mac);
            return await WaitReady(12_000);
        });

        app.MapPost("/api/v1/disconnect", async () =>
        {
            await ServiceLocator.Ble.DisconnectAsync();
            return Ok();
        });

        app.MapPost("/api/v1/demo/start", () => { ServiceLocator.Ble.StartDemo(); return Ok(); });
        app.MapPost("/api/v1/demo/stop",  () => { ServiceLocator.Ble.StopDemo();  return Ok(); });

        // --- Hybrid relay (Android phone → Windows live push) ---------

        // The Android app in Hybrid mode calls this endpoint to relay each live
        // frame (~1 Hz). We inject it into MpptClient so the dashboard, history
        // recorder and all ViewModels update as if the frame came from local BLE.
        app.MapPost("/api/v1/live/push", (PushLiveBody body) =>
        {
            var chargerState = ChargerStateLogic.FromRegisters(
                body.SolarStatusRaw, body.WorkStatusRaw, body.PowerStatusRaw,
                body.ChargeCurrent, body.DischargeCurrent, body.BatteryVoltage);

            var live = new MpptLive(
                TimestampMs:        body.TimestampMs,
                BatteryVoltage:     body.BatteryVoltage,
                ChargeCurrent:      body.ChargeCurrent,
                DischargeCurrent:   body.DischargeCurrent,
                TemperatureC:       body.TemperatureC,
                SolarStatusRaw:     body.SolarStatusRaw,
                WorkStatusRaw:      body.WorkStatusRaw,
                PowerStatusRaw:     body.PowerStatusRaw,
                TotalAccumulatedAh: body.TotalAccumulatedAh,
                ChargerState:       chargerState,
                SocEstimate:        body.SocEstimate);

            // Register this MAC if not already known.
            if (!string.IsNullOrEmpty(body.Mac) &&
                ServiceLocator.Controllers.Find(body.Mac) == null)
            {
                ServiceLocator.Controllers.Pair(body.Mac, $"Android relay ({body.Mac[^5..]})");
            }

            ServiceLocator.Ble.InjectFromRelay(live);
            return Ok();
        });

        // --- History --------------------------------------------------

        app.MapGet("/api/v1/history", (string? mac, double? hours) =>
        {
            var m = mac ?? ServiceLocator.Ble.CurrentMac ?? ServiceLocator.Controllers.CurrentMac;
            if (string.IsNullOrEmpty(m)) return Results.BadRequest(new { error = "no MAC and no current controller" });
            double h = hours is { } hv && hv > 0 ? hv : 24;
            long since = NowMs() - (long)(h * 60 * 60 * 1000);
            return Results.Json(new { mac = m, hours = h, samples = ServiceLocator.History.Query(m, since) });
        });

        app.MapDelete("/api/v1/history", (string? mac) =>
        {
            var m = mac ?? ServiceLocator.Ble.CurrentMac ?? ServiceLocator.Controllers.CurrentMac;
            if (string.IsNullOrEmpty(m)) return Results.BadRequest(new { error = "no MAC and no current controller" });
            ServiceLocator.History.Clear(m);
            return Ok();
        });

        // Gap-fill ingest: a peer device (the Android app, which holds the BLE
        // link while this PC is away) uploads the frames it captured so our
        // time-series has no holes. Merge dedups by timestamp and keeps the
        // file sorted, so re-uploading overlapping windows is safe/idempotent.
        app.MapPost("/api/v1/history/ingest", (IngestBody body) =>
        {
            var m = body.Mac ?? ServiceLocator.Ble.CurrentMac ?? ServiceLocator.Controllers.CurrentMac;
            if (string.IsNullOrEmpty(m)) return Results.BadRequest(new { error = "no MAC and no current controller" });
            var samples = body.Samples ?? new List<LiveSample>();
            int added = ServiceLocator.History.Merge(m, samples);
            Log.I("api", $"history ingest mac={m} received={samples.Count} added={added}");
            return Results.Json(new { ok = true, mac = m, received = samples.Count, added, total = ServiceLocator.History.Count(m) });
        });

        // --- Alerts ---------------------------------------------------

        app.MapGet("/api/v1/alerts", (double? hours) =>
        {
            double h = hours is { } hv && hv > 0 ? hv : 168;   // default 7 days
            long since = NowMs() - (long)(h * 60 * 60 * 1000);
            return Results.Json(ServiceLocator.Alerts.RecentSince(since));
        });

        app.MapPost("/api/v1/alerts/{id:long}/dismiss", (long id) =>
        {
            ServiceLocator.Alerts.Dismiss(id);
            return Ok();
        });

        app.MapPost("/api/v1/alerts/dismiss-all", (ConnectBody? body) =>
        {
            var m = body?.Mac ?? ServiceLocator.Ble.CurrentMac ?? ServiceLocator.Controllers.CurrentMac;
            if (string.IsNullOrEmpty(m)) return Results.BadRequest(new { error = "no MAC and no current controller" });
            ServiceLocator.Alerts.DismissAllFor(m);
            return Ok();
        });

        // --- Controllers ----------------------------------------------

        app.MapGet("/api/v1/controllers", () => Results.Json(new
        {
            currentMac = ServiceLocator.Controllers.CurrentMac,
            all = ServiceLocator.Controllers.All,
        }));

        app.MapDelete("/api/v1/controllers/{mac}", (string mac) =>
        {
            ServiceLocator.Controllers.Remove(mac);
            return Ok();
        });

        app.MapPost("/api/v1/controllers/{mac}/current", (string mac) =>
        {
            ServiceLocator.Controllers.SetCurrent(mac);
            return Ok();
        });

        app.MapPost("/api/v1/controllers/{mac}/rename", (string mac, RenameBody body) =>
        {
            if (!string.IsNullOrWhiteSpace(body.Name))
                ServiceLocator.Controllers.Rename(mac, body.Name.Trim());
            return Ok();
        });

        // --- Scan -----------------------------------------------------

        app.MapPost("/api/v1/scan/start", () => { ServiceLocator.Ble.StartScan(); return Ok(); });
        app.MapPost("/api/v1/scan/stop",  () => { ServiceLocator.Ble.StopScan();  return Ok(); });
        app.MapGet ("/api/v1/scan/devices", () => Results.Json(ServiceLocator.Ble.Devices));

        // --- App settings ---------------------------------------------

        app.MapGet("/api/v1/app-settings", () => Results.Json(new
        {
            autoReconnect   = ServiceLocator.Settings.AutoReconnect,
            apiPort         = ServiceLocator.Settings.ApiPort,
            recordHistory   = ServiceLocator.Settings.RecordHistory,
            historyEverySec = ServiceLocator.Settings.HistoryEverySec,
            alertsEnabled   = ServiceLocator.Settings.AlertsEnabled,
        }));

        app.MapPut("/api/v1/app-settings", (AppSettingsBody body) =>
        {
            if (body.AutoReconnect   is { } a) ServiceLocator.Settings.SetAutoReconnect(a);
            if (body.RecordHistory   is { } b) ServiceLocator.Settings.SetRecordHistory(b);
            if (body.HistoryEverySec is { } c) ServiceLocator.Settings.SetHistoryEverySec(c);
            if (body.AlertsEnabled   is { } d) ServiceLocator.Settings.SetAlertsEnabled(d);
            return Ok();
        });

        // --- Diagnostics ----------------------------------------------

        app.MapGet("/api/v1/log/tail", (int? n) =>
        {
            try { return Results.Json(Log.Tail(n is { } v && v > 0 ? v : 200)); }
            catch (Exception ex) { return Results.Problem(ex.Message); }
        });
    }

    // --- Helpers --------------------------------------------------

    private static IResult Ok() => Results.Json(new { ok = true });

    private static long NowMs() => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();

    private static async Task<IResult> WaitReady(int timeoutMs)
    {
        var sw = System.Diagnostics.Stopwatch.StartNew();
        while (sw.ElapsedMilliseconds < timeoutMs)
        {
            var s = ServiceLocator.Ble.State;
            if (s == ConnectionState.Ready) return Results.Json(new { ok = true, ready = true });
            if (s == ConnectionState.Failed)
                return Results.Json(new { ok = false, ready = false, error = ServiceLocator.Ble.LastError });
            await Task.Delay(200);
        }
        return Results.Json(new { ok = false, ready = false, error = "timeout" });
    }

    // Ensure the link is up (idempotent connect to current controller), then
    // report whether we got there.
    private static async Task<bool> EnsureReady(MpptClient ble)
    {
        if (ble.State == ConnectionState.Ready) return true;
        var mac = ServiceLocator.Controllers.CurrentMac;
        if (string.IsNullOrEmpty(mac)) return false;
        _ = ble.ConnectAsync(mac);
        return await ble.WaitReadyAsync(12_000);
    }

    private static async Task<IResult> Fire(Func<MpptClient, Task<bool>> action)
    {
        var ble = ServiceLocator.Ble;
        if (!await EnsureReady(ble))
            return Results.Json(new { ok = false, error = string.IsNullOrEmpty(ble.LastError) ? "no current controller" : ble.LastError });
        bool ok = await action(ble);
        return Results.Json(new { ok });
    }

    // --- DTOs -----------------------------------------------------

    private static object? LiveDto(MpptLive? l) => l == null ? null : new
    {
        timestampMs       = l.TimestampMs,
        batteryVoltage    = l.BatteryVoltage,
        chargeCurrent     = l.ChargeCurrent,
        dischargeCurrent  = l.DischargeCurrent,
        temperatureC      = l.TemperatureC,
        batteryWatts      = l.BatteryWatts,
        loadWatts         = l.LoadWatts,
        approxPvWatts     = l.ApproxPvWatts,
        totalAccumulatedAh = l.TotalAccumulatedAh,
        socEstimate       = l.SocEstimate,
        chargerState      = l.ChargerState.ToString(),
        chargerLabel      = l.ChargerState.Label(),
        solarStatusRaw    = l.SolarStatusRaw,
        workStatusRaw     = l.WorkStatusRaw,
        powerStatusRaw    = l.PowerStatusRaw,
    };

    private static object? SettingsDto(MpptSettings? s) => s == null ? null : new
    {
        batteryType            = s.BatteryType,
        batteryTypeLabel       = s.BatteryTypeEnum.DisplayName(),
        timerHours             = s.TimerHours,
        timerMinutes           = s.TimerMinutes,
        chargeVoltageSetpoint  = s.ChargeVoltageSetpoint,
        outputMode             = s.OutputMode,
        outputModeLabel        = s.OutputModeEnum.DisplayName(),
        cutoffVoltageSetpoint  = s.CutoffVoltageSetpoint,
        manualLoadOn           = s.ManualLoadOn,
        voltageMonitorMode     = s.VoltageMonitorMode,
        recoveryVoltageSetpoint = s.RecoveryVoltageSetpoint,
    };

    // --- Request bodies -------------------------------------------

    public sealed record ConnectBody(string? Mac);
    public sealed record IngestBody(string? Mac, List<LiveSample>? Samples);
    public sealed record RenameBody(string Name);
    public sealed record RegisterBody(int Address, int Value);
    public sealed record SettingsBody(
        double? ChargeVoltage, double? CutoffVoltage, double? RecoveryVoltage,
        int? BatteryType, int? OutputMode, bool? ManualLoadOn,
        int? VoltageMonitorMode, int? TimerHours, int? TimerMinutes);
    public sealed record AppSettingsBody(
        bool? AutoReconnect, bool? RecordHistory, int? HistoryEverySec, bool? AlertsEnabled);

    public sealed record PushLiveBody(
        string? Mac,
        long   TimestampMs,
        double BatteryVoltage,
        double ChargeCurrent,
        double DischargeCurrent,
        double TemperatureC,
        double TotalAccumulatedAh,
        double SocEstimate,
        int    SolarStatusRaw,
        int    WorkStatusRaw,
        int    PowerStatusRaw);
}
