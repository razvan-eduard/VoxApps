package com.voxapps.commander.service

import android.content.Context
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.commander.state.AppStateManager
import com.voxapps.logging.Logger

/**
 * The one place in the app where a wake-word engine key is connected to a class.
 *
 * The counterpart of [com.voxapps.commander.domain.engine.SttEngines] and
 * [com.voxapps.commander.domain.engine.TtsEngines], and the last domain to get one. Wake word chose
 * its engine with an `if/else if` chain inside the service, so a fourth engine meant editing a
 * branch in the middle of a foreground service's startup rather than adding a line to a table.
 *
 * Each branch also tested two spellings of its key — `"wake_porcupine" || "porcupine"` — a third
 * copy of an alias table that `SettingsRepositoryImpl.normalizeEngineKey` already applies to every
 * read. The legacy spelling cannot reach this far, so the map is keyed by the current key alone.
 *
 * Keyed by the `models.json` engine key itself, as the other two are: JSON selects among the
 * implementations compiled in, it cannot introduce one.
 */
object WakeWordEngines {

    private const val TAG = "WakeWordEngines"

    private val factories: Map<
        String,
        (Context, SettingsRepository, AppStateManager, () -> Unit) -> IWakeWordEngine
    > = mapOf(
        PorcupineWakeWordEngine.ENGINE_KEY to { ctx, repo, state, onDetected ->
            PorcupineWakeWordEngine(ctx, repo, state, onDetected)
        },
        OpenWakeWordEngine.ENGINE_KEY to { ctx, _, state, onDetected ->
            OpenWakeWordEngine(ctx, state, onDetected)
        },
        WakeWordEngine.ENGINE_KEY to { ctx, repo, state, onDetected ->
            WakeWordEngine(ctx, repo, state, onDetected)
        }
    )

    /** Engine keys with a compiled implementation. */
    val supportedKeys: Set<String> get() = factories.keys

    /**
     * Builds the engine for [engineKey], or null when this build has no implementation for it — an
     * engine a schema describes but this app cannot run. Null rather than quietly substituting a
     * default: the caller decides, and says so in the log.
     */
    fun create(
        engineKey: String,
        context: Context,
        settingsRepo: SettingsRepository,
        appStateManager: AppStateManager,
        onWakeWordDetected: () -> Unit
    ): IWakeWordEngine? {
        val factory = factories[engineKey]
        if (factory == null) {
            Logger.log("No wake word implementation for engine key '$engineKey'", TAG)
            return null
        }
        return factory(context, settingsRepo, appStateManager, onWakeWordDetected)
    }
}
