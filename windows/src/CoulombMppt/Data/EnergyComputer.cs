namespace CoulombMppt.Data;

// Port of the Android EnergyComputer — computes daily PV generation, battery
// I/O, and EA SUN inverter inference from a list of LiveSamples.
public static class EnergyComputer
{
    private const long MaxGapMs = 5 * 60_000L;     // skip gaps > 5 min
    private const double MinVoltageDelta = 0.02;    // filter noise on flat NMC curve

    public record DayEnergy(
        long   DayMs,
        double PvWh,
        double BattInWh,
        double BattOutWh,
        double MpptNetWh,
        double? EasunNetWh,
        int    SampleCount)
    {
        public double BattNetWh     => BattInWh - BattOutWh;
        public double EasunChargeWh => EasunNetWh is { } v ? Math.Max(0, v) : 0;
        public double EasunLoadWh   => EasunNetWh is { } v ? Math.Max(0, -v) : 0;
        public static DayEnergy Empty(long dayMs) => new(dayMs, 0, 0, 0, 0, null, 0);
    }

    public static DayEnergy ComputeDay(IReadOnlyList<LiveSample> rows, long dayMs, BatteryProfile? profile)
    {
        if (rows.Count < 2) return DayEnergy.Empty(dayMs);

        double pvWh = 0, battIn = 0, battOut = 0, mpptNet = 0;

        for (int i = 1; i < rows.Count; i++)
        {
            var r0 = rows[i - 1]; var r1 = rows[i];
            long dtMs = r1.TimestampMs - r0.TimestampMs;
            if (dtMs <= 0 || dtMs > MaxGapMs) continue;
            double dtH = dtMs / 3_600_000.0;

            pvWh += (r0.PvWatts + r1.PvWatts) / 2.0 * dtH;

            double avgV   = (r0.BatteryVoltage + r1.BatteryVoltage) / 2.0;
            double mpptA  = (r0.ChargeCurrent + r1.ChargeCurrent) / 2.0
                          - (r0.DischargeCurrent + r1.DischargeCurrent) / 2.0;
            mpptNet += mpptA * avgV * dtH;

            if (profile != null && Math.Abs(r1.BatteryVoltage - r0.BatteryVoltage) >= MinVoltageDelta)
            {
                double soc0 = profile.SocFromVoltage(r0.BatteryVoltage);
                double soc1 = profile.SocFromVoltage(r1.BatteryVoltage);
                double dWh  = profile.DeltaWh(soc0, soc1);
                if (dWh > 0) battIn  += dWh;
                else         battOut += -dWh;
            }
        }

        double? easun = profile != null ? (battIn - battOut) - mpptNet : null;

        return new DayEnergy(dayMs, Math.Max(0, pvWh), battIn, battOut, mpptNet, easun, rows.Count);
    }

    /// <summary>Live EA SUN net current estimate from a short window of recent samples.
    /// Returns null if the profile is missing or the window is too short (< 30 s).</summary>
    public static double? LiveEasunA(IReadOnlyList<LiveSample> recent, BatteryProfile? profile)
    {
        if (profile == null || recent.Count < 2) return null;
        long dtMs = recent[^1].TimestampMs - recent[0].TimestampMs;
        if (dtMs < 30_000) return null;
        double soc0 = profile.SocFromVoltage(recent[0].BatteryVoltage);
        double soc1 = profile.SocFromVoltage(recent[^1].BatteryVoltage);
        double netA  = profile.InferredNetCurrentA(soc0, soc1, dtMs);
        double mpptA = recent.Average(r => r.ChargeCurrent - r.DischargeCurrent);
        return netA - mpptA;
    }
}
