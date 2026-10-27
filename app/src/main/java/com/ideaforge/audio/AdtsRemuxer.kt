package com.ideaforge.audio

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaCodec
import android.media.MediaMuxer
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * Moves AAC out of a raw ADTS stream into an MP4 (.m4a) container without re-encoding, and
 * measures audio durations. The API accepts .m4a but not raw ADTS, and m4a seeks properly.
 */
object AdtsRemuxer {
    private const val TAG = "AdtsRemuxer"
    private const val SAMPLE_BUFFER_BYTES = 64 * 1024

    /**
     * Copies every complete AAC frame of [src] into [dst] (MPEG-4 container) and returns [dst].
     * A truncated final frame (crash mid-write) is trimmed from [src] first, because extractors
     * handle partial frames inconsistently. Throws [IOException] when [src] holds no complete
     * frame or the container cannot be written; [dst] is only created on success.
     */
    @Throws(IOException::class)
    fun remux(src: File, dst: File): File {
        val scan = FileInputStream(src).use { Adts.scan(BufferedInputStream(it, 32 * 1024)) }
        if (scan.frames == 0) throw IOException("${src.name} contains no complete audio frame")
        if (scan.validBytes < src.length()) {
            RandomAccessFile(src, "rw").use { it.setLength(scan.validBytes) }
        }

        val tmp = File(dst.parentFile, dst.name + ".tmp")
        tmp.delete()
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            extractor.setDataSource(src.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("No audio track in ${src.name}")
            extractor.selectTrack(track)
            val m = MediaMuxer(tmp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = m
            val outTrack = m.addTrack(extractor.getTrackFormat(track))
            m.start()
            started = true

            val buffer = ByteBuffer.allocate(SAMPLE_BUFFER_BYTES)
            val info = MediaCodec.BufferInfo()
            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.set(0, size, extractor.sampleTime.coerceAtLeast(0), MediaCodec.BUFFER_FLAG_KEY_FRAME)
                m.writeSampleData(outTrack, buffer, info)
                extractor.advance()
            }
            m.stop()
            started = false
        } catch (e: IOException) {
            tmp.delete()
            throw e
        } catch (e: RuntimeException) {
            tmp.delete()
            throw IOException("Remux of ${src.name} failed: ${e.message}", e)
        } finally {
            try { extractor.release() } catch (_: RuntimeException) { }
            try {
                if (started) muxer?.stop()
            } catch (_: RuntimeException) { }
            try { muxer?.release() } catch (_: RuntimeException) { }
        }
        dst.delete()
        if (!tmp.renameTo(dst)) {
            tmp.delete()
            throw IOException("Could not move remuxed audio to ${dst.name}")
        }
        return dst
    }

    /**
     * Duration of an audio file in milliseconds, or 0 if it cannot be determined. Raw ADTS files
     * are measured by counting frames, which stays correct for truncated files; everything else
     * is asked from the platform.
     */
    fun durationMs(file: File): Long {
        if (!file.isFile || file.length() == 0L) return 0L
        if (file.extension.equals("aac", ignoreCase = true)) {
            return try {
                FileInputStream(file).use { Adts.scan(BufferedInputStream(it, 32 * 1024)).durationMs }
            } catch (e: IOException) {
                Log.w(TAG, "could not scan ${file.name}", e)
                0L
            }
        }
        val fromRetriever = metadataDuration(file)
        return if (fromRetriever > 0) fromRetriever else extractorDuration(file)
    }

    private fun metadataDuration(file: File): Long {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.absolutePath)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: RuntimeException) {
            0L
        } finally {
            try { r.release() } catch (_: RuntimeException) { }
        }
    }

    private fun extractorDuration(file: File): Long {
        val e = MediaExtractor()
        return try {
            e.setDataSource(file.absolutePath)
            (0 until e.trackCount)
                .map { e.getTrackFormat(it) }
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true && it.containsKey(MediaFormat.KEY_DURATION) }
                ?.getLong(MediaFormat.KEY_DURATION)?.div(1000L) ?: 0L
        } catch (ex: Exception) {
            0L
        } finally {
            try { e.release() } catch (_: RuntimeException) { }
        }
    }
}
