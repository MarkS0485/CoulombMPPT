package app.coulombmppt.di

import android.content.Context
import java.util.concurrent.ConcurrentHashMap
import app.coulombmppt.data.ble.BleScanner
import app.coulombmppt.data.history.AlertEngine
import app.coulombmppt.data.history.HistoryDb
import app.coulombmppt.data.history.HistoryRecorder
import app.coulombmppt.data.log.AppLogger
import app.coulombmppt.data.remote.HistorySync
import app.coulombmppt.data.remote.HybridLiveRelay
import app.coulombmppt.data.remote.RemotePairingStore
import app.coulombmppt.data.model.DeviceType
import app.coulombmppt.data.model.PairedController
import app.coulombmppt.data.repo.MpptRepository
import app.coulombmppt.data.source.BleMpptSource
import app.coulombmppt.data.source.DeviceProfiles
import app.coulombmppt.data.source.FakeMpptSource
import app.coulombmppt.data.source.ModbusBleSource
import app.coulombmppt.data.source.MpptSource
import app.coulombmppt.data.source.RemoteApiMpptSource
import app.coulombmppt.data.source.VictronBleSource
import app.coulombmppt.data.store.SettingsStore

// Process-wide singletons, hand-wired (no Hilt / Koin) so the dependency
// graph reads top-to-bottom in one file. Same pattern as the sister
// coulombmonitor app.
//
// Multi-controller model: we keep one MpptRepository *per paired controller
// id*, plus one shared fakeRepo for demo mode. `repositoryFor(controllerId)`
// is the new way in; the old `repository(useFake)` overload is preserved
// for screens that still operate on the currently-selected controller (the
// Info screen, the Controller Settings editor, etc.).
object ServiceLocator {
    lateinit var appContext: Context
        private set
    lateinit var settings: SettingsStore
        private set
    lateinit var scanner: BleScanner
        private set
    lateinit var historyDb: HistoryDb
        private set
    lateinit var historyRecorder: HistoryRecorder
        private set
    lateinit var alertEngine: AlertEngine
        private set
    lateinit var remotePairing: RemotePairingStore
        private set
    lateinit var historySync: HistorySync
        private set
    val hybridRelay: HybridLiveRelay by lazy { HybridLiveRelay(remotePairing) }

    private val realRepos = ConcurrentHashMap<String, MpptRepository>()
    private val fakeRepo by lazy { MpptRepository(FakeMpptSource()) }

    // Active connection mode, chosen on the launch screen. When true, real
    // repositories are backed by the PC's API instead of local BLE. Read when a
    // repository is first created for a controller.
    @Volatile var remoteMode: Boolean = false
        private set

    /** Set the mode flag only. Safe to call at process start, before any
     *  repository (and therefore any BLE/HTTP connection) has been created. */
    fun setRemoteModeFlag(remote: Boolean) { remoteMode = remote }

    /** Switch the active source kind, tearing down any existing real
     *  repositories first so their sources disconnect cleanly — critically,
     *  this stops the BLE link when moving to remote mode (clearing the map
     *  alone would leak the radio connection). The next repositoryFor()
     *  rebuilds with the chosen source. */
    suspend fun applyConnectionMode(remote: Boolean) {
        // Flip the flag first so any repository created during teardown already
        // uses the new source kind, then stop and drop the stale ones.
        remoteMode = remote
        val repos = realRepos.values.toList()
        realRepos.clear()
        for (r in repos) runCatching { r.stop() }
    }

    /** Repository for a specific paired controller, by id only. Created lazily.
     *  Use the [PairedController] overload where possible — it's the one that
     *  picks the right driver per device type. This id-only form falls back to
     *  the default BLE/remote source and is meant for call sites that operate on
     *  an already-created repo (the typed overload runs first at pair/startup). */
    fun repositoryFor(controllerId: String): MpptRepository =
        realRepos.getOrPut(controllerId) {
            MpptRepository(if (remoteMode) RemoteApiMpptSource(remotePairing) else BleMpptSource(appContext))
        }

    /** Repository for a paired controller, choosing the driver by its
     *  [DeviceType] (Victron Instant Readout vs the generic Modbus controller),
     *  unless remote mode routes everything through the PC's API. */
    fun repositoryFor(controller: PairedController): MpptRepository =
        realRepos.getOrPut(controller.id) { MpptRepository(sourceFor(controller)) }

    private fun sourceFor(c: PairedController): MpptSource = when {
        remoteMode -> RemoteApiMpptSource(remotePairing)
        c.deviceType == DeviceType.VictronInstantReadout -> VictronBleSource(appContext, c.victronKeyHex)
        c.deviceType == DeviceType.Renogy -> ModbusBleSource(appContext, DeviceProfiles.RENOGY)
        c.deviceType == DeviceType.Epever -> ModbusBleSource(appContext, DeviceProfiles.EPEVER)
        else -> BleMpptSource(appContext)
    }

    /** Demo-mode repository — single shared instance across the app. */
    fun fakeRepository(): MpptRepository = fakeRepo

    /** Resolve the repository implied by a settings snapshot. Returns the
     *  fake repo when demo mode is on, the selected controller's repo when
     *  one is paired, or the fake repo as a safe fallback when neither
     *  applies (avoids null-handling on call sites). */
    fun repositoryFor(settings: app.coulombmppt.data.store.CoulombMpptSettings): MpptRepository {
        if (settings.useFakeSource) return fakeRepo
        val ctrl = settings.selected ?: return fakeRepo
        return repositoryFor(ctrl)
    }

    /** Tear down and forget the repository for a controller — used after
     *  unpair. Stops it first (closing the BLE link and cancelling its
     *  reconnect loop) *before* dropping the reference: removing it from the
     *  map alone would orphan a still-running source that keeps the radio open
     *  and keeps reconnecting to the now-deleted controller — which is what
     *  forced the "delete the MPPT and add it back" recovery dance. Safe to
     *  call more than once; stop() is idempotent. */
    suspend fun removeRepository(controllerId: String) {
        realRepos.remove(controllerId)?.let { runCatching { it.stop() } }
    }

    fun init(ctx: Context) {
        appContext = ctx.applicationContext
        AppLogger.init(appContext)
        settings        = SettingsStore(appContext)
        scanner         = BleScanner(appContext)
        historyDb       = HistoryDb.get(appContext)
        historyRecorder = HistoryRecorder(historyDb.liveSampleDao())
        alertEngine     = AlertEngine(appContext, settings, historyDb.alertsDao())
        remotePairing   = RemotePairingStore(appContext)
        historySync     = HistorySync(historyDb.liveSampleDao(), remotePairing)
        AppLogger.i("ServiceLocator", "init complete")
    }
}
