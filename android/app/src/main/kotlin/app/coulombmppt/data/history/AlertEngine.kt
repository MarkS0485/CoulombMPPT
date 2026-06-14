package app.coulombmppt.data.history

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import app.coulombmppt.MainActivity
import app.coulombmppt.R
import app.coulombmppt.data.log.AppLogger
import app.coulombmppt.data.model.MpptLive
import app.coulombmppt.data.model.MpptSettings
import app.coulombmppt.data.model.PairedController
import app.coulombmppt.data.repo.MpptRepository
import app.coulombmppt.data.store.SettingsStore

// Watches a controller's live frames and emits alert rows when a metric
// crosses a configured threshold. Hysteresis is enforced per (controllerId,
// kind) pair: we only insert + notify on the rising edge (crosses threshold)
// and clear the "active" flag once the metric drops back inside the safe
// band, so a slow recovery doesn't spam the user.
//
// Critical alerts (battery over/undervoltage) post a heads-up notification
// on their own channel — lithium chemistries are genuinely dangerous when
// over-volted and the user needs to know NOW.
class AlertEngine(
    private val appContext: Context,
    private val settings: SettingsStore,
    private val dao: AlertsDao,
) {

    enum class Kind(val severity: Severity, val displayName: String) {
        BatteryOverVoltage  (Severity.Critical, "Battery overvoltage"),
        BatteryUnderVoltage (Severity.Critical, "Battery undervoltage"),
        SolarOverCurrent    (Severity.Warn,     "Solar overcurrent"),
        LoadOverCurrent     (Severity.Warn,     "Load overcurrent"),
    }

    enum class Severity { Critical, Warn }

    private val errorHandler = CoroutineExceptionHandler { _, e ->
        AppLogger.e("AlertEngine", "swallowed uncaught coroutine error", e)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + errorHandler)
    private val jobs = ConcurrentHashMap<String, Job>()
    /** Per-controller active-flag map (kind → still triggered?). Lives only
     *  in memory; on process restart we re-evaluate fresh, which is the
     *  right behaviour — the user expects a current snapshot of state. */
    private val active = ConcurrentHashMap<String, MutableSet<Kind>>()

    fun start(controllerId: String, repo: MpptRepository) {
        if (jobs[controllerId]?.isActive == true) return
        ensureChannel()
        AppLogger.i(TAG, "start $controllerId")
        jobs[controllerId] = scope.launch {
            // Combine live + controller-settings + paired-controller record
            // so threshold changes (user edits the pack profile) take effect
            // on the very next sample.
            combine(repo.latest, repo.settings, settings.flow) { live, cs, s ->
                Triple(live, cs, s.controllerById(controllerId))
            }.collect { (live, cs, ctrl) ->
                if (live != null && ctrl != null) {
                    evaluate(controllerId, ctrl, live, cs)
                }
            }
        }
    }

    fun stop(controllerId: String) {
        jobs.remove(controllerId)?.cancel()
        active.remove(controllerId)
    }

    fun stopAll() {
        for (id in jobs.keys.toList()) stop(id)
    }

    private suspend fun evaluate(
        controllerId: String,
        ctrl: PairedController,
        live: MpptLive,
        cs: MpptSettings?,
    ) {
        // Battery overvoltage. Threshold = user-set "full" V (or controller
        // setpoint) + a small headroom. Without either source we skip the
        // check entirely rather than guess.
        val fullV    = ctrl.packUserFullV  ?: cs?.chargeVoltageSetpoint ?: ctrl.cachedFullV
        val emptyV   = ctrl.packUserEmptyV ?: cs?.cutoffVoltageSetpoint ?: ctrl.cachedEmptyV
        if (fullV != null) {
            val threshold = fullV + OV_HEADROOM_V
            check(
                controllerId, Kind.BatteryOverVoltage,
                observed = live.batteryVoltage,
                triggered = live.batteryVoltage > threshold,
                cleared   = live.batteryVoltage < threshold - HYSTERESIS_V,
                threshold = threshold,
                message   = "Battery %.2f V > %.2f V (full %+.2f V over %s)".format(
                    live.batteryVoltage, threshold, OV_HEADROOM_V,
                    if (ctrl.packUserFullV != null) "user pack" else "controller setpoint",
                ),
                tsMs = live.timestampMs,
            )
        }
        if (emptyV != null) {
            val threshold = emptyV - UV_FLOOR_V
            check(
                controllerId, Kind.BatteryUnderVoltage,
                observed = live.batteryVoltage,
                triggered = live.batteryVoltage < threshold && live.batteryVoltage > 1.0,  // ignore disconnected probes
                cleared   = live.batteryVoltage > threshold + HYSTERESIS_V,
                threshold = threshold,
                message   = "Battery %.2f V < %.2f V (empty %.2f V minus %.2f V floor)".format(
                    live.batteryVoltage, threshold, emptyV, UV_FLOOR_V,
                ),
                tsMs = live.timestampMs,
            )
        }
        // Fixed-default OC alerts; user could later expose these as Battery-
        // tab fields if desired.
        check(
            controllerId, Kind.SolarOverCurrent,
            observed = live.chargeCurrent,
            triggered = live.chargeCurrent > SOLAR_OC_A,
            cleared   = live.chargeCurrent < SOLAR_OC_A - HYSTERESIS_A,
            threshold = SOLAR_OC_A,
            message   = "Solar charge %.2f A > %.0f A".format(live.chargeCurrent, SOLAR_OC_A),
            tsMs = live.timestampMs,
        )
        check(
            controllerId, Kind.LoadOverCurrent,
            observed = live.dischargeCurrent,
            triggered = live.dischargeCurrent > LOAD_OC_A,
            cleared   = live.dischargeCurrent < LOAD_OC_A - HYSTERESIS_A,
            threshold = LOAD_OC_A,
            message   = "Load draw %.2f A > %.0f A".format(live.dischargeCurrent, LOAD_OC_A),
            tsMs = live.timestampMs,
        )
    }

    /** Decide whether to insert + notify based on rising-edge detection. */
    private suspend fun check(
        controllerId: String,
        kind: Kind,
        observed: Double,
        triggered: Boolean,
        cleared: Boolean,
        threshold: Double,
        message: String,
        tsMs: Long,
    ) {
        val activeSet = active.getOrPut(controllerId) { mutableSetOf() }
        val wasActive = kind in activeSet
        when {
            triggered && !wasActive -> {
                activeSet.add(kind)
                AppLogger.w(TAG, "ALERT [${kind.severity}] $controllerId ${kind.displayName}: $message")
                val rowId = runCatching {
                    dao.insert(
                        AlertRow(
                            controllerId = controllerId,
                            tsMs         = tsMs,
                            severity     = if (kind.severity == Severity.Critical) "CRIT" else "WARN",
                            kind         = kind.name,
                            observed     = observed,
                            threshold    = threshold,
                            message      = message,
                        )
                    )
                }.getOrNull()
                if (kind.severity == Severity.Critical) {
                    postNotification(kind, controllerId, message, rowId)
                }
            }
            cleared && wasActive -> {
                activeSet.remove(kind)
                AppLogger.i(TAG, "cleared ${kind.displayName} $controllerId — back inside safe band")
            }
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = appContext.getSystemService(NotificationManager::class.java) ?: return
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    "Critical battery alerts",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "Battery overvoltage / undervoltage — potentially dangerous."
                    enableLights(true)
                    enableVibration(true)
                }
                mgr.createNotificationChannel(ch)
            }
        }
    }

    private fun postNotification(kind: Kind, controllerId: String, message: String, rowId: Long?) {
        val mgr = appContext.getSystemService(NotificationManager::class.java) ?: return
        val tap = PendingIntent.getActivity(
            appContext, 0,
            Intent(appContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("⚠ ${kind.displayName}")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setColor(0xFFC8102E.toInt())          // Coulomb red
            .setColorized(true)
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()
        // Use row id as the notification id where possible so a recurring
        // alert doesn't collapse onto the previous one; fall back to a hash
        // when the DB insert failed.
        val notificationId = (rowId?.toInt() ?: (controllerId.hashCode() * 31 + kind.ordinal))
            .let { if (it < BASE_NOTIFICATION_ID) it + BASE_NOTIFICATION_ID else it }
        mgr.notify(notificationId, notification)
    }

    companion object {
        private const val TAG = "AlertEngine"
        private const val CHANNEL_ID = "coulombmppt-critical"
        private const val BASE_NOTIFICATION_ID = 2000

        const val OV_HEADROOM_V = 0.5   // alert this far above full
        const val UV_FLOOR_V    = 0.3   // alert this far below empty
        const val HYSTERESIS_V  = 0.2   // clear once retreated by this much
        const val SOLAR_OC_A    = 15.0
        const val LOAD_OC_A     = 10.0
        const val HYSTERESIS_A  = 0.5
        const val RETENTION_DAYS = 7L
    }
}
