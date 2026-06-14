package app.coulombmppt.data.ble

import java.util.UUID

// Nordic UART Service UUIDs the MPPT controller exposes (see BLE_PROTOCOL.md
// §1.1). The original Chinese app writes to 0x0003 (the notify char) instead
// of the standard 0x0002 write char — we try both at runtime.
object BleConstants {
    val NUS_SERVICE  : UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    val NUS_CHAR_RX  : UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")  // phone → device
    val NUS_CHAR_TX  : UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")  // device → phone (notify)

    // Standard Client Characteristic Configuration descriptor — used to
    // enable notifications. Same constant on every BLE device.
    val CCCD         : UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // Renogy BT-1/BT-2: Modbus tunnelled over BLE with the write and notify
    // characteristics split across two services (FFD0 / FFF0).
    val RENOGY_CHAR_WRITE : UUID = UUID.fromString("0000ffd1-0000-1000-8000-00805f9b34fb")
    val RENOGY_CHAR_NOTIFY: UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")

    // MTU we negotiate after connect. 64 is plenty for the 25-byte live
    // telemetry response (see BLE_PROTOCOL.md §6.4); going higher costs us
    // nothing and avoids the need for frame reassembly across notifications.
    const val PREFERRED_MTU = 64
}
