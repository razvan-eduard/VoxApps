package com.voxapps.design.effects

/**
 * Which particle effect (if any) an app draws around its "today" card — see
 * `com.voxapps.design.effects.particles.ParticleEffectPresets` for what each one actually looks like.
 * Persisted by name in each app's own settings (same convention as [com.voxapps.design.VoxDarkMode]'s
 * string constants).
 *
 * Each entry declares its own capabilities so the settings UI can render generically instead of
 * special-casing effect names: [usesColor] gates whether the primary color picker shows at all,
 * [usesGradient] gates the second-color/gradient toggle (only meaningful when [usesColor] is also
 * true). Adding a new preset later that, say, doesn't support a gradient is just a different
 * constructor call on a new entry — no settings-UI code changes.
 */
enum class TodayEffect(val usesColor: Boolean, val usesGradient: Boolean) {
    NONE(usesColor = false, usesGradient = false),
    FIRE(usesColor = true, usesGradient = true),
    GLOW(usesColor = true, usesGradient = true),
    WAVES(usesColor = true, usesGradient = true),

    /** Self-cycling hue — ignores any picked color entirely, so the settings UI hides the color
     *  pickers for it (driven by [usesColor], not a name check). */
    RAINBOW(usesColor = false, usesGradient = false),

    /** A single pulsing hue — no second color/gradient concept. */
    NEON_PULSE(usesColor = true, usesGradient = false)
}

/** Where [TodayEffect]'s particles are allowed to spawn relative to the wrapped element: [NONE]
 *  spawns none at all (lets a user keep an effect/color picked without it showing), [RING] spawns
 *  along the element's perimeter, [BACKGROUND] spawns within its bounds, and [FULL] spawns from both
 *  zones. Orthogonal to which [TodayEffect] is picked — the same 4 placements apply to every preset.
 *  Persisted the same way as [TodayEffect]. */
enum class TodayEffectStyle { NONE, RING, BACKGROUND, FULL }
