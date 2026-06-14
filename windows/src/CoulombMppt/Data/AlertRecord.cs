namespace CoulombMppt.Data;

// One alert event, raised by AlertEngine when a metric crosses a configured
// threshold (hysteresis is enforced in the engine, not here). Persisted by
// AlertStore. Ported from the Android client's AlertRow.
public sealed record AlertRecord(
    long    Id,
    string  ControllerMac,
    long    TimestampMs,
    string  Severity,        // "CRIT" or "WARN" — string so new severities don't break the file
    string  Kind,            // stable identifier, see AlertEngine.AlertKind
    double  Observed,        // the measurement that triggered the alert
    double  Threshold,       // the threshold that was crossed
    string  Message,
    long?   DismissedMs = null)
{
    public bool IsCritical => Severity == "CRIT";
    public bool IsActive   => DismissedMs == null;
}
