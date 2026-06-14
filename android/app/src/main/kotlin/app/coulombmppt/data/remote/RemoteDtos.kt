package app.coulombmppt.data.remote

import kotlinx.serialization.Serializable

// Wire DTOs for the Windows local API. Field names are camelCase to match the
// ASP.NET minimal-API JSON (web defaults), which is what both the GET
// /api/v1/history response and the POST /api/v1/history/ingest body use.

/** One telemetry row over the wire — mirrors Windows LiveSample / Android
 *  LiveSampleRow field-for-field. */
@Serializable
data class RemoteSample(
    val timestampMs: Long,
    val batteryVoltage: Double,
    val chargeCurrent: Double,
    val dischargeCurrent: Double,
    val pvWatts: Double,
    val loadWatts: Double,
    val temperatureC: Double,
    val socPercent: Double,
)

/** POST /api/v1/history/ingest body. */
@Serializable
data class IngestRequest(
    val mac: String,
    val samples: List<RemoteSample>,
)

/** POST /api/v1/history/ingest response. */
@Serializable
data class IngestResponse(
    val ok: Boolean = false,
    val mac: String = "",
    val received: Int = 0,
    val added: Int = 0,
    val total: Int = 0,
)

/** GET /api/v1/history?mac=&hours= response. */
@Serializable
data class HistoryResponse(
    val mac: String = "",
    val hours: Double = 0.0,
    val samples: List<RemoteSample> = emptyList(),
)

// --- Remote-control mode (the phone driving the PC's BLE link) -------------

/** Live telemetry block inside GET /api/v1/status (Windows LiveDto). */
@Serializable
data class RemoteLive(
    val timestampMs: Long = 0,
    val batteryVoltage: Double = 0.0,
    val chargeCurrent: Double = 0.0,
    val dischargeCurrent: Double = 0.0,
    val temperatureC: Double = 0.0,
    val totalAccumulatedAh: Double = 0.0,
    val socEstimate: Double = 0.0,
    val solarStatusRaw: Int = 0,
    val workStatusRaw: Int = 0,
    val powerStatusRaw: Int = 0,
)

/** Settings block inside GET /api/v1/status (Windows SettingsDto). */
@Serializable
data class RemoteSettingsDto(
    val batteryType: Int = 0,
    val timerHours: Int = 0,
    val timerMinutes: Int = 0,
    val chargeVoltageSetpoint: Double = 0.0,
    val outputMode: Int = 0,
    val cutoffVoltageSetpoint: Double = 0.0,
    val manualLoadOn: Boolean = false,
    val voltageMonitorMode: Int = 0,
    val recoveryVoltageSetpoint: Double = 0.0,
)

/** GET /api/v1/status response. */
@Serializable
data class RemoteStatus(
    val state: String = "",
    val isReady: Boolean = false,
    val demoMode: Boolean = false,
    val lastError: String? = null,
    val currentMac: String? = null,
    val live: RemoteLive? = null,
    val settings: RemoteSettingsDto? = null,
)

// --- Hybrid relay mode (phone → PC live push) --------------------------------

/** POST /api/v1/live/push body — a single live frame from the phone's BLE link.
 *  Field names match the Windows LiveDto so no extra mapping is needed there. */
@Serializable
data class RemoteLiveFrame(
    val mac: String,
    val timestampMs: Long,
    val batteryVoltage: Double,
    val chargeCurrent: Double,
    val dischargeCurrent: Double,
    val temperatureC: Double,
    val totalAccumulatedAh: Double,
    val socEstimate: Double,
    val solarStatusRaw: Int,
    val workStatusRaw: Int,
    val powerStatusRaw: Int,
)

@Serializable
data class RegisterBody(val address: Int, val value: Int)

@Serializable
data class ConnectBody(val mac: String)

/** Generic { ok, ... } ack used by the control endpoints. */
@Serializable
data class OkResult(val ok: Boolean = false, val error: String? = null)
