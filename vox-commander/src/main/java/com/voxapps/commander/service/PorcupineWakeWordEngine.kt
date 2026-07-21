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
) : IWakeWordEngine {

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
        val BUILT_IN_KEYWORDS = mapOf(
            "alexa" to Porcupine.BuiltInKeyword.ALEXA,
            "americano" to Porcupine.BuiltInKeyword.AMERICANO,
            "blueberry" to Porcupine.BuiltInKeyword.BLUEBERRY,
            "bumblebee" to Porcupine.BuiltInKeyword.BUMBLEBEE,
            "computer" to Porcupine.BuiltInKeyword.COMPUTER,
            "grapefruit" to Porcupine.BuiltInKeyword.GRAPEFRUIT,
            "grasshopper" to Porcupine.BuiltInKeyword.GRASSHOPPER,
            "hey google" to Porcupine.BuiltInKeyword.HEY_GOOGLE,
            "hey siri" to Porcupine.BuiltInKeyword.HEY_SIRI,
            "jarvis" to Porcupine.BuiltInKeyword.JARVIS,
            "picovoice" to Porcupine.BuiltInKeyword.PICOVOICE,
            "porcupine" to Porcupine.BuiltInKeyword.PORCUPINE,
            "terminator" to Porcupine.BuiltInKeyword.TERMINATOR
        )
    }

    override suspend fun initialize(modelPath: String, wakeWord: String): Boolean = withContext(Dispatchers.IO) {
        try {
            porcupine?.delete()
            porcupine = null

            val accessKey = settingsRepo.getPicovoiceAccessKeySync()
            if (accessKey.isNullOrBlank()) {
                Logger.log("No Picovoice AccessKey configured", TAG)
                return@withContext false
            }

            val wakeWordClean = wakeWord.lowercase().trim()
            val builder = Porcupine.Builder()
                .setAccessKey(accessKey)

            // Try built-in keyword first
            val builtInKeyword = BUILT_IN_KEYWORDS[wakeWordClean]
            if (builtInKeyword != null) {
                builder.setKeywords(arrayOf(builtInKeyword))
                Logger.log("Using Porcupine built-in keyword: $wakeWordClean", TAG)
            } else {
                // Try custom .ppn file in assets
                val ppnFileName = "$wakeWordClean.ppn"
                val ppnPath = wakeWordClean.replace(" ", "_") + ".ppn"
                val assetManager = context.assets
                val hasCustomPpn = try {
                    assetManager.list("")?.any { it.equals(ppnFileName, ignoreCase = true) || it.equals(ppnPath, ignoreCase = true) } == true
                } catch (e: Exception) {
                    false
                }

                if (hasCustomPpn) {
                    val actualPpnName = assetManager.list("")?.find {
                        it.equals(ppnFileName, ignoreCase = true) || it.equals(ppnPath, ignoreCase = true)
                    } ?: ppnFileName
                    builder.setKeywordPaths(arrayOf(actualPpnName))
                    Logger.log("Using Porcupine custom keyword file: $actualPpnName", TAG)
                } else {
                    Logger.log("Wake word '$wakeWordClean' not found as built-in or custom .ppn. Available built-ins: ${BUILT_IN_KEYWORDS.keys}", TAG)
                    return@withContext false
                }
            }

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

    override fun release() {
        stopService()
        engineJob.cancel()
        try {
            porcupine?.delete()
        } catch (e: Exception) {
            Logger.log("Porcupine delete error: ${e.message}", TAG)
        }
        porcupine = null
    }

    override fun releaseForMemoryPressure() {
        // Porcupine model is small (~2MB), no need to release on memory pressure
    }
}
