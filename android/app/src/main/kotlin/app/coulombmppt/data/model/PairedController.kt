package app.coulombmppt.data.model

import kotlinx.serialization.Serializable
import java.util.UUID

// One paired MPPT controller. Persisted as part of CoulombMpptSettings — see
// data/store/SettingsStore.kt. `id` is a stable UUID we mint on pair so the
// rest of the app can address a controller without re-typing its MAC.
//
// The site/icon/accent fields exist so the home list can show each unit
// with its own visual identity, mirroring how the sister coulombmonitor app
// styles each unit (`UnitProfile` over there). They default to the "home
// install" identity used in coulombmonitor's Unit 001.
@Serializable
data class PairedController(
    val id:           String  = UUID.randomUUID().toString(),
    val mac:          String,
    val displayName:  String? = null,
    // Which driver speaks to this device. Defaults to the original controller
    // so JSON written by older builds (which lacks the field) deserialises to
    // the right type with no migration.
    val deviceType:   DeviceType = DeviceType.GenericModbusNus,
    // Victron Instant Readout per-device advertisement key (32 hex chars).
    // Null for every other device type.
    val victronKeyHex: String? = null,
    val siteLabel:    String  = "Domestic solar · charge controller",
    val iconKey:      String  = ICON_HOME,
    val accentArgb:   Long    = ACCENT_COPPER_ARGB,
    val pairedAtMs:   Long    = System.currentTimeMillis(),
    // Cached battery profile — same three values as before, scoped per
    // controller now so multiple paired units don't trample each other.
    val cachedFullV:    Double? = null,
    val cachedRecoverV: Double? = null,
    val cachedEmptyV:   Double? = null,
    // User-supplied pack spec — what the human said the battery actually is,
    // distinct from cachedFullV/EmptyV (what the controller is configured
    // for). The two should agree, but the app shouldn't assume they do.
    val packChemistry:   BatteryChemistry = BatteryChemistry.Unknown,
    val packNominalV:    Double? = null,
    val packUserFullV:   Double? = null,
    val packUserEmptyV:  Double? = null,
    val packCapacityKwh: Double? = null,
    // Pack capacity in amp-hours — the input the energy / battery-I/O math wants
    // (it works in Ah, then × nominal V for Wh). Null until the user enters it.
    val packCapacityAh:  Double? = null,
) {
    val hasBatteryProfile: Boolean
        get() = cachedFullV != null && cachedEmptyV != null
                && cachedFullV > (cachedEmptyV + 0.1)

    /** True if the user has entered any of the pack-spec fields. */
    val hasUserPackProfile: Boolean
        get() = packChemistry != BatteryChemistry.Unknown ||
                packNominalV != null || packUserFullV != null ||
                packUserEmptyV != null || packCapacityKwh != null

    /** Resolved battery profile for energy calculations. Uses the user's pack
     *  spec for voltage bounds (with controller setpoints as fallback) and the
     *  user-entered Ah capacity. Returns null when either the voltage range or
     *  the Ah capacity is unknown — the caller must degrade gracefully. */
    fun resolvedBatteryProfile(): BatteryProfile? {
        val empty = packUserEmptyV  ?: cachedEmptyV  ?: return null
        val full  = packUserFullV   ?: cachedFullV   ?: return null
        if (full <= empty + 0.1) return null
        val nominal = packNominalV ?: ((empty + full) / 2.0)
        val ah = packCapacityAh ?: return null
        return BatteryProfile(emptyV = empty, fullV = full, nominalV = nominal, capacityAh = ah)
    }

    /** Linear interp same as MpptSettings.computeSoc, run from cache so the
     *  home screen has calibration before the next settings read lands. */
    fun computeSocFromCache(batteryVoltage: Double): Double? {
        val low  = cachedEmptyV ?: return null
        val high = cachedFullV  ?: return null
        if (high <= low + 0.1) return null
        return ((batteryVoltage - low) / (high - low) * 100.0).coerceIn(0.0, 100.0)
    }

    companion object {
        // Icon keys — resolved to ImageVectors at render time so the data
        // layer stays Compose-free.
        const val ICON_HOME = "home"
        const val ICON_BOAT = "boat"
        const val ICON_FACTORY = "factory"
        const val ICON_CABIN = "cabin"

        // Default accent (copper / amber-700) — matches Unit 001 in
        // coulombmonitor and the brand's "domestic solar" identity.
        const val ACCENT_COPPER_ARGB = 0xFFB45309L
        const val ACCENT_TEAL_ARGB   = 0xFF0F766EL    // unit-002-ish
        const val ACCENT_VIOLET_ARGB = 0xFF7C3AEDL    // unit-003-ish
    }
}
