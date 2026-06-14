namespace CoulombMppt.Data;

// Synthesised top-level charger state. We don't yet have ground truth for the
// firmware's solar_status / work_status / power_status enum codes, so we derive
// the state heuristically from the numeric registers — ported verbatim from
// the Android client's ChargerState.fromRegisters. Swap for a direct mapping
// once the firmware enum codes are confirmed on hardware.
public enum ChargerState
{
    Bulk,      // charging hard
    Boost,     // absorption phase
    Floating,  // top-up
    Idle,      // no PV input, no load
    LoadOff,   // load disconnected (low-voltage cutoff or manual)
    Fault,
    Unknown,
}

public static class ChargerStateLogic
{
    public static ChargerState FromRegisters(
        int solarStatus,
        int workStatus,
        int powerStatus,
        double chargeCurrent,
        double dischargeCurrent,
        double batteryVoltage)
    {
        if (workStatus >= 0x10 || solarStatus >= 0x10) return ChargerState.Fault;
        if (chargeCurrent > 0.1 && batteryVoltage > 14.0) return ChargerState.Floating;
        if (chargeCurrent > 0.1) return ChargerState.Bulk;
        if (dischargeCurrent > 0.05) return ChargerState.Idle;
        if (powerStatus == 0) return ChargerState.LoadOff;
        return ChargerState.Idle;
    }

    public static string Label(this ChargerState s) => s switch
    {
        ChargerState.Bulk    => "Bulk charge",
        ChargerState.Boost   => "Absorption",
        ChargerState.Floating => "Float",
        ChargerState.Idle    => "Idle",
        ChargerState.LoadOff => "Load off",
        ChargerState.Fault   => "Fault",
        _                    => "Unknown",
    };
}
