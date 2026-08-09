package com.voxapps.commander.domain.engine

import android.content.Context
import com.voxapps.logging.Logger

/**
 * The one place in the app where a TTS engine key is connected to a class.
 *
 * This is the irreducible part of making the registry the source of truth: `models.json` can say
 * what an engine is, what it downloads and where its artefact lands, but something has to name the
 * Kotlin class that runs it — and it must be this table rather than a name in the JSON, because the
 * schema can be served from a `modelRepoBaseUrl` the user controls. JSON selects among the
 * implementations compiled in; it cannot introduce one.
 *
 * Keyed by the `models.json` engine key itself, not by an invented indirection: two engines that
 * share an implementation simply write two entries pointing at the same constructor.
 *
 * This replaces `TtsEngineType`, an enum whose only real job was `when (type) -> Engine()` — a
 * dispatch table wearing a type's clothes, and one that had to be kept in sync with the registry key
 * by hand. It was not: it carried `"piper"` while settings stored `"piper_tts"`, so selecting Piper
 * silently did nothing. The legacy spelling now normalises with every other engine alias in
 * `SettingsRepositoryImpl.normalizeEngineKey`.
 */
object TtsEngines {

    private val factories: Map<String, (Context) -> ITtsEngine> = mapOf(
        AndroidTtsEngine.ENGINE_KEY to { ctx: Context -> AndroidTtsEngine(ctx) },
        PiperTtsEngine.ENGINE_KEY to { _: Context -> PiperTtsEngine() },
    )

    /** Engine keys with a compiled implementation. */
    val supportedKeys: Set<String> get() = factories.keys

    /**
     * Builds the engine for [engineKey], or null if nothing implements it — an engine declared in a
     * schema this build does not know how to run. Null rather than a default, so the caller decides
     * what to do about it instead of silently getting something else than it asked for.
     */
    fun create(engineKey: String, context: Context): ITtsEngine? {
        val factory = factories[engineKey]
        if (factory == null) {
            Logger.log("No TTS implementation for engine key '$engineKey'", TAG)
            return null
        }
        return factory(context)
    }

    private const val TAG = "TtsEngines"
}
