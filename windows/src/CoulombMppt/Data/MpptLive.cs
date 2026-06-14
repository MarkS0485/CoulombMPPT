namespace CoulombMppt.Data;

// One snapshot of live telemetry as decoded from the firmware's
// read(0x0001, 16) response. Field names follow BLE_PROTOCOL.md §7.1 rather
// than the original Chinese variable names. Ported from the Android client's
// MpptLive.kt.
public sealed record MpptLive(
    long   TimestampMs,
    double BatteryVoltage,     // V
    double ChargeCurrent,      // A — PV → battery (≥ 0)
    double DischargeCurrent,   // A — battery → load (≥ 0)
    double TemperatureC,       // controller temp
    int    SolarStatusRaw,
    int    WorkStatusRaw,
    int    PowerStatusRaw,
    // Lifetime accumulator from registers 8/9, formula (1000 × hi + lo) / 10.
    double TotalAccumulatedAh,
    ChargerState ChargerState,
    double SocEstimate)        // %, lookup-curve from BatteryVoltage
{
    /// <summary>Battery-side instantaneous power. Positive when charging.</summary>
    public double BatteryWatts => BatteryVoltage * (ChargeCurrent - DischargeCurrent);

    /// <summary>Load-side instantaneous power, always ≥ 0.</summary>
    public double LoadWatts => BatteryVoltage * DischargeCurrent;

    /// <summary>
    /// PV-side power — best effort. The firmware doesn't expose PV V/I, so we
    /// approximate by V_battery × I_charge (= battery-input power, i.e. PV
    /// minus the small MPPT-conversion losses).
    /// </summary>
    public double ApproxPvWatts => BatteryVoltage * ChargeCurrent;

    // Crude V→SoC table for a 12 V lead-acid battery. Fallback when we don't
    // have voltage calibration from the controller's own setpoints.
    public static double EstimateSoc(double vBat) => vBat switch
    {
        >= 12.9 => 100.0,
        >= 12.6 => 80.0,
        >= 12.3 => 60.0,
        >= 12.0 => 40.0,
        >= 11.7 => 20.0,
        _       => 0.0,
    };
}
