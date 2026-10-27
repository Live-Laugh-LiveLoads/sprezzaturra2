package com.ideaforge.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import com.ideaforge.settings.MicSource
import com.ideaforge.settings.RecordingSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException

/**
 * Captures the microphone on a dedicated high-priority thread and writes it as overlapping AAC
 * chunks.
 *
 * Chunking is measured in captured samples ("audio time"), never wall-clock time, so pauses and
 * scheduling hiccups cannot skew chunk boundaries. When the current chunk reaches
 * (length - overlap) a second encoder starts and both receive the same PCM until the first one
 * reaches its full length; that gives a true overlap from a single mic stream.
 *
 * Threading: [start], [pause], [resume] and [stop] may be called from any thread. Encoders and the
 * [Listener] callbacks live on the audio thread; the listener must hand work off, not block.
 */
class ChunkedAacRecorder(
    private val settings: RecordingSettings,
    private val audioDir: File,
    private val listener: Listener,
) {
    /** Callbacks, all invoked on the audio thread. */
    interface Listener {
        /** A new chunk file exists and is being written. */
        fun onChunkStarted(index: Int, file: File, startOffsetMs: Long)

        /** The chunk's .aac is complete and closed; [durationMs] is audio time. */
        fun onChunkFinished(index: Int, file: File, durationMs: Long)

        /** The chunk held nothing worth keeping (a pure duplicate of overlap, or silence of ~0 s). */
        fun onChunkDiscarded(index: Int, file: File)

        /** Capture or encoding failed; the recorder finishes what it has and stops. */
        fun onFailure(error: Throwable)

        /** After a resume request the microphone could not be opened; the recorder remains paused. */
        fun onMicUnavailable()
    }

    private class ActiveChunk(val index: Int, val encoder: AacChunkEncoder) {
        var samples = 0L
    }

    private val geometry = ChunkGeometry(settings.sampleRate, settings.chunkMinutes, settings.overlapSeconds)
    private val _level = MutableStateFlow(0f)
    private val _elapsedMs = MutableStateFlow(0L)
    private val stopped = CompletableDeferred<Long>()
    private val lock = Object()

    @Volatile private var wantPaused = false
    @Volatile private var stopRequested = false
    @Volatile private var thread: Thread? = null

    /** Current input level, 0..1. */
    val level: StateFlow<Float> = _level.asStateFlow()

    /** Recorded audio time in ms (paused time excluded). */
    val elapsedMs: StateFlow<Long> = _elapsedMs.asStateFlow()

    /** Audio session id of the live [AudioRecord] (0 when none), to match recording-config callbacks. */
    @Volatile var audioSessionId: Int = 0
        private set

    // Audio-thread state.
    private val active = ArrayList<ActiveChunk>(2)
    private var nextIndex = 0
    private var totalSamples = 0L
    private var record: AudioRecord? = null

    /** Starts the audio thread. Call once. */
    fun start() {
        check(thread == null) { "Recorder already started" }
        audioDir.mkdirs()
        val t = Thread({ run() }, "audio-recorder")
        thread = t
        t.start()
    }

    fun pause() {
        wantPaused = true
    }

    fun resume() {
        synchronized(lock) {
            wantPaused = false
            lock.notifyAll()
        }
    }

    /** Asks the thread to finish all chunks and exit; completes [awaitStopped]. */
    fun stop() {
        synchronized(lock) {
            stopRequested = true
            lock.notifyAll()
        }
    }

    /** Suspends until the audio thread has closed every file; returns the total audio time in ms. */
    suspend fun awaitStopped(): Long = stopped.await()

    private fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val bytesPerRead = settings.sampleRate / 10 * 2 // ~100 ms
        val buffer = ByteArray(bytesPerRead)
        try {
            record = openRecord() ?: throw IOException("The microphone could not be opened. Is another app using it?")
            startChunk()
            while (!stopRequested) {
                if (wantPaused) {
                    pauseLoop()
                    continue
                }
                val rec = record ?: break
                val n = rec.read(buffer, 0, buffer.size)
                if (n < 0) throw IOException("Microphone read failed (code $n)")
                if (n == 0) continue
                _level.value = LevelMeter.levelOfPcm16(buffer, 0, n)
                feed(buffer, n and 1.inv())
            }
        } catch (t: Throwable) {
            Log.e(TAG, "recording failed", t)
            listener.onFailure(t)
        } finally {
            releaseRecord()
            closeAll()
            _level.value = 0f
            stopped.complete(geometry.samplesToMs(totalSamples))
        }
    }

    /** Releases the mic while paused (so a phone call can use it) and waits for resume or stop. */
    private fun pauseLoop() {
        releaseRecord()
        _level.value = 0f
        for (c in active) c.encoder.flush()
        synchronized(lock) {
            while (wantPaused && !stopRequested) lock.wait(250)
        }
        if (stopRequested) return
        val rec = openRecord()
        if (rec == null) {
            // Keep waiting for another resume instead of dying; the caller shows a warning.
            wantPaused = true
            listener.onMicUnavailable()
        } else {
            record = rec
        }
    }

    /** Feeds [byteCount] bytes to every live encoder, splitting at chunk boundaries so they are exact. */
    private fun feed(pcm: ByteArray, byteCount: Int) {
        var offset = 0
        var remaining = byteCount / 2L
        while (remaining > 0) {
            val first = active[0]
            val take = minOf(remaining, geometry.samplesUntilBoundary(active.size, first.samples))
            val bytes = (take * 2).toInt()
            for (c in active) {
                c.encoder.feed(pcm, offset, bytes)
                c.samples += take
            }
            totalSamples += take
            offset += bytes
            remaining -= take
            settleBoundaries()
        }
        _elapsedMs.value = geometry.samplesToMs(totalSamples)
    }

    private fun settleBoundaries() {
        while (true) {
            when (geometry.nextAction(active.size, active[0].samples)) {
                ChunkGeometry.Action.START_NEXT -> startChunk()
                ChunkGeometry.Action.FINISH_FIRST -> finishChunk(active.removeAt(0), keep = true)
                ChunkGeometry.Action.NONE -> return
            }
        }
    }

    private fun startChunk() {
        val index = nextIndex
        val file = File(audioDir, "chunk_%03d.aac".format(index))
        val encoder = AacChunkEncoder(file, settings.sampleRate, settings.bitrateKbps)
        nextIndex++
        active.add(ActiveChunk(index, encoder))
        listener.onChunkStarted(index, file, geometry.startOffsetMs(index))
    }

    private fun finishChunk(chunk: ActiveChunk, keep: Boolean) {
        val file = chunk.encoder.file
        if (!keep) {
            chunk.encoder.abort()
            file.delete()
            listener.onChunkDiscarded(chunk.index, file)
            return
        }
        try {
            chunk.encoder.finish()
        } catch (e: IOException) {
            // The data written so far is still a valid ADTS stream, so keep the chunk and report.
            listener.onFailure(e)
        }
        listener.onChunkFinished(chunk.index, file, geometry.samplesToMs(chunk.samples))
    }

    /**
     * Ends all live chunks. A second chunk that exists at stop time only holds audio already inside
     * the first chunk's tail (its length never exceeds the overlap), so it is dropped; a chunk with
     * under 0.3 s of audio is also dropped because transcription services reject very short files.
     */
    private fun closeAll() {
        val minSamples = settings.sampleRate * 3L / 10
        val chunks = ArrayList(active)
        active.clear()
        for ((i, c) in chunks.withIndex()) {
            val redundant = i > 0
            try {
                finishChunk(c, keep = !redundant && c.samples >= minSamples)
            } catch (t: Throwable) {
                Log.e(TAG, "closing chunk ${c.index} failed", t)
                c.encoder.abort()
            }
        }
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO is requested by the UI before recording starts.
    private fun openRecord(): AudioRecord? {
        val wanted = sourceConstant(settings.micSource)
        return open(wanted) ?: if (wanted != MediaRecorder.AudioSource.MIC) open(MediaRecorder.AudioSource.MIC) else null
    }

    @SuppressLint("MissingPermission")
    private fun open(source: Int): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(settings.sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return null
        // Room for ~0.5 s so a slow encoder or GC pause does not drop samples.
        val size = maxOf(minBuf * 2, settings.sampleRate / 2 * 2)
        var r: AudioRecord? = null
        try {
            r = AudioRecord(source, settings.sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
            if (r.state != AudioRecord.STATE_INITIALIZED) {
                r.release()
                return null
            }
            r.startRecording()
            if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                r.release()
                return null
            }
            audioSessionId = r.audioSessionId
            return r
        } catch (e: RuntimeException) {
            Log.w(TAG, "AudioRecord open failed for source $source", e)
            try { r?.release() } catch (_: RuntimeException) { }
            return null
        }
    }

    private fun releaseRecord() {
        val r = record ?: return
        record = null
        audioSessionId = 0
        try { r.stop() } catch (_: RuntimeException) { }
        try { r.release() } catch (_: RuntimeException) { }
    }

    companion object {
        private const val TAG = "ChunkedAacRecorder"

        /** Maps the setting to the platform constant. */
        fun sourceConstant(source: MicSource): Int = when (source) {
            MicSource.MIC -> MediaRecorder.AudioSource.MIC
            MicSource.VOICE_RECOGNITION -> MediaRecorder.AudioSource.VOICE_RECOGNITION
            MicSource.CAMCORDER -> MediaRecorder.AudioSource.CAMCORDER
            MicSource.UNPROCESSED -> MediaRecorder.AudioSource.UNPROCESSED
        }
    }
}
