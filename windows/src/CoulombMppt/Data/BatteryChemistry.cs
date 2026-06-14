namespace CoulombMppt.Data;

// User-facing battery chemistry classification, attached to an MpptController.
// Distinct from MpptSettings.BatteryType (the controller's own firmware enum):
//  • This one is what the user told us their pack actually is.
//  • That one is what the controller is configured to behave as.
// They should agree but the app doesn't assume they do. Ported from the
// Android client's BatteryChemistry.kt.
public enum BatteryChemistry
{
    Unknown,
    LeadAcid,
    Gel,
    Flooded,
    LiFePO4,
    NMC,
    NCA,
    LTO,
    Custom,
}

public static class BatteryChemistryExtensions
{
    public static string DisplayName(this BatteryChemistry c) => c switch
    {
        BatteryChemistry.LeadAcid => "Lead-acid (sealed/AGM)",
        BatteryChemistry.Gel      => "Gel lead-acid",
        BatteryChemistry.Flooded  => "Flooded lead-acid",
        BatteryChemistry.LiFePO4  => "LiFePO4 (lithium iron)",
        BatteryChemistry.NMC      => "Li-ion NMC",
        BatteryChemistry.NCA      => "Li-ion NCA",
        BatteryChemistry.LTO      => "Lithium titanate",
        BatteryChemistry.Custom   => "Custom / other",
        _                         => "Unknown",
    };

    public static double? TypicalNominalV(this BatteryChemistry c) => c switch
    {
        BatteryChemistry.LeadAcid => 12.0,
        BatteryChemistry.Gel      => 12.0,
        BatteryChemistry.Flooded  => 12.0,
        BatteryChemistry.LiFePO4  => 12.8,
        BatteryChemistry.NMC      => 11.1,
        BatteryChemistry.NCA      => 11.1,
        BatteryChemistry.LTO      => 9.0,
        _                         => null,
    };
}
