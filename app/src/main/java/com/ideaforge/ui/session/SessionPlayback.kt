package com.ideaforge.ui.session

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.ideaforge.data.db.ChunkEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * One finalized chunk as a playlist entry.
 *
 * Consecutive chunks overlap by a few seconds (so no word is cut at a boundary). To avoid playing the
 * overlap twice, every entry after the first is clipped to start where the previous one ended.
 * [sessionStartMs] is therefore the session time of the first *played* sample, not the chunk's own start.
 */
internal data class PlaylistItem(
    val file: File,
    /** Session time at which this entry's first played sample sits (chunk start + clip). */
    val sessionStartMs: Long,
    /** Played length of the entry (chunk duration minus the clipped overlap). */
    val lengthMs: Long,
    /** Offset into the chunk file where playback of this entry begins. */
    val clipStartMs: Long,
) {
    val sessionEndMs: Long get() = sessionStartMs + lengthMs
}

/**
 * Maps the session's finalized chunks to a clipped playlist.
 * Chunks that are still being written (not finalized) or whose file is gone are left out.
 */
internal fun buildPlaylist(chunks: List<ChunkEntity>): List<PlaylistItem> {
    val usable = chunks.filter { it.finalized && it.durationMs > 0 && File(it.path).exists() }.sortedBy { it.chunkIndex }
    val out = ArrayList<PlaylistItem>(usable.size)
    var prev: ChunkEntity? = null
    for (c in usable) {
        // prevStart + prevDuration - start_i: how much of this chunk the previous one already covered.
        val clip = prev?.let { (it.startOffsetMs + it.durationMs - c.startOffsetMs).coerceIn(0L, (c.durationMs - 1).coerceAtLeast(0L)) } ?: 0L
        out += PlaylistItem(File(c.path), c.startOffsetMs + clip, c.durationMs - clip, clip)
        prev = c
    }
    return out
}

/**
 * Media3 ExoPlayer over a session's chunk files, exposed in *session time* (milliseconds from the
 * start of the recording) so the UI never has to know about chunks. Owned by the ViewModel; call
 * [release] from onCleared. All methods must be called on the main thread.
 */
internal class SessionPlayback(context: Context, private val scope: CoroutineScope) {

    private val player: ExoPlayer = ExoPlayer.Builder(context).build().apply {
        // Speech content type + focus handling so a call or another app pauses us politely.
        setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
            true,
        )
        setHandleAudioBecomingNoisy(true)
    }

    private var items: List<PlaylistItem> = emptyList()
    private var ticker: Job? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    /** Current position in session time. Updated about four times a second while playing. */
    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    /** End of the last playable sample in session time (0 when nothing is playable). */
    private val _playableEndMs = MutableStateFlow(0L)
    val playableEndMs: StateFlow<Long> = _playableEndMs.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val hasAudio: Boolean get() = items.isNotEmpty()

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
            if (isPlaying) startTicker() else {
                ticker?.cancel()
                _positionMs.value = currentSessionMs()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            _positionMs.value = currentSessionMs()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            _positionMs.value = currentSessionMs()
        }

        override fun onPlayerError(error: PlaybackException) {
            _error.value = error.message ?: "Playback failed"
        }
    }

    init {
        player.addListener(listener)
    }

    /** Replaces (or extends) the playlist when the chunk list changes, keeping the listening position. */
    fun setChunks(chunks: List<ChunkEntity>) {
        val next = buildPlaylist(chunks)
        if (next == items) return
        val old = items
        items = next
        _playableEndMs.value = next.lastOrNull()?.sessionEndMs ?: 0L
        if (next.isEmpty()) {
            player.clearMediaItems()
            return
        }
        if (old.isNotEmpty() && next.size > old.size && next.subList(0, old.size) == old) {
            // A chunk was finalized while recording: append without disturbing playback.
            player.addMediaItems(next.subList(old.size, next.size).map(::toMediaItem))
            return
        }
        val position = if (old.isEmpty()) 0L else currentSessionMs(old)
        val resume = player.playWhenReady && old.isNotEmpty()
        player.setMediaItems(next.map(::toMediaItem), false)
        player.prepare()
        seekToSessionInternal(position)
        player.playWhenReady = resume
    }

    private fun toMediaItem(item: PlaylistItem): MediaItem {
        val builder = MediaItem.Builder().setUri(Uri.fromFile(item.file))
        if (item.clipStartMs > 0) {
            builder.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setStartPositionMs(item.clipStartMs).build(),
            )
        }
        return builder.build()
    }

    /** Session time → (entry, position inside the entry). Gaps fall to the entry before them. */
    private fun seekToSessionInternal(sessionMs: Long) {
        if (items.isEmpty()) return
        val idx = items.indexOfLast { it.sessionStartMs <= sessionMs }.coerceAtLeast(0)
        val item = items[idx]
        val pos = (sessionMs - item.sessionStartMs).coerceIn(0L, item.lengthMs)
        player.seekTo(idx, pos)
        _positionMs.value = item.sessionStartMs + pos
    }

    /** Seeks to [sessionMs] and starts playing. Returns false when there is nothing to play. */
    fun seekAndPlay(sessionMs: Long): Boolean {
        if (items.isEmpty()) return false
        _error.value = null
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        seekToSessionInternal(sessionMs.coerceAtLeast(0L))
        player.play()
        return true
    }

    /** Moves the playhead without changing play/pause (scrubber). */
    fun seekTo(sessionMs: Long) {
        if (items.isEmpty()) return
        seekToSessionInternal(sessionMs.coerceAtLeast(0L))
    }

    fun seekBy(deltaMs: Long) = seekTo((currentSessionMs() + deltaMs).coerceIn(0L, _playableEndMs.value))

    fun togglePlay() {
        if (items.isEmpty()) return
        if (player.isPlaying) {
            player.pause()
            return
        }
        _error.value = null
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        if (player.playbackState == Player.STATE_ENDED) seekToSessionInternal(0L)
        player.play()
    }

    fun pause() {
        player.pause()
    }

    fun release() {
        ticker?.cancel()
        player.removeListener(listener)
        player.release()
    }

    private fun currentSessionMs(list: List<PlaylistItem> = items): Long {
        val idx = player.currentMediaItemIndex
        val item = list.getOrNull(idx) ?: return _positionMs.value
        return item.sessionStartMs + player.currentPosition.coerceIn(0L, item.lengthMs)
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                _positionMs.value = currentSessionMs()
                delay(250)
            }
        }
    }
}
