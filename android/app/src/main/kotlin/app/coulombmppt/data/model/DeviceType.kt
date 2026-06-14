package app.coulombmppt.data.model

import kotlinx.serialization.Serializable

// What kind of device a paired entry is, so the app can pick the right driver.
// Persisted in PairedController; defaults to the original Modbus-over-Nordic-UART
// controller so existing pairings keep working without a migration step.
@Serializable
enum class DeviceType {
    /** The original Chinese MPPT: Modbus-RTU framed over a Nordic UART GATT
     *  service, connection-oriented polling. Handled by BleMpptSource. */
    GenericModbusNus,

    /** Victron SmartSolar/BlueSolar "Instant Readout": telemetry is decoded
     *  passively from the BLE manufacturer advertisement (AES-CTR with a
     *  per-device key) — no connection. Handled by VictronBleSource. */
    VictronInstantReadout,

    /** Renogy Rover/Wanderer/DCC via a BT-1/BT-2 dongle: Modbus-RTU over a
     *  split write/notify GATT layout. Also covers SRNE/Rich Solar/PowMr
     *  rebrands. Handled by ModbusBleSource with the Renogy profile. */
    Renogy,

    /** EPEver/EPSolar Tracer: Modbus input registers (fn 0x04). EPEver has no
     *  native BLE, so this needs a transparent RS485↔BLE bridge that exposes a
     *  UART-like GATT service. Handled by ModbusBleSource with the EPEver profile. */
    Epever;

    val displayName: String
        get() = when (this) {
            GenericModbusNus      -> "MPPT controller"
            VictronInstantReadout -> "Victron (Instant Readout)"
            Renogy                -> "Renogy (BT-1/BT-2)"
            Epever                -> "EPEver / EPSolar (via bridge)"
        }
}
