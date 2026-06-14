package app.coulombmppt.data.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import app.coulombmppt.data.log.AppLogger
import java.util.concurrent.atomic.AtomicReference

// Connection-oriented Nordic UART transport. Exposes:
//
//   - connect(mac): open GATT, raise MTU, enable notifications. Suspending,
//                   throws on failure.
//   - write(bytes): write a Modbus PDU to whichever NUS char accepts writes.
//   - frames:       Flow<ByteArray> of reassembled Modbus frames coming back
//                   from the device.
//
// The reassembly buffer (see BLE_PROTOCOL.md §6.4) accumulates notification
// payloads and emits a frame each time it has slave+fn+bc+payload+crc bytes
// for fn=0x03, or 8 bytes for fn=0x10. Anything unrecognised is dropped at
// the buffer's edge so a stray packet can't permanently desync us.
//
// Lifecycle / races (see the 2026-06 reconnect-spiral investigation):
// everything tied to a single BluetoothGatt lives in a [Conn] holder, and
// each gatt gets its *own* BluetoothGattCallback bound to that holder. So a
// late callback fired by a torn-down gatt can only resolve its own (already
// abandoned) deferreds — it can never complete the wrong operation on a fresh
// session, and it can never leave a stale gatt holding the radio. connect()
// also closes any prior gatt up front and on every failure path, which is
// what stops the "leaked GATT client → status 133 → device refuses new links"
// spiral that previously forced a delete-and-re-add to recover.
class NusTransport(
    private val context: Context,
    // GATT layout. Defaults are the Nordic UART controller; pass a null
    // serviceUuid to search every service for the write/notify characteristics
    // (devices like Renogy split them across two services).
    private val serviceUuid: java.util.UUID? = BleConstants.NUS_SERVICE,
    private val preferWriteUuid: java.util.UUID? = BleConstants.NUS_CHAR_RX,
    private val preferNotifyUuid: java.util.UUID? = BleConstants.NUS_CHAR_TX,
    // Modbus slave the device answers as (drives the reassembler's resync).
    private val expectedSlave: Int = 0x01,
) {

    enum class State { Disconnected, Connecting, Connected }

    private val _state = MutableStateFlow(State.Disconnected)
    val state = _state.asStateFlow()

    private val _frames = Channel<ByteArray>(capacity = Channel.BUFFERED)
    val frames: Flow<ByteArray> = _frames.receiveAsFlow()

    // Everything bound to one BluetoothGatt. A fresh instance per connect()
    // attempt; the gatt's callback closes over it, so signals are routed to
    // the right generation and stale callbacks are harmless.
    private inner class Conn {
        @Volatile var gatt: BluetoothGatt? = null
        val connected          = CompletableDeferred<Unit>()
        val servicesDiscovered = CompletableDeferred<Unit>()
        val mtuChanged         = CompletableDeferred<Int>()
        val notifyEnabled      = CompletableDeferred<Unit>()
        @Volatile var writeAck:    CompletableDeferred<Unit>? = null
        @Volatile var writeChar:   BluetoothGattCharacteristic? = null
        @Volatile var notifyChar:  BluetoothGattCharacteristic? = null
    }

    // The live connection, or null when disconnected.
    private val current = AtomicReference<Conn?>(null)

    // Serialise connect()/close() so a reconnect attempt can't interleave with
    // a teardown and leave two BluetoothGatt objects fighting over the radio.
    private val connectionMutex = Mutex()
    // BLE GATT only permits one outstanding write per characteristic. Two
    // concurrent callers of write() would overwrite each other's writeAck and
    // one would hang. Serialise the whole "set value + start write + await
    // callback" sequence behind a Mutex.
    private val writeMutex = Mutex()

    @SuppressLint("MissingPermission")
    suspend fun connect(macAddress: String): Result<Unit> = connectionMutex.withLock {
        // Always start from a clean slate. Without this, a connect() that
        // follows a half-finished attempt (service-discovery / MTU / notify
        // timeout — none of which fire STATE_DISCONNECTED) would leak the old
        // gatt, and these single-link UART bridges then refuse the new link.
        closeCurrent()

        runCatching {
            AppLogger.i("NusTransport", "connect($macAddress)")
            val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val adapter = bm.adapter ?: error("Bluetooth adapter not available")
            val device: BluetoothDevice = adapter.getRemoteDevice(macAddress)

            val conn = Conn()
            _state.value = State.Connecting
            current.set(conn)

            try {
                val callback = callbackFor(conn)
                val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                } else {
                    device.connectGatt(context, false, callback)
                }
                conn.gatt = gatt

                withTimeoutOrNull(8000) { conn.connected.await() } ?: error("connect timeout")

                // ---- Vendor app parity ----
                // The original Chinese app sleeps 1500 ms after
                // createBLEConnection before calling getBLEDeviceServices.
                // Cheap BLE-to-UART bridges (HC-08 / BT05 style modules, common
                // in these MPPT controllers) appear to need that pause to
                // finish their own internal handshake.
                AppLogger.i("NusTransport", "post-connect settle 1500ms")
                delay(1500)

                gatt.discoverServices()
                withTimeoutOrNull(8000) { conn.servicesDiscovered.await() } ?: error("discoverServices timeout")

                // MTU negotiation is required after all — without it the
                // 25-byte live response gets truncated by the default 23-byte
                // ATT MTU and the controller doesn't bother splitting (we saw
                // zero frames through when we tried removing this). The previous
                // attempt to "match the vendor app" by omitting requestMtu was
                // wrong: uni-app's BLE stack actually negotiates MTU
                // automatically under the hood on the Android versions the
                // vendor tested.
                gatt.requestMtu(BleConstants.PREFERRED_MTU)
                withTimeoutOrNull(3000) { conn.mtuChanged.await() }     // best-effort

                val (writeable, notifyable) = if (serviceUuid != null) {
                    // Single known service (our controller). Behaviour unchanged:
                    // pick characteristics by property, falling back across the
                    // two NUS chars. See BLE_PROTOCOL.md §1.1: the original app
                    // writes to 0x0003, which is non-standard.
                    val service = gatt.getService(serviceUuid)
                        ?: error("device does not expose service $serviceUuid")
                    val rx = service.getCharacteristic(preferWriteUuid)
                    val tx = service.getCharacteristic(preferNotifyUuid)
                    val w = when {
                        rx != null && rx.hasWriteProperty()  -> rx
                        tx != null && tx.hasWriteProperty()  -> tx
                        else -> error("no writeable characteristic on service")
                    }
                    val n = when {
                        tx != null && tx.hasNotifyProperty() -> tx
                        rx != null && rx.hasNotifyProperty() -> rx
                        else -> error("no notifyable characteristic on service")
                    }
                    w to n
                } else {
                    // Unknown layout: search every service. Prefer the supplied
                    // UUIDs, else the first characteristic with the right property.
                    val chars = gatt.services.flatMap { it.characteristics }
                    val w = chars.firstOrNull { it.uuid == preferWriteUuid && it.hasWriteProperty() }
                        ?: chars.firstOrNull { it.hasWriteProperty() }
                        ?: error("no writeable characteristic on device")
                    val n = chars.firstOrNull { it.uuid == preferNotifyUuid && it.hasNotifyProperty() }
                        ?: chars.firstOrNull { it.hasNotifyProperty() }
                        ?: error("no notifyable characteristic on device")
                    w to n
                }
                conn.writeChar  = writeable
                conn.notifyChar = notifyable
                AppLogger.i(
                    "NusTransport",
                    "chars: write=${writeable.uuid} notify=${notifyable.uuid}",
                )

                // Vendor app pauses 100 ms before registering its notify
                // callback. Same reason as above — the BLE bridge wants a breath.
                delay(100)

                gatt.setCharacteristicNotification(notifyable, true)
                val descriptor = notifyable.getDescriptor(BleConstants.CCCD)
                    ?: error("missing CCCD on notify characteristic")
                @Suppress("DEPRECATION") descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION") gatt.writeDescriptor(descriptor)
                withTimeoutOrNull(3000) { conn.notifyEnabled.await() } ?: error("enable-notify timeout")

                // Only declare success if a concurrent close() didn't replace
                // us mid-handshake.
                if (current.get() !== conn) error("connection superseded")
                _state.value = State.Connected
                AppLogger.i("NusTransport", "connected, notifications enabled")
            } catch (t: Throwable) {
                // Release this attempt's gatt no matter where it failed.
                teardown(conn)
                throw t
            }
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    suspend fun write(bytes: ByteArray): Result<Unit> = runCatching {
        writeMutex.withLock {
            val conn = current.get()       ?: error("not connected")
            val gatt = conn.gatt           ?: error("not connected")
            val char = conn.writeChar      ?: error("no write char")
            val ack = CompletableDeferred<Unit>()
            conn.writeAck = ack
            char.value = bytes
            char.writeType =
                if (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                else
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            if (!gatt.writeCharacteristic(char)) error("writeCharacteristic returned false")
            withTimeoutOrNull(3000) { ack.await() } ?: error("write timeout")
        }
    }

    /** Tear down the live connection (if any). Safe to call from any thread
     *  and any number of times; idempotent. */
    fun close() {
        AppLogger.i("NusTransport", "close()")
        closeCurrent()
    }

    // Unconditionally release whatever connection is current and reset state.
    @SuppressLint("MissingPermission")
    private fun closeCurrent() {
        val conn = current.getAndSet(null)
        if (conn != null) closeGatt(conn)
        // Drop any half-assembled bytes from the previous connection so the
        // next session's first response can't be parsed as a continuation of
        // stale garbage.
        synchronized(reassemblyLock) { reassembly.clear() }
        _state.value = State.Disconnected
    }

    // Release a *specific* connection. Only mutates shared state if that
    // connection is still the live one — a callback from an already-replaced
    // gatt just closes its own orphaned handle so it can't hold the radio.
    @SuppressLint("MissingPermission")
    private fun teardown(conn: Conn) {
        if (current.compareAndSet(conn, null)) {
            closeGatt(conn)
            synchronized(reassemblyLock) { reassembly.clear() }
            _state.value = State.Disconnected
        } else {
            conn.gatt?.let { runCatching { it.close() } }
        }
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt(conn: Conn) {
        conn.gatt?.let {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        conn.gatt = null
        // Unblock anything still awaiting this attempt's handshake or write.
        // gatt.close() stops callbacks firing, so without this a teardown
        // mid-handshake would leave connect()/write() parked on a dead gatt
        // until their own timeouts elapse. completeExceptionally is a no-op on
        // an already-resolved deferred.
        val closed = IllegalStateException("connection closed")
        conn.connected.completeExceptionally(closed)
        conn.servicesDiscovered.completeExceptionally(closed)
        conn.mtuChanged.completeExceptionally(closed)
        conn.notifyEnabled.completeExceptionally(closed)
        conn.writeAck?.completeExceptionally(closed)
    }

    // --- Notification reassembly ---
    //
    // `reassembly` is an ArrayDeque<Byte> — not thread-safe. Android 13+
    // dispatches BluetoothGatt callbacks from a binder thread pool, so two
    // notifications can race here on different threads. Without the lock,
    // concurrent addAll/removeFirst interleave and `ByteArray(n) {
    // reassembly.removeFirst() }` can observe a null boxed Byte mid-
    // mutation — auto-unbox via Number.byteValue() then NPEs in the GATT
    // callback. The result is every response gets dropped, polling times
    // out, and the source spirals into reconnect loops. See logcat from
    // 2026-05-24 09:36 for the symptom trail.
    private val reassemblyLock = Any()
    private val reassembly = ArrayDeque<Byte>()

    private fun onNotificationBytes(bytes: ByteArray) {
        synchronized(reassemblyLock) {
            reassembly.addAll(bytes.asList())
            while (true) {
                val frame = tryConsumeFrame() ?: return
                _frames.trySend(frame)
            }
        }
    }

    /**
     * Inspect the reassembly buffer and pull a complete Modbus frame off the
     * head if there is one. Returns null when the buffer doesn't yet hold a
     * full frame. **Caller must hold `reassemblyLock`.**
     */
    private fun tryConsumeFrame(): ByteArray? {
        if (reassembly.size < 5) return null
        val slave = reassembly[0].toInt() and 0xFF
        if (slave != expectedSlave) {
            reassembly.removeFirst()                          // resync on next byte
            return tryConsumeFrame()
        }
        val fn = reassembly[1].toInt() and 0xFF
        val total = when (fn) {
            0x03, 0x04 -> {
                val bc = reassembly[2].toInt() and 0xFF
                3 + bc + 2
            }
            0x10 -> 8                                          // slave fn addr×2 qty×2 crc×2
            else -> {
                reassembly.removeFirst()
                return tryConsumeFrame()
            }
        }
        if (reassembly.size < total) return null
        val buf = ByteArray(total) { reassembly.removeFirst() }
        return buf
    }

    // A fresh callback per connection attempt, bound to that attempt's [Conn].
    // Routing signals through `conn` (rather than shared fields) is what makes
    // a late callback from a superseded gatt harmless.
    private fun callbackFor(conn: Conn) = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            AppLogger.i("NusTransport", "onConnectionStateChange status=$status newState=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> conn.connected.complete(Unit)
                BluetoothProfile.STATE_DISCONNECTED -> {
                    conn.connected.completeExceptionally(
                        IllegalStateException("disconnected, status=$status"),
                    )
                    // Closes this gatt; only touches shared state if `conn` is
                    // still the live connection.
                    teardown(conn)
                }
            }
        }
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) conn.servicesDiscovered.complete(Unit)
            else conn.servicesDiscovered.completeExceptionally(IllegalStateException("svc disc status=$status"))
        }
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            AppLogger.i("NusTransport", "MTU negotiated = $mtu (status=$status)")
            conn.mtuChanged.complete(mtu)
        }
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid == BleConstants.CCCD) conn.notifyEnabled.complete(Unit)
        }
        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            val ack = conn.writeAck
            if (status == BluetoothGatt.GATT_SUCCESS) ack?.complete(Unit)
            else ack?.completeExceptionally(IllegalStateException("write status=$status"))
        }
        @Deprecated("Pre-Tiramisu; replaced by the byte[] overload below on T+.")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            onNotificationBytes(characteristic.value ?: return)
        }
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            onNotificationBytes(value)
        }
    }

    private fun BluetoothGattCharacteristic.hasWriteProperty(): Boolean =
        properties and (BluetoothGattCharacteristic.PROPERTY_WRITE
            or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0

    private fun BluetoothGattCharacteristic.hasNotifyProperty(): Boolean =
        properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
}
