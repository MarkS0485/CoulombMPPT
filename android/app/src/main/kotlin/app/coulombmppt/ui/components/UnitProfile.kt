package app.coulombmppt.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cabin
import androidx.compose.material.icons.filled.DirectionsBoat
import androidx.compose.material.icons.filled.Factory
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import app.coulombmppt.data.model.PairedController

// Resolves a paired controller's iconKey + accentArgb into Compose types.
// Lives next to BrandTopBar / SoCRing so any UI that needs to show a
// controller's identity (the controllers-home card, the Unit Detail top
// bar, the Info tab) goes through one helper instead of switching on the
// iconKey string in three places.
//
// Mirrors `UnitProfile` from the sister coulombmonitor app, but the labels
// and accent come from the PairedController record itself — the user can
// rename and re-style each MPPT they pair, no hard-coded unit table.
data class UnitProfile(
    val title:     String,
    val siteLabel: String,
    val icon:      ImageVector,
    val accent:    Color,
) {
    companion object {
        fun of(controller: PairedController, fallbackTitle: String = "Unit"): UnitProfile {
            val icon = when (controller.iconKey) {
                PairedController.ICON_HOME    -> Icons.Filled.Home
                PairedController.ICON_BOAT    -> Icons.Filled.DirectionsBoat
                PairedController.ICON_FACTORY -> Icons.Filled.Factory
                PairedController.ICON_CABIN   -> Icons.Filled.Cabin
                else                          -> Icons.Filled.QuestionMark
            }
            return UnitProfile(
                title     = controller.displayName ?: fallbackTitle,
                siteLabel = controller.siteLabel,
                icon      = icon,
                accent    = Color(controller.accentArgb.toInt()),
            )
        }
    }
}
