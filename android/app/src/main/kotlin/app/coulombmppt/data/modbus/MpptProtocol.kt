package app.coulombmppt.data.modbus

// High-level commands the MPPT firmware understands. All addresses + register
// layouts come from BLE_PROTOCOL.md. Centralised here so the UI never has to
// know what offset `chargeVoltageSetpoint` lives at.
object MpptProtocol {

    // --- Live telemetry ---
    // detail.vue polls read([0,1,0,16]) — start = 0x0001, qty = 16. Firmware
    // returns 10 registers (byteCount = 20).
    const val LIVE_ADDRESS: Int  = 0x0001
    const val LIVE_QUANTITY: Int = 0x0010

    fun pollLive(): ByteArray = ModbusFrame.read(LIVE_ADDRESS, LIVE_QUANTITY)

    // --- Settings read-back ---
    // xiangxi.vue: read([16,1,0,15]) — start = 0x1001, qty = 15. Firmware
    // returns 9 registers.
    const val SETTINGS_ADDRESS: Int  = 0x1001
    const val SETTINGS_QUANTITY: Int = 0x000F

    fun pollSettings(): ByteArray = ModbusFrame.read(SETTINGS_ADDRESS, SETTINGS_QUANTITY)

    // --- Writable setting addresses ---
    object Reg {
        const val BATTERY_TYPE             = 0x1001
        const val TIMER_HOUR               = 0x1002
        const val TIMER_MINUTE             = 0x1003
        const val CHARGE_VOLTAGE_SETPOINT  = 0x1004      // "cm_voltage" / 充满电压
        const val OUTPUT_MODE              = 0x1005
        const val CUTOFF_VOLTAGE_SETPOINT  = 0x1006      // "jz_voltage" / 截止电压
        const val MANUAL_LOAD_ON           = 0x1007      // "fz_output"  / 放电输出
        const val VOLTAGE_MONITOR_MODE     = 0x1008
        const val RECOVERY_VOLTAGE_SETPOINT = 0x1009     // "hf_out_voltage" / 恢复电压
    }

    /**
     * Build a write frame for any of the addresses in [Reg]. Caller is
     * responsible for any unit conversion — voltages, per BLE_PROTOCOL.md
     * §3.2, are multiplied by `convert` (10 in every case we have evidence
     * for) before being written.
     */
    fun writeRegister(address: Int, value: Int): ByteArray =
        ModbusFrame.writeSingle(address, value)
}
