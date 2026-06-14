package app.coulombmppt.data.remote

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// Persists the single Windows-PC pairing in its own DataStore file, kept
// separate from the controller/settings store so clearing one never disturbs
// the other. At most one PC is paired at a time (the desktop app is the hub).
private val Context.coulombRemoteDataStore by preferencesDataStore(name = "coulombmppt_remote")

// How this device gets its telemetry:
//  • LocalBluetooth — this phone holds the BLE link to the controller.
//  • RemoteApi      — this phone has no BLE link; it views and controls the
//                     controller through the paired Windows PC's API (the PC
//                     holds the BLE link and relays commands).
enum class ConnectionMode {
    /** This phone holds the BLE link directly. */
    LocalBluetooth,
    /** The paired Windows PC holds the BLE link; phone reads from its API. */
    RemoteApi,
    /** Phone holds BLE AND relays each live frame to the Windows API so the
     *  desktop sees live data while you're away from the PC.
     *  Effectively LocalBluetooth + background live push. */
    Hybrid,
}

class RemotePairingStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private object Keys {
        val PAIRING_JSON = stringPreferencesKey("pairing_json")
        val MODE         = stringPreferencesKey("connection_mode")
    }

    val flow: Flow<RemotePairing?> = context.coulombRemoteDataStore.data.map { prefs ->
        prefs[Keys.PAIRING_JSON]?.let { raw ->
            runCatching { json.decodeFromString<RemotePairing>(raw) }.getOrNull()
        }
    }

    /** Last-chosen connection mode (default LocalBluetooth). Used to pre-select
     *  the launch chooser; the actual active mode lives in ServiceLocator. */
    val modeFlow: Flow<ConnectionMode> = context.coulombRemoteDataStore.data.map { prefs ->
        when (prefs[Keys.MODE]) {
            ConnectionMode.RemoteApi.name -> ConnectionMode.RemoteApi
            ConnectionMode.Hybrid.name    -> ConnectionMode.Hybrid
            else                          -> ConnectionMode.LocalBluetooth
        }
    }

    suspend fun snapshot(): RemotePairing? = flow.first()
    suspend fun modeSnapshot(): ConnectionMode = modeFlow.first()

    suspend fun save(pairing: RemotePairing) {
        context.coulombRemoteDataStore.edit { it[Keys.PAIRING_JSON] = json.encodeToString(pairing) }
    }

    suspend fun clear() {
        context.coulombRemoteDataStore.edit { it.remove(Keys.PAIRING_JSON) }
    }

    suspend fun setMode(mode: ConnectionMode) {
        context.coulombRemoteDataStore.edit { it[Keys.MODE] = mode.name }
    }
}
