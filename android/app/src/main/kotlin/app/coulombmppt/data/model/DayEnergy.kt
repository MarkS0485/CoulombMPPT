package app.coulombmppt.data.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Energy totals for one calendar day, computed from the live-sample history
// by EnergyComputer. All values are in Wh.
//
// The MPPT array contribution (pvWh) is computed directly from the sensed
// pvWatts field (V_bat × I_charge). The battery I/O (battInWh / battOutWh)
// is derived from voltage → SoC → Ah deltas × nominal V, which reflects the
// *total* energy going through the battery — both the MPPT array and the EA
// SUN inverter's array and loads. The EA SUN net is then inferred as:
//   easunNetWh = battNetWh − mpptNetWh
// Positive → the EA SUN array is net-charging; negative → it's drawing load.
data class DayEnergy(
    val dayMs: Long,             // midnight local time (ms) that starts the day
    val pvWh: Double,            // MPPT solar generation (Wh)
    val battInWh: Double,        // total energy into battery (Wh, voltage-derived)
    val battOutWh: Double,       // total energy out of battery (Wh, voltage-derived)
    val mpptNetWh: Double,       // MPPT's net contribution (charge − discharge load)
    val easunNetWh: Double?,     // inferred EA SUN net (null if no battery profile)
    val sampleCount: Int,        // rows used (for diagnostic display)
) {
    val battNetWh:    Double  get() = battInWh - battOutWh
    val easunChargeWh: Double get() = easunNetWh?.let { maxOf(0.0, it) } ?: 0.0
    val easunLoadWh:   Double get() = easunNetWh?.let { maxOf(0.0, -it) } ?: 0.0

    val dayLabel: String get() =
        SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(dayMs))

    companion object {
        fun empty(dayMs: Long) = DayEnergy(
            dayMs, 0.0, 0.0, 0.0, 0.0, null, 0,
        )
    }
}
