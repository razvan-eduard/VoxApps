package com.voxcommander.app.service

/**
 * Maps the user-facing Wake Word Sensitivity setting ("low"/"medium"/"high") to the
 * per-engine numeric threshold each detector expects.
 *
 * Pure logic (no Android deps) so it is unit-testable and the mapping lives in one place.
 * NOTE the engines disagree on direction:
 *  - OpenWakeWord & Vosk template DTW trigger on `score/sim >= threshold`, so a *lower*
 *    threshold = easier trigger = more sensitive.
 *  - Porcupine's own `sensitivity` param is the opposite: *higher* = more sensitive.
 */
object WakeWordSensitivity {

    const val LOW = "low"
    const val MEDIUM = "medium"
    const val HIGH = "high"

    /** OpenWakeWord ONNX score threshold. Lower = more sensitive. */
    fun openWakeWordThreshold(setting: String?): Float = when (setting) {
        HIGH -> 0.3f
        LOW -> 0.7f
        else -> 0.5f // medium / unknown
    }

    /** Porcupine sensitivity param (0..1). Higher = more sensitive. */
    fun porcupineSensitivity(setting: String?): Float = when (setting) {
        HIGH -> 0.7f
        LOW -> 0.3f
        else -> 0.5f // medium / unknown
    }

    /** Vosk template DTW similarity threshold. Lower = more sensitive. */
    fun voskTemplateThreshold(setting: String?): Float = when (setting) {
        HIGH -> 0.35f
        LOW -> 0.55f
        else -> 0.45f // medium / unknown
    }
}
