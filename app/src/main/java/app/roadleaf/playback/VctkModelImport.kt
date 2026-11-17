package app.roadleaf.playback

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.util.UUID

/** Extracts only the three required files from Roadleaf's pinned VCTK archive. */
internal object VctkModelImport {
    suspend fun installBundled(context: Context) = withContext(Dispatchers.IO) {
        if (NativeVctk.installed(context)) return@withContext
        val stage = File(context.filesDir, "vctk-stage-" + UUID.randomUUID()).apply { mkdirs() }
        val target = NativeVctk.folder(context)
        var committed = false
        try {
            context.assets.open("models/vits.tar.bz2").use { input ->
                TarArchiveInputStream(BZip2CompressorInputStream(input.buffered(), true)).use { tar ->
                    var count = 0
                    while (true) {
                        val entry = tar.nextTarEntry ?: break
                        require(++count <= 32) { "Unexpected VCTK archive layout." }
                        require(!entry.isSymbolicLink && !entry.isLink) { "Linked model files are not allowed." }
                        val name = entry.name.removePrefix("./vits-vctk/")
                        if (name !in setOf("vits-vctk.onnx", "lexicon.txt", "tokens.txt")) continue
                        require(entry.isFile && entry.size in 1L..200_000_000L) { "Invalid VCTK model file." }
                        val file = File(stage, name)
                        file.outputStream().buffered().use { out ->
                            val buf = ByteArray(65536)
                            var remaining = entry.size
                            while (remaining > 0) {
                                val read = tar.read(buf, 0, minOf(remaining, buf.size.toLong()).toInt())
                                check(read > 0) { "Incomplete VCTK archive." }
                                out.write(buf, 0, read)
                                remaining -= read
                            }
                        }
                    }
                }
            }
            require(File(stage, "vits-vctk.onnx").length() > 100_000_000L &&
                File(stage, "lexicon.txt").length() > 10_000L && File(stage, "tokens.txt").length() > 10L) {
                "Bundled VCTK model is incomplete."
            }
            val backup = File(context.filesDir, "vctk-old-" + UUID.randomUUID())
            if (target.exists()) check(target.renameTo(backup))
            try {
                check(stage.renameTo(target))
                committed = true
                backup.deleteRecursively()
            } catch (failed: Exception) {
                if (backup.exists()) backup.renameTo(target)
                throw failed
            }
        } finally { if (!committed) stage.deleteRecursively() }
    }
}
