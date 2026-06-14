package app.coulombmppt.data.ble

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

// Decoder for Victron's "Instant Readout" BLE manufacturer advertisement
// (company id 0x02E1). The data is broadcast continuously — you decode it
// passively, no connection. Layout and field scaling follow Victron's
// "Extra Manufacturer Data" spec; the AES handling mirrors keshavdv/victron-ble.
//
// The bytes we receive are the manufacturer-specific data for company 0x02E1
// *after* the 2-byte company id is stripped (Android's
// ScanRecord.getManufacturerSpecificData(0x02E1) does this), so:
//
//   [0..1]  model id (LE)
//   [2]     record type        (0x01 = solar charger)
//   [3..4]  nonce / data counter (LE) — the AES-CTR IV
//   [5]     first byte of the key (key-check; must equal key[0])
//   [6..]   AES-CTR ciphertext
//
// Records are ≤ 16 bytes, so CTR collapses to a single keystream block:
// keystream = AES-ECB(key, counterBlock) where counterBlock holds the nonce in
// its low two bytes (little-endian) and zeroes elsewhere.
object VictronDecoder {

    const val COMPANY_ID = 0x02E1
    private const val RECORD_SOLAR_CHARGER = 0x01

    // A decoded solar-charger record. Null fields are the spec's "not available"
    // sentinels (e.g. a model with no load output reports load current as NA).
    data class SolarReading(
        val deviceState: Int,
        val chargerError: Int,
        val batteryVoltage: Double?,   // V
        val batteryCurrent: Double?,   // A, signed (+ charging)
        val yieldTodayKwh: Double?,    // kWh
        val pvPowerW: Int?,            // W
        val loadCurrent: Double?,      // A
    )

    /** True if a manufacturer payload looks like a Victron solar-charger advert. */
    fun isSolarCharger(payload: ByteArray?): Boolean =
        payload != null && payload.size >= 6 && (payload[2].toInt() and 0xFF) == RECORD_SOLAR_CHARGER

    /**
     * Decode a solar-charger advertisement with the per-device [keyHex]
     * (32 hex chars). Returns null if the key is missing/invalid, the record
     * isn't a solar charger, or the key-check byte doesn't match (stale key).
     */
    fun decodeSolar(payload: ByteArray, keyHex: String?): SolarReading? {
        val key = hexToBytes(keyHex) ?: return null
        if (key.size != 16) return null
        if (payload.size < 8) return null
        if ((payload[2].toInt() and 0xFF) != RECORD_SOLAR_CHARGER) return null

        val keyCheck = payload[5].toInt() and 0xFF
        if (keyCheck != (key[0].toInt() and 0xFF)) return null   // wrong/rotated key

        val nonce = u16(payload, 3)
        val cipher = payload.copyOfRange(6, payload.size)
        val plain = aesCtrSingleBlock(key, nonce, cipher) ?: return null
        if (plain.size < 12) return null

        fun s16OrNull(i: Int, na: Int): Double? { val v = s16(plain, i); return if ((v and 0xFFFF) == na) null else v.toDouble() }
        fun u16OrNull(i: Int, na: Int): Int? { val v = u16(plain, i); return if (v == na) null else v }

        // Load current is a 9-bit field starting at bit 112 (= plaintext byte 10).
        val loadRaw = (plain[10].toInt() and 0xFF) or ((plain[11].toInt() and 0x01) shl 8)

        return SolarReading(
            deviceState    = plain[0].toInt() and 0xFF,
            chargerError   = plain[1].toInt() and 0xFF,
            batteryVoltage = s16OrNull(2, 0x7FFF)?.let { it * 0.01 },
            batteryCurrent = s16OrNull(4, 0x7FFF)?.let { it * 0.1 },
            yieldTodayKwh  = u16OrNull(6, 0xFFFF)?.let { it * 0.01 },
            pvPowerW       = u16OrNull(8, 0xFFFF),
            loadCurrent    = if (loadRaw == 0x1FF) null else loadRaw * 0.1,
        )
    }

    private fun aesCtrSingleBlock(key: ByteArray, nonce: Int, cipher: ByteArray): ByteArray? = runCatching {
        val counter = ByteArray(16)
        counter[0] = (nonce and 0xFF).toByte()
        counter[1] = ((nonce ushr 8) and 0xFF).toByte()
        val aes = Cipher.getInstance("AES/ECB/NoPadding")
        aes.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        val keystream = aes.doFinal(counter)        // 16-byte keystream block
        val n = minOf(cipher.size, keystream.size)
        ByteArray(n) { (cipher[it].toInt() xor keystream[it].toInt()).toByte() }
    }.getOrNull()

    private fun s16(b: ByteArray, i: Int): Int = u16(b, i).toShort().toInt()
    private fun u16(b: ByteArray, i: Int): Int = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)

    /** Parse 32 hex chars (case-insensitive, optional spaces/colons) → 16 bytes. */
    fun hexToBytes(hex: String?): ByteArray? {
        if (hex == null) return null
        val clean = hex.filter { it.isLetterOrDigit() }
        if (clean.length % 2 != 0) return null
        return runCatching {
            ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }.getOrNull()
    }

    /** True if [hex] is a usable 16-byte key — for validating user input. */
    fun isValidKey(hex: String?): Boolean = hexToBytes(hex)?.size == 16
}
