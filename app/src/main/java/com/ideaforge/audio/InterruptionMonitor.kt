package com.ideaforge.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.util.Log
import androidx.annotation.RequiresApi
import com.ideaforge.settings.RecordingSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Watches for everything that can interrupt or endanger a recording and reports it to the
 * [Host] (the service): phone calls and audio-focus loss (pause, optionally auto-resume), the OS
 * silencing our mic, low battery and low storage.
 *
 * Must be started and stopped on the main thread; [scope] must be a main-thread scope. No
 * READ_PHONE_STATE permission is used; calls are inferred from [AudioManager.getMode] and focus.
 */
class InterruptionMonitor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: () -> RecordingSettings,
    private val audioDir: File,
    private val micSessionId: () -> Int,
    private val host: Host,
) {
    /** What the monitor asks of the service. All calls arrive on the main thread. */
    interface Host {
        val isPaused: Boolean

        /** Pause because of [reason] (or just update the reason if already paused). */
        fun onAutoPause(reason: String)

        fun onAutoResume()

        /** Show ([message] non-null) or clear ([message] null) the warning identified by [kind]. */
        fun onWarning(kind: String, message: String?)

        /** End the recording gracefully; the user is told [reason]. */
        fun onStopRequested(reason: String)
    }

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val batteryManager = context.getSystemService(BatteryManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private var focusRequest: AudioFocusRequest? = null
    private var recordingCallback: AudioManager.AudioRecordingCallback? = null
    private var ticker: Job? = null

    private var pausedByFocus = false
    private var pausedByCall = false
    private var inCall = false
    private var stopSignalled = false
    private val shown = HashMap<String, String?>()

    fun start() {
        requestFocus()
        if (Build.VERSION.SDK_INT >= 29) registerRecordingCallback()
        ticker = scope.launch {
            var tick = 0
            while (isActive) {
                checkCall()
                // Battery and storage change slowly; every 5 s is plenty.
                if (tick % 5 == 0) {
                    checkBattery()
                    checkStorage()
                }
                tick++
                delay(1_000)
            }
        }
    }

    fun stop() {
        ticker?.cancel()
        ticker = null
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
        if (Build.VERSION.SDK_INT >= 29) recordingCallback?.let { audioManager.unregisterAudioRecordingCallback(it) }
        recordingCallback = null
    }

    /** The user paused or resumed by hand: forget automatic pauses so we never override them. */
    fun onManualChange() {
        pausedByFocus = false
        pausedByCall = false
        warn(WARN_INTERRUPTED, null)
    }

    // ---- Audio focus --------------------------------------------------------------------------

    private fun requestFocus() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener({ change -> onFocusChange(change) }, handler)
            .build()
        focusRequest = request
        if (audioManager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.w(TAG, "audio focus not granted; recording continues without it")
        }
    }

    private fun onFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (!host.isPaused) {
                    pausedByFocus = true
                    host.onAutoPause("Audio interrupted")
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                warn(WARN_FOCUS, null)
                if (pausedByFocus) {
                    pausedByFocus = false
                    resumeIfClear()
                }
            }
            // Permanent loss: another app took over for good. Pausing would never end, so keep
            // recording and tell the user.
            AudioManager.AUDIOFOCUS_LOSS -> warn(WARN_FOCUS, "Another app took audio focus. Still recording.")
            // AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK: we are a recorder, nothing to duck.
        }
    }

    // ---- Calls --------------------------------------------------------------------------------

    private fun checkCall() {
        val mode = audioManager.mode
        val nowInCall = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
        if (nowInCall && !inCall) {
            inCall = true
            pausedByCall = true
            host.onAutoPause("Phone call")
        } else if (!nowInCall && inCall) {
            inCall = false
            if (pausedByCall) {
                pausedByCall = false
                resumeIfClear()
            }
        }
    }

    private fun resumeIfClear() {
        if (pausedByCall || pausedByFocus) return
        if (settings().autoResumeAfterInterruption) {
            warn(WARN_INTERRUPTED, null)
            host.onAutoResume()
        } else {
            warn(WARN_INTERRUPTED, "The interruption is over. Tap Resume to continue recording.")
        }
    }

    // ---- Silenced mic -------------------------------------------------------------------------

    @RequiresApi(29)
    private fun registerRecordingCallback() {
        val cb = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
                val id = micSessionId()
                if (id == 0) return
                val mine = configs.firstOrNull { it.clientAudioSessionId == id } ?: return
                warn(
                    WARN_SILENCED,
                    if (mine.isClientSilenced) "The system is blocking the microphone (another app or a call is using it). Audio may be silent." else null,
                )
            }
        }
        recordingCallback = cb
        audioManager.registerAudioRecordingCallback(cb, handler)
    }

    // ---- Resources ----------------------------------------------------------------------------

    private fun checkBattery() {
        if (stopSignalled) return
        val s = settings()
        val percent = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (percent <= 0 || percent > 100) return // unknown
        val charging = batteryManager.isCharging
        when {
            charging -> warn(WARN_BATTERY, null)
            percent <= s.stopAtBatteryPercent -> {
                stopSignalled = true
                host.onStopRequested("Battery at $percent%. Recording was stopped and saved.")
            }
            percent <= s.warnAtBatteryPercent -> warn(WARN_BATTERY, "Battery low ($percent%). Recording stops at ${s.stopAtBatteryPercent}%.")
            else -> warn(WARN_BATTERY, null)
        }
    }

    private fun checkStorage() {
        if (stopSignalled) return
        val s = settings()
        val freeMb = try {
            audioDir.mkdirs()
            StatFs(audioDir.path).availableBytes / (1024L * 1024L)
        } catch (e: RuntimeException) {
            Log.w(TAG, "StatFs failed", e)
            return
        }
        when {
            freeMb <= s.stopAtFreeMb -> {
                stopSignalled = true
                host.onStopRequested("Storage is almost full ($freeMb MB free). Recording was stopped and saved.")
            }
            freeMb < 2L * s.stopAtFreeMb -> warn(WARN_STORAGE, "Storage is running low ($freeMb MB free).")
            else -> warn(WARN_STORAGE, null)
        }
    }

    /** Forwards a warning only when it changed, so events are not logged every tick. */
    private fun warn(kind: String, message: String?) {
        if (shown[kind] == message) return
        shown[kind] = message
        host.onWarning(kind, message)
    }

    companion object {
        private const val TAG = "InterruptionMonitor"
        const val WARN_FOCUS = "focus"
        const val WARN_SILENCED = "silenced"
        const val WARN_BATTERY = "battery"
        const val WARN_STORAGE = "storage"
        const val WARN_INTERRUPTED = "interrupted"
    }
}
