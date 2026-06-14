package app.coulombmppt.data.model

import kotlinx.serialization.Serializable

// Snapshot of the controller's writable settings (BLE_PROTOCOL.md §3.2).
// `convert` is hard-coded to 10 for every voltage field, which is what the
// live telemetry uses too — confirm on hardware and tune per-field if any
// turn out to be different.
@Serializable
data class MpptSettings(
    val batteryType:               Int,      // enum — codes TBD on hardware
    val timerHours:                Int,
    val timerMinutes:              Int,
    val chargeVoltageSetpoint:     Double,   // V — reg / 10 — "full" / boost target
    val outputMode:                Int,      // enum — codes TBD
    val cutoffVoltageSetpoint:     Double,   // V — reg / 10 — low-disconnect / "empty"
    val manualLoadOn:              Boolean,  // 0 / 1
    val voltageMonitorMode:        Int,      // enum — codes TBD
    val recoveryVoltageSetpoint:   Double,   // V — reg / 10 — load reconnect
) {
    /**
     * Linear-interpolation SoC from the controller's own calibration.
     *
     * The cutoff and charge setpoints are the controller's actual idea of
     * "0%" and "100%", so we treat them as the SoC anchors. That works
     * cleanly across battery chemistries (Mark's 24 V LiFePO4: 20.0 V → 0,
     * 25.5 V → 100; a 12 V lead-acid pack: 11.1 V → 0, 14.4 V → 100).
     *
     * Returns a value in [0, 100], or null if the calibration is degenerate
     * (e.g. cutoff ≥ charge, which would happen if we read garbage from a
     * not-yet-initialised firmware).
     */
    fun computeSoc(batteryVoltage: Double): Double? {
        val low  = cutoffVoltageSetpoint
        val high = chargeVoltageSetpoint
        if (high <= low + 0.1) return null            // degenerate calibration
        val pct = (batteryVoltage - low) / (high - low) * 100.0
        return pct.coerceIn(0.0, 100.0)
    }
}

@Serializable
enum class BatteryType(val code: Int, val displayName: String) {
    // Placeholder enumeration until we round-trip on hardware. Most cheap
    // MPPTs of this generation expose at least these:
    Unknown    (0, "Unknown"),
    SealedLead (1, "Sealed lead-acid"),
    GelLead    (2, "Gel lead-acid"),
    FloodedLead(3, "Flooded lead-acid"),
    Lithium    (4, "Lithium (LiFePO4)");

    companion object {
        fun ofCode(c: Int): BatteryType = entries.firstOrNull { it.code == c } ?: Unknown
    }
}

@Serializable
enum class OutputMode(val code: Int, val displayName: String) {
    Manual(0, "Manual"),
    Auto  (1, "Auto / dusk-to-dawn"),
    Timer (2, "Timer"),
    Unknown(255, "Unknown");

    companion object {
        fun ofCode(c: Int): OutputMode = entries.firstOrNull { it.code == c } ?: Unknown
    }
}
