package app.coulombmppt.data.store

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import app.coulombmppt.data.model.BatteryChemistry
import app.coulombmppt.data.model.DeviceType
import app.coulombmppt.data.model.PairedController

// DataStore wrapper. The active source of truth is `controllers` — a JSON
// list of paired MPPT units, each with its own MAC, display name, accent
// and cached battery profile. The single-controller legacy keys
// (paired_mac, paired_name, cached_full_v, …) are still read so an upgrade
// from a previous build migrates seamlessly into the list on first launch.
//
// Existing screens that read `settings.pairedMac`, `settings.displayName`,
// `settings.cachedFullV`, etc. keep working because those are derived
// getters on the *selected* controller (see CoulombMpptSettings below).
private val Context.coulombMpptDataStore by preferencesDataStore(name = "coulombmppt")

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

@Serializable
data class CoulombMpptSettings(
    val controllers: List<PairedController> = emptyList(),
    val selectedControllerId: String? = null,
    val useFakeSource: Boolean = false,
) {
    val selected: PairedController?
        get() = controllers.firstOrNull { it.id == selectedControllerId }
            ?: controllers.firstOrNull()

    val isConfigured: Boolean get() = controllers.isNotEmpty()

    // ---- Legacy single-controller compatibility surface. The original
    //      screens were written against these fields directly; routing them
    //      to the selected controller avoids a sprawling refactor while we
    //      bring up the multi-controller UI. ----
    val pairedMac:    String? get() = selected?.mac
    val displayName:  String? get() = selected?.displayName
    val cachedFullV:    Double? get() = selected?.cachedFullV
    val cachedRecoverV: Double? get() = selected?.cachedRecoverV
    val cachedEmptyV:   Double? get() = selected?.cachedEmptyV

    val hasBatteryProfile: Boolean get() = selected?.hasBatteryProfile == true

    fun computeSocFromCache(batteryVoltage: Double): Double? =
        selected?.computeSocFromCache(batteryVoltage)

    fun controllerById(id: String): PairedController? =
        controllers.firstOrNull { it.id == id }
}

class SettingsStore(private val context: Context) {
    private object Keys {
        val CONTROLLERS_JSON = stringPreferencesKey("controllers_json")
        val SELECTED_ID      = stringPreferencesKey("selected_controller_id")
        val USE_FAKE         = stringPreferencesKey("use_fake_source")

        // Legacy single-controller keys — read for one-shot migration only.
        val LEGACY_MAC          = stringPreferencesKey("paired_mac")
        val LEGACY_NAME         = stringPreferencesKey("paired_name")
        val LEGACY_FULL_V       = doublePreferencesKey("cached_full_v")
        val LEGACY_RECOVER_V    = doublePreferencesKey("cached_recover_v")
        val LEGACY_EMPTY_V      = doublePreferencesKey("cached_empty_v")
    }

    val flow: Flow<CoulombMpptSettings> = context.coulombMpptDataStore.data.map { it.toSettings() }

    suspend fun snapshot(): CoulombMpptSettings = flow.first()

    /** Append-or-update a paired controller and select it. Identical MACs
     *  are treated as the same controller — we update its display name and
     *  keep the existing id, profile cache and pair-date. */
    suspend fun pair(
        macAddress: String,
        displayName: String?,
        deviceType: DeviceType = DeviceType.GenericModbusNus,
        victronKeyHex: String? = null,
    ): PairedController {
        var result: PairedController? = null
        context.coulombMpptDataStore.edit { prefs ->
            val list = decodeList(prefs).migrateFromLegacyIfNeeded(prefs)
            val existing = list.firstOrNull { it.mac.equals(macAddress, ignoreCase = true) }
            val updated: PairedController
            val newList: List<PairedController>
            if (existing != null) {
                // Re-pairing updates the name and (re)applies the device type /
                // key so a user can fix a mistyped Victron key by pairing again.
                updated = existing.copy(
                    displayName   = displayName ?: existing.displayName,
                    deviceType    = deviceType,
                    victronKeyHex = victronKeyHex ?: existing.victronKeyHex,
                )
                newList = list.map { if (it.id == existing.id) updated else it }
            } else {
                updated = PairedController(
                    mac = macAddress,
                    displayName = displayName,
                    deviceType = deviceType,
                    victronKeyHex = victronKeyHex,
                )
                newList = list + updated
            }
            result = updated
            prefs[Keys.CONTROLLERS_JSON] = json.encodeToString(newList)
            prefs[Keys.SELECTED_ID] = updated.id
            // Once we've migrated, drop the legacy keys so we don't double-
            // count them on subsequent reads.
            prefs.dropLegacyKeys()
        }
        return result!!
    }

    /** Legacy no-arg overload — unpairs the currently selected controller.
     *  Kept so AppSettingsScreen's "Unpair" button works without knowing
     *  about the multi-controller model yet. */
    suspend fun unpair() {
        val id = snapshot().selected?.id ?: return
        unpair(id)
    }

    /** Remove a controller. If it was the selected one, fall back to the
     *  first remaining controller (or none). */
    suspend fun unpair(controllerId: String) {
        context.coulombMpptDataStore.edit { prefs ->
            val list = decodeList(prefs).migrateFromLegacyIfNeeded(prefs)
            val newList = list.filterNot { it.id == controllerId }
            prefs[Keys.CONTROLLERS_JSON] = json.encodeToString(newList)
            val selected = prefs[Keys.SELECTED_ID]
            if (selected == controllerId) {
                val next = newList.firstOrNull()?.id
                if (next == null) prefs.remove(Keys.SELECTED_ID)
                else prefs[Keys.SELECTED_ID] = next
            }
            prefs.dropLegacyKeys()
        }
    }

    suspend fun selectController(controllerId: String) {
        context.coulombMpptDataStore.edit { prefs -> prefs[Keys.SELECTED_ID] = controllerId }
    }

    suspend fun setUseFakeSource(enabled: Boolean) {
        context.coulombMpptDataStore.edit { prefs ->
            prefs[Keys.USE_FAKE] = enabled.toString()
        }
    }

    /** Update display name, icon, accent and site label for one controller. */
    suspend fun updateProfile(
        controllerId: String,
        displayName: String? = null,
        siteLabel:   String? = null,
        iconKey:     String? = null,
        accentArgb:  Long?   = null,
    ) {
        context.coulombMpptDataStore.edit { prefs ->
            val list = decodeList(prefs).migrateFromLegacyIfNeeded(prefs)
            val newList = list.map { c ->
                if (c.id != controllerId) c else c.copy(
                    displayName = displayName ?: c.displayName,
                    siteLabel   = siteLabel   ?: c.siteLabel,
                    iconKey     = iconKey     ?: c.iconKey,
                    accentArgb  = accentArgb  ?: c.accentArgb,
                )
            }
            prefs[Keys.CONTROLLERS_JSON] = json.encodeToString(newList)
            prefs.dropLegacyKeys()
        }
    }

    /** Persist the user-supplied pack spec (chemistry / nominal V / user
     *  full+empty / capacity). Any null arg leaves that field untouched —
     *  use Double.NaN sentinel via a wrapper if "clear back to null" ever
     *  becomes necessary. */
    suspend fun updateBatteryPack(
        controllerId: String,
        chemistry:    BatteryChemistry? = null,
        nominalV:     Double? = null,
        userFullV:    Double? = null,
        userEmptyV:   Double? = null,
        capacityKwh:  Double? = null,
        capacityAh:   Double? = null,
    ) {
        context.coulombMpptDataStore.edit { prefs ->
            val list = decodeList(prefs).migrateFromLegacyIfNeeded(prefs)
            val newList = list.map { c ->
                if (c.id != controllerId) c else c.copy(
                    packChemistry   = chemistry   ?: c.packChemistry,
                    packNominalV    = nominalV    ?: c.packNominalV,
                    packUserFullV   = userFullV   ?: c.packUserFullV,
                    packUserEmptyV  = userEmptyV  ?: c.packUserEmptyV,
                    packCapacityKwh = capacityKwh ?: c.packCapacityKwh,
                    packCapacityAh  = capacityAh  ?: c.packCapacityAh,
                )
            }
            prefs[Keys.CONTROLLERS_JSON] = json.encodeToString(newList)
            prefs.dropLegacyKeys()
        }
    }

    /** Cache the three voltage setpoints for one specific controller. */
    suspend fun saveBatteryProfile(
        controllerId: String,
        fullV: Double, recoverV: Double, emptyV: Double,
    ) {
        context.coulombMpptDataStore.edit { prefs ->
            val list = decodeList(prefs).migrateFromLegacyIfNeeded(prefs)
            val newList = list.map { c ->
                if (c.id != controllerId) c else c.copy(
                    cachedFullV    = fullV,
                    cachedRecoverV = recoverV,
                    cachedEmptyV   = emptyV,
                )
            }
            prefs[Keys.CONTROLLERS_JSON] = json.encodeToString(newList)
            prefs.dropLegacyKeys()
        }
    }

    /** Legacy single-controller overload — routes to the currently selected
     *  controller. Kept so ControllerSettingsViewModel keeps compiling. */
    suspend fun saveBatteryProfile(fullV: Double, recoverV: Double, emptyV: Double) {
        val id = snapshot().selected?.id ?: return
        saveBatteryProfile(id, fullV, recoverV, emptyV)
    }

    private fun Preferences.toSettings(): CoulombMpptSettings {
        val migrated = decodeList(this).migrateFromLegacy(this)
        val selectedRaw = this[Keys.SELECTED_ID]
        val selected = selectedRaw?.takeIf { id -> migrated.any { it.id == id } }
            ?: migrated.firstOrNull()?.id
        return CoulombMpptSettings(
            controllers          = migrated,
            selectedControllerId = selected,
            useFakeSource        = this[Keys.USE_FAKE]?.toBoolean() ?: false,
        )
    }

    private fun decodeList(prefs: Preferences): List<PairedController> {
        val raw = prefs[Keys.CONTROLLERS_JSON] ?: return emptyList()
        return runCatching { json.decodeFromString<List<PairedController>>(raw) }.getOrDefault(emptyList())
    }

    /** First time we see a legacy paired_mac without controllers_json,
     *  synthesise a one-item list so the user's previously-paired MPPT
     *  re-appears. The next write will persist the new shape. */
    private fun List<PairedController>.migrateFromLegacy(prefs: Preferences): List<PairedController> {
        if (isNotEmpty()) return this
        val mac = prefs[Keys.LEGACY_MAC] ?: return this
        return listOf(
            PairedController(
                mac            = mac,
                displayName    = prefs[Keys.LEGACY_NAME],
                cachedFullV    = prefs[Keys.LEGACY_FULL_V],
                cachedRecoverV = prefs[Keys.LEGACY_RECOVER_V],
                cachedEmptyV   = prefs[Keys.LEGACY_EMPTY_V],
            )
        )
    }

    /** Same migration logic, scoped to a transactional edit block. */
    private fun List<PairedController>.migrateFromLegacyIfNeeded(
        prefs: androidx.datastore.preferences.core.MutablePreferences
    ): List<PairedController> = migrateFromLegacy(prefs)

    private fun androidx.datastore.preferences.core.MutablePreferences.dropLegacyKeys() {
        remove(Keys.LEGACY_MAC)
        remove(Keys.LEGACY_NAME)
        remove(Keys.LEGACY_FULL_V)
        remove(Keys.LEGACY_RECOVER_V)
        remove(Keys.LEGACY_EMPTY_V)
    }
}
