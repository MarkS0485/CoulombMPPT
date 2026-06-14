package app.coulombmppt.data.modbus

import app.coulombmppt.data.log.AppLogger

// Tiny helper for the two Modbus function codes the MPPT firmware speaks.
// We don't pull in a general-purpose Modbus library because (a) we only need
// 0x03 and 0x10, (b) the BLE NUS transport is bespoke anyway, and (c) one
// file is easier to audit than a third-party black box.
object ModbusFrame {

    private const val TAG = "ModbusFrame"

    const val SLAVE: Int = 0x01     // default unit address for our controller — see BLE_PROTOCOL.md §6.5

    const val FN_READ:    Int = 0x03  // Read Holding Registers
    const val FN_READ_IN: Int = 0x04  // Read Input Registers (EPEver live data)
    const val FN_WRITE:   Int = 0x10  // Write Multiple Registers

    /**
     * Build a register-read request frame. Defaults match our controller
     * (slave 0x01, fn 0x03); other devices pass their own slave / function
     * (e.g. Renogy uses slave 0xFF, EPEver reads input registers with fn 0x04).
     *
     * Wire layout: `<slave> <fn> <addrHi> <addrLo> <qtyHi> <qtyLo> <crcLo> <crcHi>`.
     */
    fun read(address: Int, quantity: Int, slave: Int = SLAVE, function: Int = FN_READ): ByteArray {
        val payload = byteArrayOf(
            slave.toByte(),
            function.toByte(),
            ((address ushr 8) and 0xFF).toByte(),
            (address and 0xFF).toByte(),
            ((quantity ushr 8) and 0xFF).toByte(),
            (quantity and 0xFF).toByte(),
        )
        return payload + ModbusCrc.compute(payload)
    }

    /**
     * Build a "Write Multiple Registers" frame for a single 16-bit value.
     * Used for every settings write — see BLE_PROTOCOL.md §4.
     */
    fun writeSingle(address: Int, value: Int, slave: Int = SLAVE): ByteArray {
        val payload = byteArrayOf(
            slave.toByte(),
            FN_WRITE.toByte(),
            ((address ushr 8) and 0xFF).toByte(),
            (address and 0xFF).toByte(),
            0x00, 0x01,                                  // qty = 1 register
            0x02,                                        // byte count = 2
            ((value ushr 8) and 0xFF).toByte(),
            (value and 0xFF).toByte(),
        )
        return payload + ModbusCrc.compute(payload)
    }

    /**
     * Parsed Modbus RTU response. Only fn=0x03 / 0x10 reads are populated;
     * everything else returns null.
     */
    data class Response(
        val functionCode: Int,
        val payload: ByteArray,
    ) {
        /** For a register read, the raw 16-bit big-endian register values. */
        fun registers(): IntArray {
            require(functionCode == FN_READ || functionCode == FN_READ_IN) {
                "registers() only valid for a read response"
            }
            return IntArray(payload.size / 2) { i ->
                ((payload[i * 2].toInt() and 0xFF) shl 8) or
                 (payload[i * 2 + 1].toInt() and 0xFF)
            }
        }
    }

    /**
     * Try to parse [frame] as a Modbus response from our MPPT. Returns null
     * for anything that doesn't look right (wrong slave, bad CRC, partial
     * frame). Caller is expected to be using a reassembly buffer if the
     * GATT MTU forced multiple notifications.
     */
    fun parse(frame: ByteArray, slave: Int = SLAVE): Response? {
        if (frame.size < 5) return null                     // slave + fn + bc + crcLo + crcHi minimum
        if ((frame[0].toInt() and 0xFF) != slave) return null
        if (!ModbusCrc.verify(frame)) {
            AppLogger.w(TAG, "CRC error on ${frame.size}B frame — dropping (got ${
                "%02X %02X".format(frame[frame.size - 2].toInt() and 0xFF, frame[frame.size - 1].toInt() and 0xFF)
            })")
            return null
        }

        val fn = frame[1].toInt() and 0xFF
        return when (fn) {
            FN_READ, FN_READ_IN -> {
                val byteCount = frame[2].toInt() and 0xFF
                if (frame.size < 3 + byteCount + 2) return null
                Response(fn, frame.copyOfRange(3, 3 + byteCount))
            }
            FN_WRITE -> {
                // Echo: slave fn addrHi addrLo qtyHi qtyLo crcLo crcHi
                if (frame.size < 8) return null
                Response(fn, frame.copyOfRange(2, 6))       // addr + qty back
            }
            else -> null
        }
    }
}
