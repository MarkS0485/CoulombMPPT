package app.coulombmppt.data.remote

import android.util.Base64
import java.io.IOException
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import app.coulombmppt.data.log.AppLogger

// HTTPS client for the Windows local API. Two security properties, both
// implemented with platform APIs only (no OkHttp / no extra dependency):
//
//  1. Certificate pinning — the server uses a self-signed cert, so instead of
//     a CA chain we trust exactly the leaf whose SHA-256(DER) matches the
//     thumbprint from the pairing link. A permissive hostname verifier is fine
//     *because* the pin already authenticates the server.
//
//  2. Coulomb1 HMAC request signing — reproduces HmacAuth on the Windows side:
//       canonical = METHOD \n PATH \n QUERY \n ts \n nonce \n hex(sha256(body))
//       sig       = base64( HMAC-SHA256(secret, canonical) )
//       header    = Authorization: Coulomb1 keyId=…,ts=…,nonce=…,sig=…
//     QUERY is the raw (already-encoded) query string with the leading '?'
//     stripped, exactly as Kestrel sees it — so we sign the same bytes we send.
class CoulombApiClient(private val pairing: RemotePairing) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // Lazily-built socket factory that enforces the cert pin.
    private val socketFactory: SSLSocketFactory by lazy { buildPinnedFactory(pairing.certSha256) }
    private val permissiveHostname = HostnameVerifier { _, _ -> true }

    private data class HttpResult(val code: Int, val body: String)

    /** Open ping — verifies reachability + cert pin without auth. */
    suspend fun ping(): Result<String> =
        request("GET", "/api/ping", auth = false, body = null).map { it.body }

    /** Upload a batch of samples for [mac]; server dedups by timestamp. */
    suspend fun ingest(req: IngestRequest): Result<IngestResponse> {
        val payload = json.encodeToString(req)
        return request("POST", "/api/v1/history/ingest", auth = true, body = payload)
            .mapCatching { json.decodeFromString<IngestResponse>(it.body) }
    }

    /** Fetch the PC's recorded window for [mac] (last [hours]). */
    suspend fun fetchHistory(mac: String, hours: Double): Result<HistoryResponse> {
        val query = "mac=${urlEncode(mac)}&hours=${formatHours(hours)}"
        return request("GET", "/api/v1/history", auth = true, body = null, query = query)
            .mapCatching { json.decodeFromString<HistoryResponse>(it.body) }
    }

    // --- Remote-control mode ----------------------------------------------

    /** Current PC status — connection state plus the latest live + settings. */
    suspend fun status(): Result<RemoteStatus> =
        request("GET", "/api/v1/status", auth = true, body = null)
            .mapCatching { json.decodeFromString<RemoteStatus>(it.body) }

    /** Ask the PC to re-read the controller's settings block over BLE. */
    suspend fun requestSettingsRead(): Result<Unit> =
        request("POST", "/api/v1/settings/read", auth = true, body = null).map { }

    /** Write one holding register on the controller via the PC's BLE link. */
    suspend fun writeRegister(address: Int, value: Int): Result<Boolean> {
        val payload = json.encodeToString(RegisterBody(address, value))
        return request("POST", "/api/v1/settings/register", auth = true, body = payload)
            .mapCatching { json.decodeFromString<OkResult>(it.body).ok }
    }

    /** Relay one live frame from this phone's BLE session to the PC.
     *  Used by hybrid mode; best-effort — caller should swallow failures. */
    suspend fun pushLive(frame: RemoteLiveFrame): Result<Unit> {
        val payload = json.encodeToString(frame)
        return request("POST", "/api/v1/live/push", auth = true, body = payload).map { }
    }

    /** Point the PC at [mac] (sets current controller + connects). */
    suspend fun connect(mac: String): Result<Unit> {
        val payload = json.encodeToString(ConnectBody(mac))
        return request("POST", "/api/v1/connect", auth = true, body = payload).map { }
    }

    // --- core request ------------------------------------------------------

    private suspend fun request(
        method: String,
        path: String,
        auth: Boolean,
        body: String?,
        query: String = "",
    ): Result<HttpResult> = withContext(Dispatchers.IO) {
        runCatching {
            val urlStr = pairing.baseUrl + path + if (query.isNotEmpty()) "?$query" else ""
            val conn = (URL(urlStr).openConnection() as HttpsURLConnection).apply {
                sslSocketFactory = socketFactory
                hostnameVerifier = permissiveHostname
                requestMethod = method
                connectTimeout = 8_000
                readTimeout = 20_000
                useCaches = false
            }

            val bodyBytes = body?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
            if (auth) conn.setRequestProperty("Authorization", authHeader(method, path, query, bodyBytes))
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(bodyBytes) }
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            conn.disconnect()
            if (code !in 200..299) throw IOException("HTTP $code: ${text.take(200)}")
            HttpResult(code, text)
        }.onFailure { AppLogger.w("CoulombApiClient", "$method $path failed: ${it.message}") }
    }

    // --- Coulomb1 signing -----------------------------------------------------

    private fun authHeader(method: String, path: String, query: String, body: ByteArray): String {
        val ts = System.currentTimeMillis().toString()
        val nonce = randomHex(16)
        val bodyHex = sha256Hex(body)
        val canonical = "$method\n$path\n$query\n$ts\n$nonce\n$bodyHex"

        val secret = Base64.decode(pairing.secretBase64, Base64.DEFAULT)
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret, "HmacSHA256")) }
        val sig = Base64.encodeToString(
            mac.doFinal(canonical.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP,
        )
        return "Coulomb1 keyId=${pairing.keyId},ts=$ts,nonce=$nonce,sig=$sig"
    }

    // --- helpers -----------------------------------------------------------

    private fun urlEncode(s: String): String = URLEncoder.encode(s, "UTF-8")

    // Avoid locale-specific formatting (e.g. comma decimals) and match the
    // double the server parses.
    private fun formatHours(h: Double): String =
        if (h == h.toLong().toDouble()) h.toLong().toString() else h.toString()

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun randomHex(nBytes: Int): String {
        val b = ByteArray(nBytes)
        SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** SSL factory that trusts exactly the leaf cert whose SHA-256(DER)
         *  equals [pinHex] (uppercase hex, no separators). */
        private fun buildPinnedFactory(pinHex: String): SSLSocketFactory {
            val pin = pinHex.uppercase()
            val tm = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                    val leaf = chain?.firstOrNull()
                        ?: throw CertificateException("no server certificate")
                    val sha = MessageDigest.getInstance("SHA-256").digest(leaf.encoded)
                    val hex = sha.joinToString("") { "%02X".format(it) }
                    if (!hex.equals(pin, ignoreCase = true))
                        throw CertificateException("cert pin mismatch")
                }
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            return SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(tm), SecureRandom())
            }.socketFactory
        }
    }
}
