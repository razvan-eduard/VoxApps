package com.voxapps.commander.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineException
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.commander.state.AppStateManager
import com.voxapps.commander.state.VoiceState
import com.voxapps.logging.Logger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PorcupineWakeWordEngine(
    private val context: Context,
    private val settingsRepo: SettingsRepository,
    private val appStateManager: AppStateManager,
    private val onWakeWordDetected: () -> Unit
) : com.voxapps.commander.domain.engine.BaseVoxEngine(), IWakeWordEngine {

    override val engineKey: String = ENGINE_KEY


    private val TAG = "PorcupineWWEngine"
    private var porcupine: Porcupine? = null
    private var audioRecord: AudioRecord? = null
    @Volatile private var isListening = false

    // Guards check-then-act sections in startListening() against concurrent calls
    private val startStopLock = Any()

    // Managed scope for listenLoop — isolates exceptions
    private val exceptionHandler = CoroutineExceptionHandler { _, e ->
        Logger.log("Uncaught exception in Porcupine listen loop: ${e.message}", TAG)
        isListening = false
    }
    private var engineJob = SupervisorJob()
    private var engineScope = CoroutineScope(engineJob + Dispatchers.IO + exceptionHandler)

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    private val sampleRate = 16000
    private val frameLength = 512 // Porcupine requires 512-sample frames at 16kHz
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)

    companion object {
        const val ENGINE_KEY = "wake_porcupine"
        /**
         * The SDK's own keyword list, asked rather than restated.
         *
         * This was thirteen hand-written lines mapping a spoken phrase to
         * [Porcupine.BuiltInKeyword] — the same thirteen `models.json` lists as this engine's
         * models, and the same thirteen the enum already declares. Three copies of one list, and
         * the enum is the only one that cannot drift from what the library actually supports.
         *
         * The enum spells a phrase `HEY_GOOGLE`; a wake word is written "hey google".
         */
        fun builtInKeyword(wakeWord: String): Porcupine.BuiltInKeyword? {
            val wanted = wakeWord.trim().uppercase().replace(' ', '_')
            return Porcupine.BuiltInKeyword.entries.find { it.name == wanted }
        }

        /** For a message that tells the user what they could have typed instead. */
        fun builtInKeywords(): List<String> =
            Porcupine.BuiltInKeyword.entries.map { it.name.lowercase().replace('_', ' ') }
    }

    override suspend fun onLoad(spec: com.voxapps.commander.domain.engine.ModelSpec): Boolean = withContext(Dispatchers.IO) {
        val wake = spec as? com.voxapps.commander.domain.engine.ModelSpec.WakeWordModel ?: run {
            Logger.log("Wake word engines need a WakeWordModel spec", TAG)
            return@withContext false
        }
        // No path is read: Porcupine's keywords are compiled into the library, which is why the
        // engine declares runtime `device_builtin` and its spec carries no entry point.
        val wakeWord = wake.keyword

        try {
            porcupine?.delete()
            porcupine = null

            val accessKey = settingsRepo.getCredentialsSnapshot().forEngine(ENGINE_KEY)
            if (accessKey.isNullOrBlank()) {
                Logger.log("No Picovoice AccessKey configured", TAG)
                return@withContext false
            }

            val wakeWordClean = wakeWord.lowercase().trim()
            val builder = Porcupine.Builder()
                .setAccessKey(accessKey)

            // Porcupine detects a keyword compiled into the library. The engine used to fall
            // back to a custom `.ppn` file in assets, scanning for one on every load — but none is
            // bundled, nothing writes one, and a `.ppn` is licence-locked to the account that
            // generated it, so that branch could never run. It is gone rather than kept as a
            // promise the app cannot keep.
            val builtInKeyword = builtInKeyword(wakeWord)
            if (builtInKeyword == null) {
                Logger.log(
                    "'$wakeWordClean' is not one of Porcupine's keywords. Available: ${builtInKeywords()}",
                    TAG
                )
                return@withContext false
            }
            builder.setKeywords(arrayOf(builtInKeyword))
            Logger.log("Using Porcupine built-in keyword: $wakeWordClean", TAG)

            // Map the user's Wake Word Sensitivity setting to Porcupine's sensitivity param
            // (higher = more sensitive — inverse of the distance-threshold engines). One value
            // per keyword; we always configure exactly one keyword above.
            val sensitivitySetting = appStateManager.uiState.value.wakeWordSensitivity
            val sensitivity = WakeWordSensitivity.porcupineSensitivity(sensitivitySetting)
            builder.setSensitivities(floatArrayOf(sensitivity))

            porcupine = builder.build(context)
            Logger.log("Porcupine engine initialized successfully (sensitivity=$sensitivitySetting/$sensitivity, frameLength=${porcupine?.frameLength}, sampleRate=${porcupine?.sampleRate})", TAG)
            return@withContext true
        } catch (e: PorcupineException) {
            Logger.log("Porcupine init failed: ${e.message}", TAG)
            return@withContext false
        } catch (e: Exception) {
            Logger.log("Porcupine init error: ${e.message}", TAG)
            return@withContext false
        }
    }

    private fun requestAudioFocus(): Boolean {
        val request = com.voxapps.commander.utils.AudioFocusHelper.requestFocus(
            audioManager,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE,
            usage = AudioAttributes.USAGE_VOICE_COMMUNICATION,
            onFocusChange = { focusChange ->
                if (focusChange == AudioManager.AUDIOFOCUS_LOSS || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    Logger.log("Audio focus lost. Pausing Porcupine listening.", TAG)
                    stopListening()
                }
            }
        )
        audioFocusRequest = request
        return request != null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O
    }

    private fun abandonAudioFocus() {
        com.voxapps.commander.utils.AudioFocusHelper.abandonFocus(audioManager, audioFocusRequest)
        audioFocusRequest = null
    }

    override fun startListening(): Boolean = synchronized(startStopLock) {
        if (isListening) return true
        val engine = porcupine ?: run {
            Logger.log("Porcupine not initialized", TAG)
            return false
        }

        try {
            if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return false
            }

            if (!requestAudioFocus()) {
                Logger.log("Could not gain audio focus. Cannot start listening.", TAG)
                return false
            }

            audioRecord?.release()
            val aecEnabled = appStateManager.uiState.value.wakeWordAecEnabled
            val audioSource = if (aecEnabled) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.VOICE_RECOGNITION
            audioRecord = AudioRecord(
                audioSource,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                abandonAudioFocus()
                return false
            }

            isListening = true
            appStateManager.setWakeWordServiceListening(true)
            appStateManager.setVoiceState(VoiceState.LISTENING_WAKEWORD)
            audioRecord?.startRecording()

            if (!engineJob.isActive) {
                engineJob = SupervisorJob()
                engineScope = CoroutineScope(engineJob + Dispatchers.IO + exceptionHandler)
            }
            engineScope.launch { listenLoop() }
            Logger.log("Porcupine started listening", TAG)
            return true
        } catch (e: Exception) {
            Logger.log("Porcupine start error: ${e.message}", TAG)
            isListening = false
            abandonAudioFocus()
            return false
        }
    }

    private suspend fun listenLoop() {
        val frameBuffer = ShortArray(frameLength)
        var consecutiveErrors = 0

        while (isListening) {
            val currentAudioRecord = audioRecord ?: break
            if (currentAudioRecord.state != AudioRecord.STATE_INITIALIZED) break

            val read = try {
                currentAudioRecord.read(frameBuffer, 0, frameLength)
            } catch (e: Exception) {
                -1
            }

            if (read > 0 && isListening) {
                consecutiveErrors = 0
                try {
                    val keywordIndex = porcupine?.process(frameBuffer)
                    if (keywordIndex != null && keywordIndex >= 0) {
                        Logger.log("Porcupine wake word detected! index=$keywordIndex", TAG)
                        onWakeWordDetected()
                    }
                } catch (e: PorcupineException) {
                    Logger.log("Porcupine process error: ${e.message}", TAG)
                }
            } else if (read < 0) {
                consecutiveErrors++
                Logger.log("Audio read error count: $consecutiveErrors", TAG)
                if (consecutiveErrors > 5) {
                    Logger.log("Too many audio read errors. Aborting Porcupine loop.", TAG)
                    stopListening()
                    break
                }
                delay(50)
            } else if (!isListening) {
                break
            }
        }
        Logger.log("Porcupine listen loop exited cleanly", TAG)
    }

    override fun stopListening() = synchronized(startStopLock) {
        if (!isListening) return@synchronized
        Logger.log("Stopping Porcupine listening", TAG)
        isListening = false

        try {
            audioRecord?.let {
                if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    it.stop()
                }
                it.release()
            }
        } catch (e: Exception) {
            Logger.log("Error stopping AudioRecord: ${e.message}", TAG)
        } finally {
            audioRecord = null
            abandonAudioFocus()
        }
    }

    override fun stopService() {
        stopListening()
        appStateManager.setWakeWordServiceListening(false)
        appStateManager.setVoiceState(VoiceState.IDLE)
    }

    override fun onRelease() {
        stopService()
        engineJob.cancel()
        try {
            porcupine?.delete()
        } catch (e: Exception) {
            Logger.log("Porcupine delete error: ${e.message}", TAG)
        }
        porcupine = null
    }

    /** ~2MB — reloading it would cost more than releasing it frees. */
    override fun releasesUnderMemoryPressure(): Boolean = false

    override fun onUnload() {
        try { porcupine?.delete() } catch (e: Exception) { Logger.log("Porcupine delete error: ${e.message}", TAG) }
        porcupine = null
    }
}
