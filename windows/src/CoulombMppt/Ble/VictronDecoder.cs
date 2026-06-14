using System.Security.Cryptography;

namespace CoulombMppt.Ble;

// Decoder for Victron's "Instant Readout" BLE manufacturer advertisement
// (company id 0x02E1). Broadcast continuously — decoded passively, no
// connection. Mirrors the Android VictronDecoder; layout/scaling follow
// Victron's "Extra Manufacturer Data" spec and keshavdv/victron-ble.
//
// The bytes here are the manufacturer payload for company 0x02E1 with the
// 2-byte company id already stripped (WinRT's BluetoothLEManufacturerData.Data
// is exactly that), so:
//   [0..1] model id (LE)
//   [2]    record type (0x01 = solar charger)
//   [3..4] nonce (LE) — the AES-CTR IV
//   [5]    key-check byte (== key[0])
//   [6..]  AES-CTR ciphertext (single block; records are ≤ 16 bytes)
public static class VictronDecoder
{
    public const ushort CompanyId = 0x02E1;
    private const int RecordSolarCharger = 0x01;

    public sealed record SolarReading(
        int DeviceState,
        int ChargerError,
        double? BatteryVoltage,   // V
        double? BatteryCurrent,   // A, signed (+ charging)
        double? YieldTodayKwh,    // kWh
        int? PvPowerW,            // W
        double? LoadCurrent);     // A

    public static bool IsSolarCharger(byte[]? payload) =>
        payload is { Length: >= 3 } && payload[2] == RecordSolarCharger;

    /// <summary>
    /// Decode a solar-charger advertisement with the per-device <paramref name="keyHex"/>
    /// (32 hex chars). Returns null if the key is missing/invalid, the record
    /// isn't a solar charger, or the key-check byte mismatches (stale key).
    /// </summary>
    public static SolarReading? DecodeSolar(byte[] payload, string? keyHex)
    {
        var key = HexToBytes(keyHex);
        if (key is not { Length: 16 }) return null;
        if (payload.Length < 8) return null;
        if (payload[2] != RecordSolarCharger) return null;
        if (payload[5] != key[0]) return null;   // wrong/rotated key

        int nonce = payload[3] | (payload[4] << 8);
        var cipher = payload[6..];
        var plain = AesCtrSingleBlock(key, nonce, cipher);
        if (plain == null || plain.Length < 12) return null;

        double? S16(int i, int na) { int v = S16Raw(plain, i); return (v & 0xFFFF) == na ? null : v; }
        int? U16(int i, int na) { int v = U16Raw(plain, i); return v == na ? null : v; }

        int loadRaw = plain[10] | ((plain[11] & 0x01) << 8);

        return new SolarReading(
            DeviceState:    plain[0],
            ChargerError:   plain[1],
            BatteryVoltage: S16(2, 0x7FFF) is { } bv ? bv * 0.01 : null,
            BatteryCurrent: S16(4, 0x7FFF) is { } bi ? bi * 0.1 : null,
            YieldTodayKwh:  U16(6, 0xFFFF) is { } y ? y * 0.01 : null,
            PvPowerW:       U16(8, 0xFFFF),
            LoadCurrent:    loadRaw == 0x1FF ? null : loadRaw * 0.1);
    }

    private static byte[]? AesCtrSingleBlock(byte[] key, int nonce, byte[] cipher)
    {
        try
        {
            var counter = new byte[16];
            counter[0] = (byte)(nonce & 0xFF);
            counter[1] = (byte)((nonce >> 8) & 0xFF);
            using var aes = Aes.Create();
            aes.Mode = CipherMode.ECB;
            aes.Padding = PaddingMode.None;
            aes.Key = key;
            using var enc = aes.CreateEncryptor();
            var keystream = enc.TransformFinalBlock(counter, 0, 16);   // single keystream block
            int n = Math.Min(cipher.Length, keystream.Length);
            var outp = new byte[n];
            for (int i = 0; i < n; i++) outp[i] = (byte)(cipher[i] ^ keystream[i]);
            return outp;
        }
        catch { return null; }
    }

    private static int U16Raw(byte[] b, int i) => b[i] | (b[i + 1] << 8);
    private static int S16Raw(byte[] b, int i) => (short)U16Raw(b, i);

    /// <summary>Parse 32 hex chars (spaces/colons ignored) → 16 bytes, or null.</summary>
    public static byte[]? HexToBytes(string? hex)
    {
        if (hex == null) return null;
        var clean = new string(hex.Where(char.IsLetterOrDigit).ToArray());
        if (clean.Length % 2 != 0) return null;
        try
        {
            var b = new byte[clean.Length / 2];
            for (int i = 0; i < b.Length; i++)
                b[i] = Convert.ToByte(clean.Substring(i * 2, 2), 16);
            return b;
        }
        catch { return null; }
    }

    public static bool IsValidKey(string? hex) => HexToBytes(hex)?.Length == 16;
}
