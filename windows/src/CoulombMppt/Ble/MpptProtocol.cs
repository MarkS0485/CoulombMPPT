namespace CoulombMppt.Ble;

// High-level commands the MPPT firmware understands. All addresses + register
// layouts come from BLE_PROTOCOL.md. Centralised here so the UI never has to
// know what offset chargeVoltageSetpoint lives at.
public static class MpptProtocol
{
    // --- Live telemetry ---
    // detail.vue polls read([0,1,0,16]) — start = 0x0001, qty = 16. Firmware
    // returns 10 registers (byteCount = 20).
    public const int LiveAddress  = 0x0001;
    public const int LiveQuantity = 0x0010;

    public static byte[] PollLive() => ModbusFrame.Read(LiveAddress, LiveQuantity);

    // --- Settings read-back ---
    // xiangxi.vue: read([16,1,0,15]) — start = 0x1001, qty = 15. Firmware
    // returns 9 registers (byteCount = 18).
    public const int SettingsAddress  = 0x1001;
    public const int SettingsQuantity = 0x000F;

    public static byte[] PollSettings() => ModbusFrame.Read(SettingsAddress, SettingsQuantity);

    // --- Writable setting addresses ---
    public static class Reg
    {
        public const int BatteryType            = 0x1001;
        public const int TimerHour              = 0x1002;
        public const int TimerMinute            = 0x1003;
        public const int ChargeVoltageSetpoint  = 0x1004;   // 充满电压
        public const int OutputMode             = 0x1005;
        public const int CutoffVoltageSetpoint  = 0x1006;   // 截止电压
        public const int ManualLoadOn           = 0x1007;   // 放电输出
        public const int VoltageMonitorMode     = 0x1008;
        public const int RecoveryVoltageSetpoint = 0x1009;  // 恢复电压
    }

    /// <summary>
    /// Build a write frame for any address in <see cref="Reg"/>. Caller is
    /// responsible for unit conversion — voltages are multiplied by 10 before
    /// being written.
    /// </summary>
    public static byte[] WriteRegister(int address, int value) =>
        ModbusFrame.WriteSingle(address, value);
}
