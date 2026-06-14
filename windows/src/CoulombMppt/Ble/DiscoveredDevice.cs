namespace CoulombMppt.Ble;

// A controller seen during a BLE scan. IsKnownMppt is a best-effort guess from
// the advertised name / service UUIDs — a null/false here is not a definitive
// "not an MPPT" signal because the NUS service isn't always advertised.
public sealed record DiscoveredDevice(
    string Mac,
    string? Name,
    int Rssi,
    bool IsKnownMppt,
    long LastSeenAtMs,
    // Raw Victron manufacturer payload (company 0x02E1) if this advert carries
    // one — lets the pairing UI flag it and route to the Victron driver.
    byte[]? VictronData = null)
{
    public string Display => string.IsNullOrEmpty(Name) ? Mac : $"{Name} ({Mac})";

    public bool IsVictron => VictronDecoder.IsSolarCharger(VictronData);
}
