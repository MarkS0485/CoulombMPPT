using CoulombMppt.Services;

namespace CoulombMppt.Ble;

// Tiny helper for the two Modbus function codes the MPPT firmware speaks
// (0x03 Read Holding Registers, 0x10 Write Multiple Registers). Ported from
// the Android client's ModbusFrame.kt. No general-purpose Modbus library —
// one file is easier to audit than a third-party black box.
public static class ModbusFrame
{
    public const int Slave   = 0x01;   // fixed unit address
    public const int FnRead  = 0x03;   // Read Holding Registers
    public const int FnWrite = 0x10;   // Write Multiple Registers

    /// <summary>
    /// Build a "Read Holding Registers" request frame.
    /// Wire layout: 01 03 addrHi addrLo qtyHi qtyLo crcLo crcHi.
    /// </summary>
    public static byte[] Read(int address, int quantity)
    {
        var payload = new byte[]
        {
            (byte)Slave,
            (byte)FnRead,
            (byte)((address >> 8) & 0xFF),
            (byte)(address & 0xFF),
            (byte)((quantity >> 8) & 0xFF),
            (byte)(quantity & 0xFF),
        };
        return Concat(payload, ModbusCrc.Compute(payload));
    }

    /// <summary>
    /// Build a "Write Multiple Registers" frame for a single 16-bit value.
    /// Used for every settings write.
    /// </summary>
    public static byte[] WriteSingle(int address, int value)
    {
        var payload = new byte[]
        {
            (byte)Slave,
            (byte)FnWrite,
            (byte)((address >> 8) & 0xFF),
            (byte)(address & 0xFF),
            0x00, 0x01,                          // qty = 1 register
            0x02,                                // byte count = 2
            (byte)((value >> 8) & 0xFF),
            (byte)(value & 0xFF),
        };
        return Concat(payload, ModbusCrc.Compute(payload));
    }

    /// <summary>Parsed Modbus RTU response. Only fn=0x03 / 0x10 are populated.</summary>
    public sealed record Response(int FunctionCode, byte[] Payload)
    {
        /// <summary>For fn=0x03, the raw 16-bit big-endian register values.</summary>
        public int[] Registers()
        {
            if (FunctionCode != FnRead)
                throw new InvalidOperationException("Registers() only valid for fn=0x03");
            var regs = new int[Payload.Length / 2];
            for (int i = 0; i < regs.Length; i++)
                regs[i] = ((Payload[i * 2] & 0xFF) << 8) | (Payload[i * 2 + 1] & 0xFF);
            return regs;
        }
    }

    /// <summary>
    /// Try to parse <paramref name="frame"/> as a Modbus response from our
    /// MPPT. Returns null for anything that doesn't look right (wrong slave,
    /// bad CRC, partial frame). Caller is expected to use a reassembly buffer
    /// if the GATT MTU forced multiple notifications.
    /// </summary>
    public static Response? Parse(byte[] frame)
    {
        if (frame.Length < 5) return null;               // slave + fn + bc + crc minimum
        if ((frame[0] & 0xFF) != Slave) return null;
        if (!ModbusCrc.Verify(frame))
        {
            Log.W("ModbusFrame", $"CRC error on {frame.Length}B frame — dropping " +
                  $"(got {frame[frame.Length - 2]:X2} {frame[frame.Length - 1]:X2})");
            return null;
        }

        int fn = frame[1] & 0xFF;
        switch (fn)
        {
            case FnRead:
                int byteCount = frame[2] & 0xFF;
                if (frame.Length < 3 + byteCount + 2) return null;
                return new Response(fn, frame[3..(3 + byteCount)]);
            case FnWrite:
                if (frame.Length < 8) return null;        // slave fn addr×2 qty×2 crc×2
                return new Response(fn, frame[2..6]);      // addr + qty echoed back
            default:
                return null;
        }
    }

    private static byte[] Concat(byte[] a, byte[] b)
    {
        var r = new byte[a.Length + b.Length];
        Buffer.BlockCopy(a, 0, r, 0, a.Length);
        Buffer.BlockCopy(b, 0, r, a.Length, b.Length);
        return r;
    }
}
