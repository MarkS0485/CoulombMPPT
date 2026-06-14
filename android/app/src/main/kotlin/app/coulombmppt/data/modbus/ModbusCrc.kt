package app.coulombmppt.data.modbus

// Standard Modbus RTU CRC-16. The MPPT firmware uses the exact same routine
// the JS app's cmdParse.js implements (poly 0xA001 = 40961, init 0xFFFF, LSB
// out first). Lifted verbatim into Kotlin so we can both sign outgoing
// frames and validate incoming ones.
object ModbusCrc {

    /** Returns the two CRC bytes in wire order — `[lo, hi]`. */
    fun compute(data: ByteArray, length: Int = data.size): ByteArray {
        var crc = 0xFFFF
        for (i in 0 until length) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) {
                crc = if ((crc and 0x0001) != 0) (crc ushr 1) xor 0xA001
                      else                       (crc ushr 1)
            }
        }
        val lo = (crc and 0x00FF).toByte()
        val hi = ((crc ushr 8) and 0x00FF).toByte()
        return byteArrayOf(lo, hi)
    }

    /**
     * Validate that the trailing two bytes of [frame] match a fresh CRC
     * computed over everything before them. Returns true on a valid frame.
     */
    fun verify(frame: ByteArray): Boolean {
        if (frame.size < 4) return false                       // slave + fn + crcLo + crcHi
        val crc = compute(frame, frame.size - 2)
        return crc[0] == frame[frame.size - 2] && crc[1] == frame[frame.size - 1]
    }
}
