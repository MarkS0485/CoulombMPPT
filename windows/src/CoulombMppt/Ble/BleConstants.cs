namespace CoulombMppt.Ble;

// Nordic UART Service UUIDs the MPPT controller exposes. The original vendor
// app writes to 0x0003 (the notify char) instead of the standard 0x0002 write
// char — we pick characteristics by property at runtime and fall back to UUID.
public static class BleConstants
{
    public static readonly Guid NusService = new("6E400001-B5A3-F393-E0A9-E50E24DCCA9E");
    public static readonly Guid NusCharRx  = new("6E400002-B5A3-F393-E0A9-E50E24DCCA9E"); // phone → device
    public static readonly Guid NusCharTx  = new("6E400003-B5A3-F393-E0A9-E50E24DCCA9E"); // device → phone (notify)

    // Standard Client Characteristic Configuration Descriptor.
    public static readonly Guid Cccd = new("00002902-0000-1000-8000-00805f9b34fb");
}
