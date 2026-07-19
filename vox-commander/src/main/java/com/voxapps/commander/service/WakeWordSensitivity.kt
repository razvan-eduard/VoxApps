package com.voxapps.commander.service

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
        HIGH -> 0.25f
        LOW -> 0.7f
        else -> 0.5f // medium / unknown (Restored to 0.5 after R8 stabilization)
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

    /**
     * OpenWakeWord RMS silence gate: buffers whose RMS energy falls below this floor skip ONNX
     * inference entirely (the dominant battery cost of always-on wake word — see forked
     * WakeWordEngine.start()). Lower = catches quieter speech but saves less; higher = cuts more
     * aggressively (more battery saved) at the risk of clipping a soft utterance.
     */
    fun openWakeWordRmsGate(setting: String?): Float = when (setting) {
        HIGH -> 0.01f
        LOW -> 0.04f
        else -> 0.025f // medium / unknown (Hardened to 0.025 to block table/mousepad friction)
    }

    /**
     * Margin above the live, rolling ambient-noise-floor estimate (see [com.voxapps.audio
     * .AdaptiveNoiseGate]) a frame's RMS must clear before either wake-word engine treats it as
     * signal worth running full inference/decode on. This is the same underlying question as the
     * gates above ("how permissive should triggering be"), just answered for the pre-inference
     * gate stage instead of the post-inference score/similarity stage — reused rather than a
     * separate "noise floor" setting so the user only ever tunes one knob. Lower = gate opens
     * closer to ambient level (more responsive, less battery saved in noise); higher = needs to
     * stand out further above ambient before inference even runs (more battery saved, more likely
     * to miss a soft utterance in a loud room).
     */
    fun noiseGateMargin(setting: String?): Float = when (setting) {
        HIGH -> 1.5f
        LOW -> 3.0f
        else -> 2.0f // medium / unknown
    }
}
