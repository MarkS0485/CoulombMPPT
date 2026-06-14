package app.coulombmppt.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.coulombmppt.MainActivity
import app.coulombmppt.R
import app.coulombmppt.data.log.AppLogger
import app.coulombmppt.data.model.PairedController
import app.coulombmppt.data.remote.ConnectionMode
import app.coulombmppt.data.model.MpptLive
import app.coulombmppt.di.ServiceLocator

// Foreground service that keeps the BLE poll loop + history recorder
// alive even when the screen is off or the app is backgrounded. One
// instance handles every paired controller — it observes SettingsStore
// and starts/stops per-controller recording as the set changes.
//
// The persistent notification is mandated by Android 14+ for connected-
// device foreground services; it shows the count of controllers being
// monitored and taps through to the app's launcher activity.
class PollingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watcher: Job? = null
    private var syncJob: Job? = null
    private var startedControllerIds: Set<String> = emptySet()
    // Latest paired list, kept so the sync loop can resolve controllerId → MAC
    // (Room is keyed by our UUID, the PC's history by MAC).
    @Volatile private var pairedControllers: List<PairedController> = emptyList()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AppLogger.i(TAG, "service onCreate")
        ensureChannel()
        startInForeground(initialMonitorCount = 0)
        startSyncLoop()
    }

    // The watcher is (re)launched from onStartCommand so that a mode switch —
    // which calls PollingService.start() again — rebuilds the per-controller
    // repositories against the now-current source kind (BLE vs remote API).
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        relaunchWatcher()
        return START_STICKY  // restart if killed; the loop is idempotent
    }

    private fun relaunchWatcher() {
        watcher?.cancel()
        startedControllerIds = emptySet()   // force a clean rebuild
        watcher = scope.launch {
            // Authoritative source-kind selection: derive it from the persisted
            // connection mode BEFORE any repository (and therefore any BLE
            // connection) is created. This is what guarantees remote mode never
            // opens a Bluetooth link — even on a START_STICKY process restart.
            val mode = ServiceLocator.remotePairing.modeSnapshot()
            // Hybrid uses local BLE sources (same as LocalBluetooth) but also
            // starts the live relay to the PC.
            ServiceLocator.setRemoteModeFlag(mode == ConnectionMode.RemoteApi)

            ServiceLocator.settings.flow.collectLatest { snap ->
                if (snap.useFakeSource) {
                    // Demo mode owns its own (in-memory) repo — no
                    // recording needed, and no real BLE to keep alive.
                    stopAllRecording()
                    updateNotification(0)
                    return@collectLatest
                }
                pairedControllers = snap.controllers
                val current = snap.controllers.map { it.id }.toSet()
                val gone = startedControllerIds - current
                val added = current - startedControllerIds
                for (id in gone) {
                    ServiceLocator.historyRecorder.stop(id)
                    ServiceLocator.alertEngine.stop(id)
                    ServiceLocator.hybridRelay.stop()   // idempotent
                    // Authoritative teardown: whichever screen removed the
                    // pairing, the source for a controller that's no longer in
                    // the list must be stopped so it stops holding the radio and
                    // stops reconnecting. NonCancellable so a rapid second
                    // settings emission (collectLatest) can't abort the stop()
                    // half-way and leave the link open.
                    withContext(NonCancellable) { ServiceLocator.removeRepository(id) }
                    AppLogger.i(TAG, "stopped recording $id (unpaired)")
                }
                for (id in added) {
                    val ctrl = snap.controllers.firstOrNull { it.id == id } ?: continue
                    val repo = ServiceLocator.repositoryFor(ctrl)
                    repo.start(ctrl.mac)
                    ServiceLocator.historyRecorder.start(ctrl.id, repo)
                    ServiceLocator.alertEngine.start(ctrl.id, repo)
                    // In Hybrid mode, relay each live frame to the Windows PC.
                    // We drive the relay from the first (only) controller for now.
                    if (mode == ConnectionMode.Hybrid && added.indexOf(id) == 0) {
                        ServiceLocator.hybridRelay.start(
                            mac = ctrl.mac,
                            liveFlow = repo.latest,
                            socForFrame = { live: MpptLive -> live.socEstimate },
                        )
                    }
                    AppLogger.i(TAG, "started recording ${ctrl.id} (${ctrl.mac})")
                }
                startedControllerIds = current
                updateNotification(current.size)
                if (current.isEmpty()) {
                    AppLogger.i(TAG, "no controllers paired — stopping service")
                    stopSelf()
                }
            }
        }
    }

    // Opportunistic background gap-fill: while we're the device holding the
    // BLE link, periodically push our recorded window to the paired PC and
    // pull back anything it logged while it held the link. Best-effort — the
    // sync engine swallows network errors into a Result we just log. The
    // manual "Sync now" button on the settings screen drives the same path.
    private fun startSyncLoop() {
        syncJob = scope.launch {
            delay(SYNC_INITIAL_DELAY_MS)
            while (isActive) {
                runCatching {
                    if (ServiceLocator.remotePairing.snapshot() != null) {
                        for (id in startedControllerIds) {
                            val mac = pairedControllers.firstOrNull { it.id == id }?.mac ?: continue
                            ServiceLocator.historySync.sync(id, mac)
                        }
                    }
                }
                delay(SYNC_INTERVAL_MS)
            }
        }
    }

    override fun onDestroy() {
        AppLogger.i(TAG, "service onDestroy")
        watcher?.cancel()
        syncJob?.cancel()
        scope.cancel()
        stopAllRecording()
        super.onDestroy()
    }

    private fun stopAllRecording() {
        for (id in startedControllerIds) {
            ServiceLocator.historyRecorder.stop(id)
            ServiceLocator.alertEngine.stop(id)
        }
        startedControllerIds = emptySet()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java) ?: return
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    "MPPT monitoring",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Keeps the BLE link alive while the screen is off."
                    setShowBadge(false)
                }
                mgr.createNotificationChannel(ch)
            }
        }
    }

    private fun startInForeground(initialMonitorCount: Int) {
        val notification = buildNotification(initialMonitorCount)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(count: Int) {
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        mgr.notify(NOTIFICATION_ID, buildNotification(count))
    }

    private fun buildNotification(count: Int): Notification {
        val pendingTap = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = when (count) {
            0    -> "Ready · no controllers paired"
            1    -> "Monitoring 1 controller"
            else -> "Monitoring $count controllers"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            // Monochrome vector — renders cleanly in the status bar.
            // Tinted by the system; the brand colours show up on the
            // expanded notification's large icon if one were ever set.
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("CoulombMPPT")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(pendingTap)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val TAG = "PollingService"
        private const val CHANNEL_ID = "coulombmppt-monitoring"
        private const val NOTIFICATION_ID = 1001

        // Auto gap-fill cadence. First pass a minute in (let telemetry land),
        // then every 15 min. The PC's Merge dedups, so re-uploading overlap is
        // cheap; 15 min keeps data fresh without hammering the LAN.
        private const val SYNC_INITIAL_DELAY_MS = 60_000L
        private const val SYNC_INTERVAL_MS = 15L * 60 * 1000

        /** Idempotent start. Safe to call from any lifecycle. */
        fun start(context: Context) {
            val intent = Intent(context, PollingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PollingService::class.java))
        }
    }
}
