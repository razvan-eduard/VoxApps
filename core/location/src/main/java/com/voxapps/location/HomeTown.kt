package com.voxapps.location

/**
 * The user's manually-set fallback location ("Home town" in the settings card). Never written by
 * [VoxLocationResolver] itself — only a live GPS fix or fresh cache is; this value only changes
 * when the user explicitly edits it via [com.voxapps.location.ui.VoxLocationSettingsCard].
 */
data class HomeTown(val lat: Double, val lon: Double)
