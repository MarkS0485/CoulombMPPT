package app.coulombmppt.data.model

// Synthesised top-level charger state. We don't yet have ground truth for
// the firmware's solar_status / work_status / power_status enum codes
// (BLE_PROTOCOL.md §7.1 — they come from the cloud API not the APK). Until
// they're verified on hardware we derive the state heuristically from the
// numeric registers; once enum codes are known, swap the heuristic for a
// direct mapping.
enum class ChargerState {
    Bulk,      // charging hard
    Boost,     // absorption phase
    Float,     // top-up
    Idle,      // no PV input, no load
    LoadOff,   // load disconnected (low voltage cutoff or manual)
    Fault,
    Unknown;

    companion object {
        /**
         * Heuristic mapping until the firmware enum codes are confirmed.
         * Charge current present  → some flavour of charging (Bulk).
         * Load current only       → discharging (Idle node, load on).
         * Nothing                 → Idle.
         */
        fun fromRegisters(
            solarStatus: Int,
            workStatus: Int,
            powerStatus: Int,
            chargeCurrent: Double,
            dischargeCurrent: Double,
            batteryVoltage: Double,
        ): ChargerState {
            // Fault bit is conventionally encoded high in this kind of
            // firmware. We treat any non-zero code in solarStatus combined
            // with chargeCurrent == 0 and batteryVoltage > 10 V as Fault —
            // refine when we have firmware docs.
            if (workStatus >= 0x10 || solarStatus >= 0x10) return Fault
            return when {
                chargeCurrent    > 0.1 && batteryVoltage > 14.0 -> Float
                chargeCurrent    > 0.1                          -> Bulk
                dischargeCurrent > 0.05                         -> Idle
                powerStatus == 0                                -> LoadOff
                else                                            -> Idle
            }
        }
    }
}
