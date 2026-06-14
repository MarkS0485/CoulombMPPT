package app.coulombmppt.data.model

import kotlinx.serialization.Serializable

// Just enough to remember a previously-paired device across app restarts.
@Serializable
data class PairedDevice(
    val macAddress: String,
    val displayName: String? = null,
)
