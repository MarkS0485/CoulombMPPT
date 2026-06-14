namespace CoulombMppt.Ble;

// One raw BLE frame seen on the wire, surfaced to the Diagnostics page so the
// Modbus traffic can be inspected live. Tx = true means PC → controller.
public sealed record RawFrame(bool Tx, byte[] Bytes, long TimestampMs)
{
    public string Hex => Convert.ToHexString(Bytes);
}
