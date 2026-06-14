namespace CoulombMppt.Data;

// Resolved battery profile — drives all energy math in the Windows app.
// Mirrors the Android BatteryProfile. Created by MpptController.ResolvedBatteryProfile().
public sealed record BatteryProfile(
    double EmptyV,       // 0% SoC voltage
    double FullV,        // 100% SoC voltage
    double NominalV,     // midpoint voltage, used for Wh = Ah × V
    double CapacityAh)   // total pack capacity in Ah
{
    public double CapacityWh => NominalV * CapacityAh;

    public double SocFromVoltage(double v) =>
        Math.Clamp((v - EmptyV) / (FullV - EmptyV) * 100.0, 0, 100);

    /// <summary>Net energy change (Wh, signed) for a SoC swing.
    /// Positive = energy stored; negative = drawn.</summary>
    public double DeltaWh(double fromSocPct, double toSocPct) =>
        CapacityWh * (toSocPct - fromSocPct) / 100.0;

    /// <summary>Average net battery current (A, signed) inferred from a
    /// voltage/SoC swing over a time window. Positive = charging.</summary>
    public double InferredNetCurrentA(double fromSocPct, double toSocPct, long elapsedMs)
    {
        if (elapsedMs <= 0) return 0;
        double deltaAh = CapacityAh * (toSocPct - fromSocPct) / 100.0;
        return deltaAh / (elapsedMs / 3_600_000.0);
    }
}
