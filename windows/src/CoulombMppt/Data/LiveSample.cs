namespace CoulombMppt.Data;

// One downsampled telemetry row, written by HistoryRecorder at the configured
// cadence (AppSettings.HistoryEverySec). Mirrors the Android client's
// LiveSampleRow but is persisted as NDJSON rather than Room/SQLite — keeps the
// Windows build free of a database dependency while still giving the charts a
// queryable time-series.
public sealed record LiveSample(
    long   TimestampMs,
    double BatteryVoltage,    // V
    double ChargeCurrent,     // A
    double DischargeCurrent,  // A
    double PvWatts,
    double LoadWatts,
    double TemperatureC,
    double SocPercent);
