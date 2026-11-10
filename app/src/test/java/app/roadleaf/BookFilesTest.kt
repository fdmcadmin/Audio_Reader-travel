package app.roadleaf

import app.roadleaf.importing.BookFiles
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BookFilesTest {
    @Test fun deletionRemovesOnlyTheSelectedPrivateBookDirectory() {
        val root = Files.createTempDirectory("roadleaf-book-delete").toFile().canonicalFile
        val selected = BookFiles.directory(root, "selected-book").apply { mkdirs() }
        val kept = BookFiles.directory(root, "kept-book").apply { mkdirs() }
        try {
            File(selected, "0.txt").writeText("Original test prose")
            val other = File(kept, "0.txt").apply { writeText("Keep this original prose") }
            BookFiles.delete(root, "selected-book")
            assertFalse(selected.exists()); assertTrue(root.exists()); assertEquals("Keep this original prose", other.readText())
            BookFiles.delete(root, "selected-book") // Already absent is safe.
        } finally { File(kept, "0.txt").delete(); kept.delete(); selected.delete(); root.delete() }
    }
    @Test fun storageCannotEscapeThePrivateBooksRoot() {
        val root = Files.createTempDirectory("roadleaf-book-guard").toFile()
        try {
            listOf("", ".", "..", "../other", "a/b", "a\\b", "C:\\outside", "/outside").forEach { id ->
                assertThrows(IllegalArgumentException::class.java) { BookFiles.directory(root, id) }
            }
            assertEquals(root.canonicalFile, BookFiles.directory(root, "1234-abc").parentFile)
        } finally { root.delete() }
    }
}
