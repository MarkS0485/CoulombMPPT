package app.coulombmppt.data.source

import android.content.Context
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import app.coulombmppt.data.ble.BleConstants
import app.coulombmppt.data.ble.NusTransport
import app.coulombmppt.data.log.AppLogger
import app.coulombmppt.data.model.ChargerState
import app.coulombmppt.data.model.MpptLive
import app.coulombmppt.data.model.MpptSettings
import app.coulombmppt.data.modbus.ModbusFrame
import kotlin.coroutines.coroutineContext

// How to read live telemetry from a Modbus-over-BLE device that isn't our own
// controller. Renogy and EPEver differ only in GATT layout, slave id, function
// code, which register block to read, and how to decode it — captured here so
// one source class drives both. Read-only for now (no settings write).
data class ModbusDeviceProfile(
    val slave: Int,
    val serviceUuid: UUID?,        // null → search every service for the chars
    val writeUuid: UUID?,
    val notifyUuid: UUID?,
    val function: Int,             // ModbusFrame.FN_READ (0x03) or FN_READ_IN (0x04)
    val liveAddress: Int,
    val liveQuantity: Int,
    val decode: (IntArray) -> MpptLive?,
)

// Generic Modbus-over-BLE source. Mirrors BleMpptSource's connect / poll /
// reconnect lifecycle but is parameterised by a [ModbusDeviceProfile] and only
// reads live telemetry. Used for Renogy (and SRNE rebrands) and EPEver.
class ModbusBleSource(
    context: Context,
    private val profile: ModbusDeviceProfile,
) : MpptSource {

    private val transport = NusTransport(
        context.applicationContext,
        serviceUuid = profile.serviceUuid,
        preferWriteUuid = profile.writeUuid,
        preferNotifyUuid = profile.notifyUuid,
        expectedSlave = profile.slave,
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _conn     = MutableStateFlow(MpptSource.Connection.Disconnected)
    private val _live     = MutableSharedFlow<MpptLive>(replay = 1)
    private val _settings = MutableStateFlow<MpptSettings?>(null)
    private val _diag     = MutableSharedFlow<MpptSource.Diag>(replay = 0, extraBufferCapacity = 32)

    override val connection  = _conn.asStateFlow()
    override val live        = _live.asSharedFlow()
    override val settings    = _settings.asStateFlow()
    override val diagnostics  = _diag.asSharedFlow()

    private val liveSignal = Channel<Unit>(capacity = Channel.CONFLATED)

    private var pollJob:      Job? = null
    private var readerJob:    Job? = null
    private var stateBridge:  Job? = null
    private var reconnectJob: Job? = null
    private var lastMac: String? = null
    @Volatile private var userStopped = false
    @Volatile private var liveCount = 0

    override suspend fun start(macAddress: String) {
        AppLogger.i(TAG, "start($macAddress) — slave=0x${profile.slave.toString(16)} fn=0x${profile.function.toString(16)}")
        userStopped = false
        lastMac = macAddress
        beginStateBridge()
        _conn.value = MpptSource.Connection.Connecting
        val result = transport.connect(macAddress)
        if (result.isFailure) {
            AppLogger.w(TAG, "connect failed: ${result.exceptionOrNull()?.message}")
            _conn.value = MpptSource.Connection.Disconnected
            scheduleReconnect("connect failed")
            return
        }
        _conn.value = MpptSource.Connection.Connected
        startReader()
        startPolling()
    }

    private fun beginStateBridge() {
        if (stateBridge != null) return
        stateBridge = scope.launch {
            var everConnected = false
            transport.state.collect { t ->
                when (t) {
                    NusTransport.State.Connected -> everConnected = true
                    NusTransport.State.Disconnected -> {
                        if (everConnected && _conn.value != MpptSource.Connection.Disconnected) {
                            _conn.value = MpptSource.Connection.Disconnected
                            pollJob?.cancel()
                            readerJob?.cancel()
                            if (!userStopped) scheduleReconnect("transport drop")
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
                AppLogger.i(TAG, "reconnect in ${backoff}ms — $reason")
                delay(backoff)
                if (userStopped) return@launch
                _conn.value = MpptSource.Connection.Connecting
                val result = transport.connect(mac)
                if (result.isSuccess) {
                    _conn.value = MpptSource.Connection.Connected
                    startReader()
                    startPolling()
                    return@launch
                }
                _conn.value = MpptSource.Connection.Disconnected
                backoff = (backoff * 2).coerceAtMost(30_000L)
            }
        }
    }

    private fun startReader() {
        readerJob?.cancel()
        readerJob = scope.launch {
            transport.frames.collect { frame ->
                _diag.tryEmit(MpptSource.Diag(MpptSource.Diag.Direction.Rx, frame))
                val resp = ModbusFrame.parse(frame, profile.slave) ?: return@collect
                if (resp.functionCode != profile.function) return@collect
                val live = profile.decode(resp.registers()) ?: return@collect
                liveCount++
                if (liveCount <= 10 || liveCount % 30 == 0) {
                    AppLogger.i(TAG, "live #$liveCount vBat=${live.batteryVoltage}V iCh=${live.chargeCurrent}A")
                }
                _live.tryEmit(live)
                liveSignal.trySend(Unit)
            }
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (liveSignal.tryReceive().isSuccess) { /* drain */ }
            liveCount = 0
            AppLogger.i(TAG, "polling start (response-driven)")
            sendPoll()
            var consecutiveTimeouts = 0
            while (coroutineContext.isActive) {
                val got = withTimeoutOrNull(2_500) { liveSignal.receive() }
                if (got == null) {
                    consecutiveTimeouts++
                    if (consecutiveTimeouts >= 4 && !userStopped) {
                        AppLogger.w(TAG, "4+ timeouts — forcing reconnect")
                        transport.close()
                        return@launch
                    }
                } else {
                    consecutiveTimeouts = 0
                }
                delay(1_000)
                sendPoll()
            }
        }
    }

    private suspend fun sendPoll() {
        runCatching {
            val frame = ModbusFrame.read(profile.liveAddress, profile.liveQuantity, profile.slave, profile.function)
            _diag.tryEmit(MpptSource.Diag(MpptSource.Diag.Direction.Tx, frame))
            transport.write(frame).onFailure { AppLogger.w(TAG, "poll write failed: ${it.message}") }
        }
    }

    override suspend fun stop() {
        AppLogger.i(TAG, "stop()")
        userStopped = true
        reconnectJob?.cancel(); reconnectJob = null
        pollJob?.cancel();      pollJob = null
        readerJob?.cancel();    readerJob = null
        stateBridge?.cancel();  stateBridge = null
        transport.close()
        _conn.value = MpptSource.Connection.Disconnected
    }

    override suspend fun readSettings(): Result<MpptSettings> =
        Result.failure(UnsupportedOperationException("settings read not implemented for this device"))

    override suspend fun writeSetting(address: Int, value: Int): Result<Unit> =
        Result.failure(UnsupportedOperationException("settings write not implemented for this device"))

    private companion object {
        const val TAG = "ModbusBleSource"
    }
}

// ---- Device profiles + decoders -------------------------------------------

object DeviceProfiles {

    // Renogy Rover/Wanderer/DCC (and SRNE/Rich Solar/PowMr rebrands) via BT-1/
    // BT-2. Holding registers from 0x0100; the dongle commonly answers as 0xFF.
    val RENOGY = ModbusDeviceProfile(
        slave = 0xFF,
        serviceUuid = null,
        writeUuid = BleConstants.RENOGY_CHAR_WRITE,
        notifyUuid = BleConstants.RENOGY_CHAR_NOTIFY,
        function = ModbusFrame.FN_READ,
        liveAddress = 0x0100,
        liveQuantity = 0x000A,
        decode = ::decodeRenogy,
    )

    // EPEver/EPSolar Tracer over a transparent RS485↔BLE bridge. Input
    // registers (fn 0x04) from 0x3100; standard slave id 1.
    val EPEVER = ModbusDeviceProfile(
        slave = 0x01,
        serviceUuid = null,
        writeUuid = null,
        notifyUuid = null,
        function = ModbusFrame.FN_READ_IN,
        liveAddress = 0x3100,
        liveQuantity = 0x0010,
        decode = ::decodeEpever,
    )
}

private fun signedHigh(reg: Int): Int = ((reg shr 8) and 0xFF).let { if (it >= 128) it - 256 else it }

// Renogy block at 0x0100: word0 SOC%, word1 battV ×0.1, word2 chargeI ×0.01,
// word3 hi=controller temp / lo=battery temp (signed), word4 loadV ×0.1,
// word5 loadI ×0.01, word6 loadW, word7 pvV ×0.1, word8 pvI ×0.01, word9 pvW.
private fun decodeRenogy(regs: IntArray): MpptLive? {
    if (regs.size < 10) return null
    val battV   = regs[1] / 10.0
    val chargeI = regs[2] / 100.0
    val loadI   = regs[5] / 100.0
    return MpptLive(
        timestampMs        = System.currentTimeMillis(),
        batteryVoltage     = battV,
        chargeCurrent      = chargeI,
        dischargeCurrent   = loadI,
        temperatureC       = signedHigh(regs[3]).toDouble(),
        solarStatusRaw     = 0,
        workStatusRaw      = 0,
        powerStatusRaw     = if (loadI > 0.05) 1 else 0,
        totalAccumulatedAh = 0.0,
        chargerState       = ChargerState.fromRegisters(0, 0, if (loadI > 0.05) 1 else 0, chargeI, loadI, battV),
        socEstimate        = regs[0].toDouble().coerceIn(0.0, 100.0),   // Renogy reports real SOC
    )
}

// EPEver input registers at 0x3100: word0 PV V ÷100, word4 batt V ÷100,
// word5 charge I ÷100, word12 load V ÷100, word13 load I ÷100.
private fun decodeEpever(regs: IntArray): MpptLive? {
    if (regs.size < 16) return null
    val battV   = regs[4] / 100.0
    val chargeI = regs[5] / 100.0
    val loadI   = regs[13] / 100.0
    return MpptLive(
        timestampMs        = System.currentTimeMillis(),
        batteryVoltage     = battV,
        chargeCurrent      = chargeI,
        dischargeCurrent   = loadI,
        temperatureC       = 0.0,
        solarStatusRaw     = 0,
        workStatusRaw      = 0,
        powerStatusRaw     = if (loadI > 0.05) 1 else 0,
        totalAccumulatedAh = 0.0,
        chargerState       = ChargerState.fromRegisters(0, 0, if (loadI > 0.05) 1 else 0, chargeI, loadI, battV),
        socEstimate        = estimateSoc(battV),
    )
}
