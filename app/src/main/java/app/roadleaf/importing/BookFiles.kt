package app.roadleaf.importing

import java.io.File

/** Imported text is always under one generated book directory; never delete paths stored in chapter text. */
object BookFiles {
    fun directory(booksRoot: File, id: String): File {
        require(id.matches(Regex("[A-Za-z0-9_-]+"))) { "Invalid book storage ID" }
        val root = booksRoot.canonicalFile
        return File(root, id).canonicalFile.also {
            require(it.parentFile == root && it != root) { "Book storage must remain inside the private library" }
        }
    }
    fun delete(booksRoot: File, id: String) {
        val target = directory(booksRoot, id)
        check(!target.exists() || target.deleteRecursively()) { "Book removed from library; its text files could not be cleaned up" }
    }
}
