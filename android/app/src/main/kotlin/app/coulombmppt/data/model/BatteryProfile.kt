package app.coulombmppt.data.model

// Resolved battery profile — the single source of truth for energy math.
// Created by PairedController.resolvedBatteryProfile() by combining the user's
// pack-spec fields with the controller's own setpoints as a fallback.
//
// All energy arithmetic in the app (SoC calculation, Ah/Wh deltas, net battery
// current inference) works through this class so the logic lives in one place.
data class BatteryProfile(
    val emptyV: Double,       // 0% SoC voltage (user empty or controller cutoff)
    val fullV: Double,        // 100% SoC voltage (user full or controller charge)
    val nominalV: Double,     // midpoint voltage — used for Wh = Ah × V conversions
    val capacityAh: Double,   // total pack capacity in Ah
) {
    val capacityWh: Double get() = nominalV * capacityAh

    /** Linear SoC from voltage. Returns [0, 100]. */
    fun socFromVoltage(v: Double): Double =
        ((v - emptyV) / (fullV - emptyV) * 100.0).coerceIn(0.0, 100.0)

    /** Stored charge in Ah at a given SoC [0–100]. */
    fun ahAtSoc(socPct: Double): Double = capacityAh * socPct / 100.0

    /** Net energy change (Wh, signed) for a SoC swing.
     *  Positive = energy stored; negative = energy drawn. */
    fun deltaWh(fromSocPct: Double, toSocPct: Double): Double =
        capacityWh * (toSocPct - fromSocPct) / 100.0

    /** Average net battery current (A, signed) inferred from a voltage/SoC
     *  swing over a time window. Positive = charging, negative = discharging.
     *  [elapsedMs] is the window duration in milliseconds. */
    fun inferredNetCurrentA(fromSocPct: Double, toSocPct: Double, elapsedMs: Long): Double {
        if (elapsedMs <= 0) return 0.0
        val deltaAh = capacityAh * (toSocPct - fromSocPct) / 100.0
        val elapsedH = elapsedMs / 3_600_000.0
        return deltaAh / elapsedH
    }
}
