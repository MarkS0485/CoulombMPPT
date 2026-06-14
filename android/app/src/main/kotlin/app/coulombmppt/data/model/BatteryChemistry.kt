package app.coulombmppt.data.model

import kotlinx.serialization.Serializable

// User-facing battery chemistry classification, attached to a PairedController.
// Distinct from MpptSettings.BatteryType (the controller's own firmware enum
// — codes 0-4, semantics TBD on hardware). The two are kept apart because:
//
//  • This one is what the user told us their pack actually is.
//  • That one is what the controller is configured to behave as.
//
// They should agree but the app shouldn't assume they do. The UI on the
// Battery tab shows both alongside each other.
@Serializable
enum class BatteryChemistry(
    val displayName: String,
    val typicalNominalV: Double?,
) {
    Unknown   ("Unknown",                  null),
    LeadAcid  ("Lead-acid (sealed/AGM)",   12.0),
    Gel       ("Gel lead-acid",            12.0),
    Flooded   ("Flooded lead-acid",        12.0),
    LiFePO4   ("LiFePO4 (lithium iron)",   12.8),
    NMC       ("Li-ion NMC",               11.1),
    NCA       ("Li-ion NCA",               11.1),
    LTO       ("Lithium titanate",          9.0),
    Custom    ("Custom / other",            null);

    companion object {
        fun fromDisplay(name: String): BatteryChemistry =
            entries.firstOrNull { it.displayName == name } ?: Unknown
    }
}
