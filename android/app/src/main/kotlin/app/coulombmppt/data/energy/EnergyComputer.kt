package app.coulombmppt.data.energy

import app.coulombmppt.data.history.LiveSampleRow
import app.coulombmppt.data.model.BatteryProfile
import app.coulombmppt.data.model.DayEnergy
import kotlin.math.abs

// Pure functions for the battery energy / inverter-inference math.
// All operate on List<LiveSampleRow> so they're easy to unit-test and
// are independent of Android/coroutine concerns.
object EnergyComputer {

    // Max time gap that we'll integrate across without assuming a data hole.
    // If the app was killed and gaps > 5 min exist, we don't hallucinate energy.
    private const val MAX_INTEGRATION_GAP_MS = 5 * 60_000L

    // Minimum voltage change between consecutive samples to be considered
    // "real" movement vs. sensor noise. Prevents noise inflation on flat curve.
    private const val MIN_VOLTAGE_DELTA = 0.02  // V

    /**
     * Compute the energy totals for one calendar day from its ordered samples.
     *
     * Integration method: trapezoidal rule on consecutive pairs. Gaps longer
     * than [MAX_INTEGRATION_GAP_MS] are skipped so offline periods don't
     * contribute energy that wasn't actually measured.
     */
    fun computeDay(
        rows: List<LiveSampleRow>,
        dayMs: Long,
        profile: BatteryProfile?,
    ): DayEnergy {
        if (rows.size < 2) return DayEnergy.empty(dayMs)

        var pvWh     = 0.0
        var battIn   = 0.0
        var battOut  = 0.0
        var mpptNet  = 0.0

        for (i in 1 until rows.size) {
            val r0 = rows[i - 1]
            val r1 = rows[i]
            val dtMs = r1.tsMs - r0.tsMs
            if (dtMs <= 0 || dtMs > MAX_INTEGRATION_GAP_MS) continue
            val dtH = dtMs / 3_600_000.0

            // PV energy: trapezoidal average of pvWatts × dt.
            val avgPv = (r0.pvWatts + r1.pvWatts) / 2.0
            pvWh += avgPv * dtH

            // MPPT net: (charge - discharge) × average voltage × dt.
            val avgV     = (r0.batteryVoltage + r1.batteryVoltage) / 2.0
            val avgChg   = (r0.chargeCurrent + r1.chargeCurrent) / 2.0
            val avgDis   = (r0.dischargeCurrent + r1.dischargeCurrent) / 2.0
            val mpptNetA = avgChg - avgDis
            mpptNet += mpptNetA * avgV * dtH

            // Battery I/O from voltage-derived SoC.
            if (profile != null) {
                val voltDelta = r1.batteryVoltage - r0.batteryVoltage
                // Only accumulate when the voltage actually moved, otherwise
                // flat-curve NMC packs produce massive integral noise.
                if (abs(voltDelta) >= MIN_VOLTAGE_DELTA) {
                    val soc0 = profile.socFromVoltage(r0.batteryVoltage)
                    val soc1 = profile.socFromVoltage(r1.batteryVoltage)
                    val dWh  = profile.deltaWh(soc0, soc1)
                    if (dWh > 0) battIn  += dWh
                    else         battOut += -dWh
                }
            }
        }

        val easunNet = if (profile != null) {
            val battNetWh = battIn - battOut
            battNetWh - mpptNet
        } else null

        return DayEnergy(
            dayMs       = dayMs,
            pvWh        = maxOf(0.0, pvWh),
            battInWh    = battIn,
            battOutWh   = battOut,
            mpptNetWh   = mpptNet,
            easunNetWh  = easunNet,
            sampleCount = rows.size,
        )
    }

    /**
     * Given a short window of recent live samples (e.g. the last 2–3 minutes),
     * estimate the real-time EA SUN net current in amps.
     *
     * The battery's voltage change rate → net amps via [BatteryProfile.inferredNetCurrentA].
     * Subtracting the MPPT's sensed (charge - discharge) gives the EA SUN share.
     *
     * Returns null if the profile is missing or the window is too short.
     * The estimate is noisy on flat NMC curves; smooth in the UI with a
     * rolling average window before displaying it.
     */
    fun liveEasunEstimateA(
        recent: List<LiveSampleRow>,
        profile: BatteryProfile?,
    ): Double? {
        if (profile == null || recent.size < 2) return null
        val oldest = recent.first()
        val newest = recent.last()
        val dtMs = newest.tsMs - oldest.tsMs
        if (dtMs < 30_000L) return null    // need at least 30 s of history

        val socOld = profile.socFromVoltage(oldest.batteryVoltage)
        val socNew = profile.socFromVoltage(newest.batteryVoltage)
        val netBattA = profile.inferredNetCurrentA(socOld, socNew, dtMs)

        // Average MPPT net current over the window.
        val mpptNetA = recent.map { it.chargeCurrent - it.dischargeCurrent }.average()

        return netBattA - mpptNetA
    }
}
