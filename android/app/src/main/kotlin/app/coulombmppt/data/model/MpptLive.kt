package app.coulombmppt.data.model

import kotlinx.serialization.Serializable

// One snapshot of live telemetry as decoded from the firmware's
// `read(0x0001, 16)` response. Field names follow BLE_PROTOCOL.md §7.1
// rather than the original Chinese variable names so the rest of the app
// reads naturally.
@Serializable
data class MpptLive(
    val timestampMs:       Long,
    val batteryVoltage:    Double,   // V
    val chargeCurrent:     Double,   // A — PV → battery (≥ 0)
    val dischargeCurrent:  Double,   // A — battery → load (≥ 0)
    val temperatureC:      Double,   // controller temp
    val solarStatusRaw:    Int,
    val workStatusRaw:     Int,
    val powerStatusRaw:    Int,
    /** Lifetime accumulator from registers 8/9 (`total_power` in the vendor
     *  bundle), formula `(1000 × hi + lo) / 10`. The vendor app labels this
     *  as Ah even though the variable name says "power"; we follow the UI
     *  label, not the variable name. */
    val totalAccumulatedAh: Double,
    val chargerState:      ChargerState,
    val socEstimate:       Double,   // %, lookup-curve from batteryVoltage
) {
    /** Battery side instantaneous power. Positive when charging. */
    val batteryWatts: Double
        get() = batteryVoltage * (chargeCurrent - dischargeCurrent)

    /** Load side instantaneous power, always ≥ 0. */
    val loadWatts: Double
        get() = batteryVoltage * dischargeCurrent

    /**
     * PV-side power — best effort. The firmware doesn't expose PV V/I in
     * the registers the app polls (BLE_PROTOCOL.md §7.1), so we approximate
     * by V_battery × I_charge. That's actually the *battery-input* power
     * (= PV power minus MPPT-conversion losses, which are typically small).
     * Zero current means zero power, not "unknown" — return 0 for clarity.
     */
    val approxPvWatts: Double
        get() = batteryVoltage * chargeCurrent
}
