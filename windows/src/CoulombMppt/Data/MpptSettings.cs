namespace CoulombMppt.Data;

// Snapshot of the controller's writable settings (BLE_PROTOCOL.md §3.2).
// Every voltage field is reg/10. Ported from the Android client's
// MpptSettings.kt.
public sealed record MpptSettings(
    int    BatteryType,              // enum code
    int    TimerHours,
    int    TimerMinutes,
    double ChargeVoltageSetpoint,    // V — "full" / boost target
    int    OutputMode,               // enum code
    double CutoffVoltageSetpoint,    // V — low-disconnect / "empty"
    bool   ManualLoadOn,
    int    VoltageMonitorMode,       // enum code
    double RecoveryVoltageSetpoint)  // V — load reconnect
{
    /// <summary>
    /// Linear-interpolation SoC from the controller's own calibration. The
    /// cutoff and charge setpoints are the controller's idea of "0%" and
    /// "100%". Returns [0,100], or null if the calibration is degenerate.
    /// </summary>
    public double? ComputeSoc(double batteryVoltage)
    {
        double low = CutoffVoltageSetpoint, high = ChargeVoltageSetpoint;
        if (high <= low + 0.1) return null;                  // degenerate calibration
        double pct = (batteryVoltage - low) / (high - low) * 100.0;
        return Math.Clamp(pct, 0.0, 100.0);
    }

    public BatteryType BatteryTypeEnum => BatteryTypeExtensions.OfCode(BatteryType);
    public OutputMode  OutputModeEnum  => OutputModeExtensions.OfCode(OutputMode);
}

// Controller's own firmware battery-type enum (codes 0-4). Distinct from
// BatteryChemistry, which is what the user told us their pack actually is.
public enum BatteryType
{
    Unknown     = 0,
    SealedLead  = 1,
    GelLead     = 2,
    FloodedLead = 3,
    Lithium     = 4,
}

public static class BatteryTypeExtensions
{
    public static BatteryType OfCode(int c) =>
        Enum.IsDefined(typeof(BatteryType), c) ? (BatteryType)c : BatteryType.Unknown;

    public static string DisplayName(this BatteryType t) => t switch
    {
        BatteryType.SealedLead  => "Sealed lead-acid",
        BatteryType.GelLead     => "Gel lead-acid",
        BatteryType.FloodedLead => "Flooded lead-acid",
        BatteryType.Lithium     => "Lithium (LiFePO4)",
        _                       => "Unknown",
    };
}

public enum OutputMode
{
    Manual  = 0,
    Auto    = 1,
    Timer   = 2,
    Unknown = 255,
}

public static class OutputModeExtensions
{
    public static OutputMode OfCode(int c) =>
        Enum.IsDefined(typeof(OutputMode), c) ? (OutputMode)c : OutputMode.Unknown;

    public static string DisplayName(this OutputMode m) => m switch
    {
        OutputMode.Manual => "Manual",
        OutputMode.Auto   => "Auto / dusk-to-dawn",
        OutputMode.Timer  => "Timer",
        _                 => "Unknown",
    };
}
