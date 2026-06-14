namespace CoulombMppt.Ble;

// Standard Modbus RTU CRC-16. The MPPT firmware uses the exact same routine
// the vendor JS app's cmdParse.js implements (poly 0xA001, init 0xFFFF, low
// byte out first). Ported verbatim from the Android client's ModbusCrc.kt so
// we can both sign outgoing frames and validate incoming ones.
public static class ModbusCrc
{
    /// <summary>Returns the two CRC bytes in wire order — [lo, hi].</summary>
    public static byte[] Compute(byte[] data, int length)
    {
        int crc = 0xFFFF;
        for (int i = 0; i < length; i++)
        {
            crc ^= data[i] & 0xFF;
            for (int b = 0; b < 8; b++)
            {
                crc = (crc & 0x0001) != 0
                    ? (crc >> 1) ^ 0xA001
                    : (crc >> 1);
            }
        }
        byte lo = (byte)(crc & 0x00FF);
        byte hi = (byte)((crc >> 8) & 0x00FF);
        return new[] { lo, hi };
    }

    public static byte[] Compute(byte[] data) => Compute(data, data.Length);

    /// <summary>
    /// Validate that the trailing two bytes of <paramref name="frame"/> match a
    /// fresh CRC computed over everything before them. True on a valid frame.
    /// </summary>
    public static bool Verify(byte[] frame)
    {
        if (frame.Length < 4) return false;          // slave + fn + crcLo + crcHi
        var crc = Compute(frame, frame.Length - 2);
        return crc[0] == frame[^2] && crc[1] == frame[^1];
    }
}
