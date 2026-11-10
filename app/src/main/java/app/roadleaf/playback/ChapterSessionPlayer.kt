package app.roadleaf.playback

import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/** Exposes real chapters to media controls; private TTS files never become separate episodes. */
@UnstableApi
class ChapterSessionPlayer(
    private val audio: Player,
    private val chapters: () -> List<MediaItem>,
    private val clock: ChapterAudioClock,
    private val moreAudio: () -> Boolean,
    private val select: (String) -> Unit,
    private val canPlay: () -> Boolean = { true },
    private val control: (String) -> Boolean = { true }
) : ForwardingSimpleBasePlayer(audio) {
    companion object {
        const val START_MS = "chapterStartMs"
        const val DURATION_MS = "speechDurationMs"
        fun key(item: MediaItem?): String? {
            val extras = item?.mediaMetadata?.extras ?: return null
            if (item.mediaId == "web-loading" || item.mediaId == "preview") return null
            return extras.getString("webUrl")?.let { "web:$it" }
                ?: extras.getString("chapterId")?.let { "chapter:$it" }
        }
    }
    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (!control(if (playWhenReady) "Play" else "Pause")) return Futures.immediateVoidFuture()
        if (playWhenReady && !canPlay()) return Futures.immediateVoidFuture()
        return super.handleSetPlayWhenReady(playWhenReady)
    }
    override fun handleStop(): ListenableFuture<*> = if (control("Stop")) super.handleStop() else Futures.immediateVoidFuture()
    override fun getState(): State {
        val original = super.getState()
        val key = key(audio.currentMediaItem) ?: return original
        val list = chapters()
        val index = list.indexOfFirst { it.mediaId == key }
        if (index < 0) return original
        val raw = requireNotNull(audio.currentMediaItem)
        val startMs = raw.mediaMetadata.extras?.getLong(START_MS) ?: 0
        val metadata = raw.mediaMetadata.buildUpon().setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER).build()
        val data = list.mapIndexed { i, item ->
            val current = if (i == index) item.buildUpon().setMediaMetadata(metadata).build() else item
            MediaItemData.Builder(item.mediaId).setMediaItem(current).setMediaMetadata(current.mediaMetadata)
                .setIsSeekable(true).setDurationUs(clock.duration(item.mediaId)?.times(1000) ?: C.TIME_UNSET).build()
        }
        val commands = audio.availableCommands.buildUpon()
            .remove(Player.COMMAND_CHANGE_MEDIA_ITEMS).remove(Player.COMMAND_SET_REPEAT_MODE).remove(Player.COMMAND_SET_SHUFFLE_MODE)
            // Full-duration scrubbing needs audio that may not have been synthesized yet.
            .remove(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS).add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        if (index < list.lastIndex) commands.add(Player.COMMAND_SEEK_TO_NEXT).add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        else commands.remove(Player.COMMAND_SEEK_TO_NEXT).remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        return original.buildUpon().setPlaylist(data).setCurrentMediaItemIndex(index)
            .setAvailableCommands(commands.build()).clearPositionDiscontinuity()
            .setContentPositionMs { startMs + audio.contentPosition }
            .setContentBufferedPositionMs { startMs + audio.contentBufferedPosition }
            .setPlaybackState(if (audio.playbackState == Player.STATE_ENDED && audio.playWhenReady && moreAudio()) Player.STATE_BUFFERING else audio.playbackState)
            .build()
    }
    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        if (!control("Seek/chapter")) return Futures.immediateVoidFuture()
        val current = key(audio.currentMediaItem) ?: return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
        val list = chapters()
        val index = list.indexOfFirst { it.mediaId == current }
        val next = seekCommand in setOf(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        val previous = seekCommand in setOf(Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        val target = when {
            next -> list.getOrNull(index + 1)
            previous -> list.getOrNull(mediaItemIndex.takeIf { it >= 0 } ?: index)
            else -> list.getOrNull(mediaItemIndex.takeIf { it >= 0 } ?: index)
        }
        if (target != null && (next || previous || target.mediaId != current)) {
            select(target.mediaId)
            return Futures.immediateVoidFuture()
        }
        val desired = if (positionMs == C.TIME_UNSET) 0 else positionMs.coerceAtLeast(0)
        val prepared = (0 until audio.mediaItemCount).filter { key(audio.getMediaItemAt(it)) == current }
        val chosen = prepared.lastOrNull { (audio.getMediaItemAt(it).mediaMetadata.extras?.getLong(START_MS) ?: 0) <= desired }
            ?: prepared.firstOrNull()
        if (chosen != null) {
            val extras = audio.getMediaItemAt(chosen).mediaMetadata.extras
            val start = extras?.getLong(START_MS) ?: 0
            if (desired == 0L && start > 0) select(current)
            else audio.seekTo(chosen, (desired - start).coerceIn(0, (extras?.getLong(DURATION_MS) ?: 1).coerceAtLeast(1) - 1))
        }
        return Futures.immediateVoidFuture()
    }
}
