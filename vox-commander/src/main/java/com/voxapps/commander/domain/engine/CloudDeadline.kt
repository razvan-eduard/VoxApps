package com.voxapps.commander.domain.engine

import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.commander.data.remote.RemoteModelRegistry
import com.voxapps.logging.Logger
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Interceptor
import java.util.concurrent.TimeUnit

/**
 * How long a call that leaves the device may take before the caller gives up on it.
 *
 * One value, decided in one place, for every cloud call — the intent cascade, satellite raw prompts,
 * cloud transcription, benchmarks. Deliberately not a per-stage timeout inside the intent cascade:
 * the cascade is only one of the paths that can hang, and a timeout that lives there covers the one
 * caller that happens to run it rather than the engines that actually block.
 *
 * Until now nothing bounded these calls at all. `OpenAiInterpreter` inherited OkHttp's own 60s and
 * Gemini Cloud had no timeout whatsoever, while the user's "offline fallback timeout" setting was
 * passed to an empty setter — so a configured fallback could never run before the primary finished,
 * however long that took.
 *
 * ### Two layers, because one is a trap
 *
 * [run] guarantees the *caller* moves on. It does not release the socket: OkHttp's `execute()` is a
 * blocking read on an IO thread and coroutine cancellation cannot interrupt it, so with only the
 * coroutine bound the fallback fires promptly while an IO thread stays pinned for the remainder of
 * the HTTP client's own budget. Repeat that per command and the IO pool starves. [interceptor]
 * applies the same number to the connection, so the thread comes back too.
 *
 * The interceptor reads the setting per call rather than baking it into the client at construction:
 * the client outlives the setting, and a user who shortens the timeout should not have to restart
 * the app for it to take effect.
 */
object CloudDeadline {

    private const val TAG = "CloudDeadline"

    /** Below this, no real request can complete and the setting would just disable the engine. */
    private const val MIN_SECONDS = 3

    /**
     * The budget for [engineKey], in seconds.
     *
     * An engine may declare `timeout_seconds` for itself — one number for a short text prompt and
     * for uploading thirty seconds of audio is too blunt — and the user's setting is what applies
     * when it does not. The stored key keeps its `offlineFallbackTimeout` name: "how long before I
     * give up and go offline" is exactly the semantics, and stored identifiers are not renamed.
     */
    fun secondsFor(engineKey: String, settingsRepo: SettingsRepository): Int {
        val declared = RemoteModelRegistry.declaredTimeoutSeconds(engineKey)
        val configured = settingsRepo.getSettingsSnapshot().offlineFallbackTimeout
        return (declared ?: configured).coerceAtLeast(MIN_SECONDS)
    }

    fun millisFor(engineKey: String, settingsRepo: SettingsRepository): Long =
        secondsFor(engineKey, settingsRepo) * 1000L

    /**
     * Runs [block] under the engine's deadline, returning null when it expires.
     *
     * Null rather than a thrown timeout because that is what every caller here already means by
     * failure: the intent cascade advances to the next level on a null, and a transcription with no
     * text is a transcription that did not happen. A caller that needs to tell "timed out" from
     * "answered with nothing" reads the log line, which names the engine and the budget.
     */
    suspend fun <R> run(
        engineKey: String,
        settingsRepo: SettingsRepository,
        block: suspend () -> R
    ): R? {
        val millis = millisFor(engineKey, settingsRepo)
        return withTimeoutOrNull(millis) { block() }
            ?: run {
                Logger.log("$engineKey exceeded its ${millis}ms deadline — giving up on it", TAG)
                null
            }
    }

    /**
     * Applies the same deadline to the connection itself, per call.
     *
     * Set on the individual chain rather than on the client because the value is a live setting and
     * the client is built once. Connect, read and write are bounded separately — OkHttp's whole-call
     * `callTimeout` cannot be varied per call — which is what actually matters here: the read is the
     * leg that blocks when a service accepts a request and then goes quiet.
     */
    fun interceptor(engineKey: String, settingsRepo: SettingsRepository) = Interceptor { chain ->
        val millis = millisFor(engineKey, settingsRepo).toInt()
        chain
            .withConnectTimeout(millis, TimeUnit.MILLISECONDS)
            .withReadTimeout(millis, TimeUnit.MILLISECONDS)
            .withWriteTimeout(millis, TimeUnit.MILLISECONDS)
            .proceed(chain.request())
    }
}
