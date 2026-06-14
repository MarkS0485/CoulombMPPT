package app.coulombmppt.data.remote

import android.net.Uri
import kotlinx.serialization.Serializable

// Credentials for talking to a paired Windows desktop app's local API server.
// The Windows app emits a `coulomb://pair?...` link (shown as text + QR on its
// Remote-API screen); the user pastes it here. We parse it into the four
// fields the HTTP client needs:
//
//   u = base URL           (percent-encoded https://host:port)
//   k = keyId              (HMAC key identifier)
//   s = secret             (base64url, no padding — re-padded to std base64)
//   t = cert SHA-256       (uppercase hex of the server leaf cert DER)
//
// These mirror ApiServer.BuildPairingUri on the Windows side exactly.
@Serializable
data class RemotePairing(
    val baseUrl: String,       // e.g. https://192.168.1.50:8800
    val keyId: String,
    val secretBase64: String,  // standard base64 (re-padded from base64url)
    val certSha256: String,    // uppercase hex, no separators
) {
    companion object {
        /** Parse a `coulomb://pair?u=…&k=…&s=…&t=…` link. Returns null on any
         *  malformed/missing field so the UI can show a clean error. */
        fun parse(raw: String): RemotePairing? {
            val uri = runCatching { Uri.parse(raw.trim()) }.getOrNull() ?: return null
            if (!"coulomb".equals(uri.scheme, ignoreCase = true)) return null
            if (!"pair".equals(uri.host, ignoreCase = true)) return null

            val base  = uri.getQueryParameter("u")?.takeIf { it.isNotBlank() } ?: return null
            val keyId = uri.getQueryParameter("k")?.takeIf { it.isNotBlank() } ?: return null
            val sec   = uri.getQueryParameter("s")?.takeIf { it.isNotBlank() } ?: return null
            val cert  = uri.getQueryParameter("t")?.takeIf { it.isNotBlank() } ?: return null

            return RemotePairing(
                baseUrl      = base.trimEnd('/'),
                keyId        = keyId,
                secretBase64 = base64UrlToStandard(sec),
                certSha256   = cert.uppercase(),
            )
        }

        /** base64url (RFC 4648, no padding) → standard base64. The Windows side
         *  encodes the secret URL-safe to keep the QR dense; the JCE Mac needs
         *  the raw bytes back, so we restore +/ and the = padding. */
        private fun base64UrlToStandard(s: String): String {
            var t = s.replace('-', '+').replace('_', '/')
            when (t.length % 4) {
                2 -> t += "=="
                3 -> t += "="
            }
            return t
        }
    }
}
