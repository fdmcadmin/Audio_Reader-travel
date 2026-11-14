package app.roadleaf.playback

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.util.UUID

/**
 * Imports the official Kokoro INT8 v1.0 .tar.bz2 selected by the user.
 * It does not alter any Library book, character cast, or external NovelAI file.
 * Archive paths and extracted bytes are bounded to avoid traversal and storage abuse.
 */
internal object KokoroModelImport {
    internal fun relativePath(name: String, directory: Boolean): String? {
        val normalized = name.removePrefix("./")
        val prefix = "kokoro-int8-multi-lang-v1_0/"
        if (!normalized.startsWith(prefix)) return null
        val path = normalized.removePrefix(prefix).let { if (directory) it.removeSuffix("/") else it }
        if (path.isEmpty()) return null
        require('\\' !in path && path.split('/').none { it == ".." || it == "." || it.isBlank() }) {
            "An invalid model file path was rejected."
        }
        return path
    }
    suspend fun install(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val root = File(context.filesDir, "kokoro-stage-" + UUID.randomUUID()).apply { mkdirs() }
        val target = NativeKokoro.folder(context)
        var committed = false
        try {
            var total = 0L
            var count = 0
            context.contentResolver.openInputStream(uri)?.use { input ->
                TarArchiveInputStream(BZip2CompressorInputStream(input.buffered(), true)).use { archive ->
                    while (true) {
                        val item = archive.nextTarEntry ?: break
                        count++
                        require(count <= 4_000) { "Too many files in the voice model archive." }
                        require(!item.isSymbolicLink && !item.isLink) { "Linked files are not allowed in a model archive." }
                        val path = relativePath(item.name, item.isDirectory) ?: continue
                        val file = File(root, path)
                        require(file.canonicalPath.startsWith(root.canonicalPath + File.separator)) {
                            "A model file tried to escape the selected directory."
                        }
                        if (item.isDirectory) {
                            file.mkdirs()
                            continue
                        }
                        require(item.isFile && item.size in 0L..500_000_000L) {
                            "A model file has an invalid size."
                        }
                        file.parentFile?.mkdirs()
                        file.outputStream().buffered().use { out ->
                            val buffer = ByteArray(65536)
                            var remaining = item.size
                            while (remaining > 0) {
                                val n = archive.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                check(n > 0) { "The model archive is incomplete." }
                                out.write(buffer, 0, n)
                                remaining -= n
                                total += n
                                require(total <= 550_000_000L) { "The extracted model exceeds the 550 MB safety limit." }
                            }
                        }
                    }
                }
            } ?: error("Cannot read the selected model archive.")
            val required = listOf("model.int8.onnx", "voices.bin", "tokens.txt",
                "lexicon-us-en.txt", "lexicon-gb-en.txt")
            require(required.all { File(root, it).length() > 0L } &&
                File(root, "model.int8.onnx").length() > 50_000_000L &&
                File(root, "voices.bin").length() > 1_000_000L &&
                File(root, "espeak-ng-data").isDirectory) {
                "This isn't the official Kokoro INT8 v1.0 package, or some model files are missing."
            }
            val backup = File(context.filesDir, "kokoro-old-" + UUID.randomUUID())
            if (target.exists()) check(target.renameTo(backup)) { "Cannot back up the existing model." }
            try {
                check(root.renameTo(target)) { "Cannot install the extracted model." }
                committed = true
                backup.deleteRecursively()
            } catch (failed: Exception) {
                if (backup.exists()) backup.renameTo(target)
                throw failed
            }
            "Kokoro offline model installed: 28 English speakers. Refresh voices, then assign voices to your characters."
        } finally {
            if (!committed) root.deleteRecursively()
        }
    }
}
