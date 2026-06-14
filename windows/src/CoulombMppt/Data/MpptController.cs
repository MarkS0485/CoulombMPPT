namespace CoulombMppt.Data;

// One paired MPPT controller. Persisted by ControllerStore. Ported from the
// Android client's PairedController.kt — the site/icon/accent fields give each
// unit its own visual identity in the dashboard list, and the cached + user
// pack-profile fields feed SoC and the alert thresholds.
public sealed record MpptController(
    string Mac,
    string? DisplayName = null,
    string SiteLabel = "Domestic solar · charge controller",
    string IconKey = MpptController.IconHome,
    uint AccentArgb = MpptController.AccentCopperArgb,
    long PairedAtMs = 0,
    // Cached controller setpoints (read from the unit) — calibration anchors.
    double? CachedFullV = null,
    double? CachedRecoverV = null,
    double? CachedEmptyV = null,
    // User-supplied pack spec — what the human said the battery actually is.
    BatteryChemistry PackChemistry = BatteryChemistry.Unknown,
    double? PackNominalV = null,
    double? PackUserFullV = null,
    double? PackUserEmptyV = null,
    double? PackCapacityKwh = null,
    // Which driver speaks to this device. Defaults to the original controller so
    // controllers.json from older builds (which lacks these) deserialises right.
    DeviceType DeviceType = DeviceType.GenericModbusNus,
    // Victron Instant Readout per-device key (32 hex chars); null otherwise.
    string? VictronKey = null,
    // Amp-hour capacity — the Ah input the energy math needs.
    // Null until entered via App Settings.
    double? PackCapacityAh = null)
{
    public bool HasBatteryProfile =>
        CachedFullV is { } f && CachedEmptyV is { } e && f > e + 0.1;

    public bool HasUserPackProfile =>
        PackChemistry != BatteryChemistry.Unknown ||
        PackNominalV != null || PackUserFullV != null ||
        PackUserEmptyV != null || PackCapacityKwh != null;

    /// <summary>Linear interp, run from cache so the dashboard has calibration
    /// before the next settings read lands.</summary>
    public double? ComputeSocFromCache(double batteryVoltage)
    {
        if (CachedEmptyV is not { } low) return null;
        if (CachedFullV is not { } high) return null;
        if (high <= low + 0.1) return null;
        return Math.Clamp((batteryVoltage - low) / (high - low) * 100.0, 0.0, 100.0);
    }

    public string Label => string.IsNullOrWhiteSpace(DisplayName) ? Mac : DisplayName!;

    /// <summary>
    /// Resolved battery profile for energy calculations. Uses user pack spec
    /// with controller setpoints as voltage fallback. Returns null when either
    /// the voltage bounds or Ah capacity are unknown.
    /// </summary>
    public BatteryProfile? ResolvedBatteryProfile()
    {
        double? empty = PackUserEmptyV   ?? CachedEmptyV;
        double? full  = PackUserFullV    ?? CachedFullV;
        if (empty == null || full == null || full <= empty + 0.1) return null;
        double nominal = PackNominalV ?? ((empty.Value + full.Value) / 2.0);
        if (PackCapacityAh is not { } ah || ah <= 0) return null;
        return new BatteryProfile(empty.Value, full.Value, nominal, ah);
    }

    public const string IconHome = "home";
    public const string IconBoat = "boat";
    public const string IconFactory = "factory";
    public const string IconCabin = "cabin";

    public const uint AccentCopperArgb = 0xFFB45309;  // domestic solar identity
    public const uint AccentTealArgb   = 0xFF0F766E;
    public const uint AccentVioletArgb = 0xFF7C3AED;
}
