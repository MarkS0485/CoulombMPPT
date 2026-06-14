package app.coulombmppt.data.source

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import app.coulombmppt.data.ble.NusTransport
import app.coulombmppt.data.log.AppLogger
import app.coulombmppt.data.model.ChargerState
import app.coulombmppt.data.model.MpptLive
import app.coulombmppt.data.model.MpptSettings
import app.coulombmppt.data.modbus.ModbusFrame
import app.coulombmppt.data.modbus.MpptProtocol
import kotlin.coroutines.coroutineContext

// Real source. Drives the NusTransport, owns the polling loop, decodes
// Modbus responses into MpptLive/MpptSettings.
class BleMpptSource(context: Context) : MpptSource {

    private val transport = NusTransport(context.applicationContext)
    private val scope     = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _conn     = MutableStateFlow(MpptSource.Connection.Disconnected)
    private val _live     = MutableSharedFlow<MpptLive>(replay = 1)
    private val _settings = MutableStateFlow<MpptSettings?>(null)
    private val _diag     = MutableSharedFlow<MpptSource.Diag>(
        replay = 0, extraBufferCapacity = 32,
    )

    // Channel used to deliver one-shot fn=0x03 reads (settings polls) so the
    // continuous live decode loop doesn't accidentally eat them.
    private val oneshot = Channel<ModbusFrame.Response>(capacity = Channel.RENDEZVOUS)

    // Signal fired every time a live frame is successfully decoded. The
    // polling loop awaits on this so the next request only fires AFTER the
    // previous response has been processed.
    private val liveResponseSignal = Channel<Unit>(capacity = Channel.CONFLATED)

    override val connection  = _conn.asStateFlow()
    override val live        = _live.asSharedFlow()
    override val settings    = _settings.asStateFlow()
    override val diagnostics = _diag.asSharedFlow()

    private var pollJob:       Job? = null
    private var readerJob:     Job? = null
    private var stateBridge:   Job? = null
    private var reconnectJob:  Job? = null
    private var lastMac: String? = null
    @Volatile private var userStopped = false

    override suspend fun start(macAddress: String) {
        AppLogger.i("BleMpptSource", "start($macAddress)")
        userStopped = false
        lastMac = macAddress
        beginStateBridge()                          // forwards transport disconnects → _conn

        _conn.value = MpptSource.Connection.Connecting
        val result = transport.connect(macAddress)
        if (result.isFailure) {
            // Don't re-throw — the caller is `scope.launch { ... }` and an
            // unhandled exception kills the process. The reconnect loop
            // below already handles "controller momentarily out of range"
            // gracefully with backoff, so a clean return is sufficient.
            AppLogger.w("BleMpptSource", "connect failed: ${result.exceptionOrNull()?.message}")
            _conn.value = MpptSource.Connection.Disconnected
            scheduleReconnect(reason = "connect failed")
            return
        }
        _conn.value = MpptSource.Connection.Connected
        startReader()
        startPolling()
    }

    /**
     * Subscribes once to transport state changes. When the BLE peer drops
     * us, mirror that into our own _conn and tear down the polling loop
     * before scheduling a reconnect. Only installed once per BleMpptSource
     * lifetime; subsequent start() calls reuse it.
     */
    private fun beginStateBridge() {
        if (stateBridge != null) return
        stateBridge = scope.launch {
            // transport.state is a StateFlow whose initial value is
            // Disconnected — collecting it immediately replays that value
            // before we've ever connected. Without the `everConnected`
            // gate, we'd misread the replay as a drop and schedule a
            // reconnect that races the legitimate transport.connect() in
            // start(), which produced the connect-then-immediately-
            // disconnect-then-reconnect spiral seen in BLE logs.
            var everConnected = false
            transport.state.collect { t ->
                when (t) {
                    NusTransport.State.Connected -> everConnected = true
                    NusTransport.State.Disconnected -> {
                        if (everConnected && _conn.value != MpptSource.Connection.Disconnected) {
                            AppLogger.w("BleMpptSource", "transport reported disconnect")
                            _conn.value = MpptSource.Connection.Disconnected
                            pollJob?.cancel()
                            readerJob?.cancel()
                            if (!userStopped) scheduleReconnect(reason = "transport drop")
                        }
                    }
                    NusTransport.State.Connecting -> Unit
                }
            }
        }
    }

    private fun scheduleReconnect(reason: String) {
        if (userStopped) return
        val mac = lastMac ?: return
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            var backoff = 1_000L
            while (isActive && !userStopped) {
                AppLogger.i("BleMpptSource", "reconnect in ${backoff}ms — $reason")
                delay(backoff)
                if (userStopped) return@launch
                AppLogger.i("BleMpptSource", "reconnect attempt to $mac")
                _conn.value = MpptSource.Connection.Connecting
                val result = transport.connect(mac)
                if (result.isSuccess) {
                    _conn.value = MpptSource.Connection.Connected
                    AppLogger.i("BleMpptSource", "reconnect succeeded")
                    startReader()
                    startPolling()
                    return@launch
                }
                AppLogger.w("BleMpptSource", "reconnect failed: ${result.exceptionOrNull()?.message}")
                _conn.value = MpptSource.Connection.Disconnected
                backoff = (backoff * 2).coerceAtMost(30_000L)   // cap at 30 s
            }
        }
    }

    private fun startReader() {
        readerJob?.cancel()
        readerJob = scope.launch {
            transport.frames.collect { frame ->
                _diag.tryEmit(MpptSource.Diag(MpptSource.Diag.Direction.Rx, frame))
                val resp = ModbusFrame.parse(frame) ?: return@collect
                when (resp.functionCode) {
                    ModbusFrame.FN_READ -> when (resp.payload.size) {
                        18   -> oneshot.trySend(resp)            // settings read-back
                        else -> emitLive(resp)                    // anything else goes live
                    }
                    ModbusFrame.FN_WRITE -> {
                        oneshot.trySend(resp)                    // ACK of last write
                    }
                }
            }
        }
    }

    @Volatile private var liveRespCount = 0

    private fun emitLive(resp: ModbusFrame.Response) {
        val regs = resp.registers()
        if (regs.isEmpty()) return
        liveRespCount++
        // Log every response for the first 10, then every 30th so the log
        // file shows steady-state without becoming unreadable.
        if (liveRespCount <= 10 || liveRespCount % 30 == 0) {
            AppLogger.i(
                "BleMpptSource",
                "live rx #$liveRespCount  vBat=${regs.getOrElse(1) { 0 } / 10.0}V" +
                    "  iCh=${regs.getOrElse(2) { 0 } / 10.0}A" +
                    "  iDis=${regs.getOrElse(3) { 0 } / 10.0}A" +
                    "  bc=${resp.payload.size}",
            )
        }
        val vBat       = regs.getOrElse(1) { 0 } / 10.0
        val iCharge    = regs.getOrElse(2) { 0 } / 10.0
        val iDischarge = regs.getOrElse(3) { 0 } / 10.0
        val tempC      = regs.getOrElse(4) { 0 } / 100.0
        val solarRaw   = regs.getOrElse(5) { 0 }
        val workRaw    = regs.getOrElse(6) { 0 }
        val powerRaw   = regs.getOrElse(7) { 0 }
        val energyLo   = regs.getOrElse(8) { 0 }
        val energyHi   = regs.getOrElse(9) { 0 }
        val totalKwh   = (1000.0 * energyHi + energyLo) / 10.0
        val state = ChargerState.fromRegisters(
            solarStatus      = solarRaw,
            workStatus       = workRaw,
            powerStatus      = powerRaw,
            chargeCurrent    = iCharge,
            dischargeCurrent = iDischarge,
            batteryVoltage   = vBat,
        )
        _live.tryEmit(
            MpptLive(
                timestampMs       = System.currentTimeMillis(),
                batteryVoltage    = vBat,
                chargeCurrent     = iCharge,
                dischargeCurrent  = iDischarge,
                temperatureC      = tempC,
                solarStatusRaw    = solarRaw,
                workStatusRaw     = workRaw,
                powerStatusRaw    = powerRaw,
                totalAccumulatedAh = totalKwh,
                chargerState      = state,
                socEstimate       = estimateSoc(vBat),
            )
        )
        liveResponseSignal.trySend(Unit)
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            // Drain any stale signal so the loop starts clean.
            while (liveResponseSignal.tryReceive().isSuccess) { /* drop */ }
            liveRespCount = 0
            AppLogger.i("BleMpptSource", "polling start (1 Hz, response-driven)")
            sendLivePoll(pollCount = 1)

            var pollNum = 1
            var consecutiveTimeouts = 0
            while (coroutineContext.isActive) {
                // Wait for emitLive() to signal a response, or a timeout.
                val got = withTimeoutOrNull(2500) { liveResponseSignal.receive() }
                if (got == null) {
                    consecutiveTimeouts++
                    AppLogger.w(
                        "BleMpptSource",
                        "no response within 2.5s for poll #$pollNum (consecutive=$consecutiveTimeouts)",
                    )
                    // If the controller is completely silent for several
                    // polls in a row, treat the link as dead and let the
                    // state-bridge / reconnect logic recover. Often happens
                    // before the GATT supervision timer notices anything.
                    if (consecutiveTimeouts >= 4 && !userStopped) {
                        AppLogger.w("BleMpptSource", "4+ timeouts — forcing reconnect")
                        transport.close()              // triggers transport state → Disconnected
                        return@launch
                    }
                } else {
                    if (consecutiveTimeouts > 0) {
                        AppLogger.i("BleMpptSource", "responsive again after $consecutiveTimeouts timeouts")
                    }
                    consecutiveTimeouts = 0
                }
                delay(1000)
                pollNum++
                sendLivePoll(pollCount = pollNum)
            }
        }
    }

    private suspend fun sendLivePoll(pollCount: Int) {
        runCatching {
            val frame = MpptProtocol.pollLive()
            _diag.tryEmit(MpptSource.Diag(MpptSource.Diag.Direction.Tx, frame))
            transport.write(frame).onFailure {
                AppLogger.w("BleMpptSource", "poll #$pollCount write failed: ${it.message}")
            }
        }
    }

    override suspend fun stop() {
        AppLogger.i("BleMpptSource", "stop()")
        userStopped = true
        reconnectJob?.cancel();  reconnectJob = null
        pollJob?.cancel();       pollJob = null
        readerJob?.cancel();     readerJob = null
        // Also drop the transport-state watcher. It's otherwise harmless once
        // userStopped is set, but cancelling it means a stopped source (e.g.
        // after unpair) leaves no live coroutine collecting transport.state.
        // beginStateBridge() reinstalls it on a later start().
        stateBridge?.cancel();   stateBridge = null
        transport.close()
        _conn.value = MpptSource.Connection.Disconnected
    }

    override suspend fun readSettings(): Result<MpptSettings> = runCatching {
        if (_conn.value != MpptSource.Connection.Connected) {
            error("not connected")
        }
        AppLogger.i("BleMpptSource", "readSettings(): polling 0x1001")
        while (oneshot.tryReceive().isSuccess) { /* drop stale */ }
        val frame = MpptProtocol.pollSettings()
        _diag.tryEmit(MpptSource.Diag(MpptSource.Diag.Direction.Tx, frame))
        transport.write(frame).getOrThrow()
        val resp = withTimeoutOrNull(2500) {
            var got: ModbusFrame.Response? = null
            while (got == null) {
                val r = oneshot.receive()
                if (r.functionCode == ModbusFrame.FN_READ && r.payload.size == 18) got = r
            }
            got
        } ?: error("settings read timed out")
        val regs = resp.registers()
        require(regs.size >= 9) { "expected 9 settings registers, got ${regs.size}" }
        MpptSettings(
            batteryType             = regs[0],
            timerHours              = regs[1],
            timerMinutes            = regs[2],
            chargeVoltageSetpoint   = regs[3] / 10.0,
            outputMode              = regs[4],
            cutoffVoltageSetpoint   = regs[5] / 10.0,
            manualLoadOn            = regs[6] != 0,
            voltageMonitorMode      = regs[7],
            recoveryVoltageSetpoint = regs[8] / 10.0,
        ).also { _settings.value = it }
    }

    override suspend fun writeSetting(address: Int, value: Int): Result<Unit> = runCatching {
        if (_conn.value != MpptSource.Connection.Connected) {
            error("not connected")
        }
        while (oneshot.tryReceive().isSuccess) { /* drop */ }
        val frame = MpptProtocol.writeRegister(address, value)
        _diag.tryEmit(MpptSource.Diag(MpptSource.Diag.Direction.Tx, frame))
        transport.write(frame).getOrThrow()
        withTimeoutOrNull(2000) {
            while (true) {
                val r = oneshot.receive()
                if (r.functionCode == ModbusFrame.FN_WRITE) return@withTimeoutOrNull r
            }
            @Suppress("UNREACHABLE_CODE") null
        } ?: error("write ACK timed out")
        Unit
    }

    fun shutdown() {
        runCatching { scope.cancel() }
        runCatching { transport.close() }
    }
}
