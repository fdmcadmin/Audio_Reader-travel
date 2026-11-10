package app.roadleaf.playback

import app.roadleaf.data.Book
import app.roadleaf.data.Chapter
import app.roadleaf.data.Progress
import java.io.Closeable
import java.io.File
import java.io.Reader

/** Reads only one bounded speech window. The whole book is never loaded into memory. */
class SpeechChunkReader(
    private val reader: Reader,
    startOffset: Int = 0,
    private val maxLength: Int = 1800,
    private val openingLengths: List<Int> = emptyList(),
    private var firstExactLength: Int? = null,
    private val paragraphChunks: Boolean = false
) : Closeable {
    private val pending = StringBuilder()
    private var offset = startOffset
    private var eof = false
    private var window = 0
    init {
        require(startOffset >= 0 && maxLength >= 2)
        require(openingLengths.all { it in 2..maxLength })
        require(firstExactLength == null || firstExactLength!! in 1..maxLength)
        var remaining = startOffset.toLong()
        while (remaining > 0) {
            val skipped = reader.skip(remaining)
            if (skipped > 0) remaining -= skipped else if (reader.read() < 0) { eof = true; break } else remaining--
        }
    }
    fun next(): SpeechChunk? {
        while (true) {
            var limit = firstExactLength ?: openingLengths.getOrElse(window) { maxLength }
            fill(limit)
            if (paragraphChunks && firstExactLength == null && pending.trimStart().startsWith("[Scene", true) && limit < maxLength) {
                limit = maxLength; fill(limit)
            }
            if (pending.isEmpty()) return null
            val newline = if (paragraphChunks && firstExactLength == null) pending.indexOf("\n").takeIf { it in 0 until minOf(limit, pending.length) } else null
            val end = if (firstExactLength != null) minOf(limit, pending.length) else newline?.plus(1) ?: SpeechText.chunkEnd(pending, limit)
            val text = pending.substring(0, end)
            val start = offset
            pending.delete(0, end); offset += end
            firstExactLength = null; window++
            if (text.isNotBlank()) return SpeechChunk(start, text)
        }
    }
    private fun fill(limit: Int) {
        while (!eof && pending.length <= limit) {
            val buffer = CharArray(limit + 1 - pending.length)
            val count = reader.read(buffer)
            if (count < 0) eof = true else if (count == 0) {
                val char = reader.read(); if (char < 0) eof = true else pending.append(char.toChar())
            } else pending.append(buffer, 0, count)
        }
    }
    override fun close() = reader.close()
}

data class NarrationPart(val chapter: Chapter, val book: Book, val offset: Int, val text: String)
class ChapterNarrationQueue(
    private val chapters: List<Chapter>,
    private val books: Map<String, Book>,
    private val saved: Progress? = null,
    private val fastStart: Boolean = false,
    private val resumeChars: Int? = null,
    private val paragraphChunks: Boolean = false,
    /** Short opening passages for the first chapter only, so a story starts speaking sooner (later chapters are prepared ahead anyway). */
    private val quickOpening: Boolean = false,
    private val open: (Chapter) -> Reader = { File(it.textPath).bufferedReader() }
) : Closeable {
    private var index = (saved?.let { progress -> chapters.indexOfFirst { it.id == progress.chapterId } } ?: 0).coerceAtLeast(0)
    private var opened = false
    private var reader: SpeechChunkReader? = null
    private var pending: NarrationPart? = null
    private var following: NarrationPart? = null
    private var followingRead = false
    private var sourceExhausted = false
    val exhausted get() = sourceExhausted && pending == null && following == null
    fun peek(): NarrationPart? {
        pending?.let { return it }
        return readNext().also { pending = it }
    }
    /** Stage a bounded next span without committing this one. Cancellation can safely retry. */
    fun peekFollowing(): NarrationPart? {
        if (peek() == null) return null
        if (!followingRead) { following = readNext(); followingRead = true }
        return following
    }
    private fun readNext(): NarrationPart? {
        if (sourceExhausted) return null
        while (index < chapters.size) {
            val chapter = chapters[index]
            if (reader == null) {
                val resuming = chapter.id == saved?.chapterId
                // Restore legacy segment boundaries when no exact span was recorded, preserving
                // saved audio milliseconds. New webpage checkpoints record the original text span.
                val lengths = if (fastStart) listOf(if (resuming && resumeChars == null) 1800 else 360, 720, 1200)
                    else if (quickOpening && !opened) QUICK_OPENING else emptyList()
                opened = true
                reader = SpeechChunkReader(open(chapter), if (resuming) saved!!.textOffset.coerceAtLeast(0) else 0,
                    openingLengths = lengths, firstExactLength = if (resuming) resumeChars else null, paragraphChunks = paragraphChunks)
            }
            val chunk = reader!!.next()
            if (chunk != null) return NarrationPart(chapter, requireNotNull(books[chapter.bookId]), chunk.offset, chunk.text)
            reader!!.close(); reader = null; index++
        }
        sourceExhausted = true
        return null
    }
    fun consume() { check(pending != null); pending = following; following = null; followingRead = false }
    override fun close() { reader?.close(); reader = null; pending = null; following = null; sourceExhausted = true }
    companion object {
        /** Opening passage limits; each passage still ends at a paragraph or sentence end. */
        val QUICK_OPENING = listOf(360, 720, 1200)
    }
}
