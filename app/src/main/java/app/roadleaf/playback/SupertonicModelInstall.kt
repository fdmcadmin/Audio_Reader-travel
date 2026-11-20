package app.roadleaf.playback

import android.content.Context
import app.roadleaf.sources.SafeHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.UUID

/** Installs model graphs once into Roadleaf's private storage. No story or voice data is sent. */
internal object SupertonicModelInstall {
    private const val ARCHIVE = "sherpa-onnx-supertonic-3-tts-int8-2026-05-11"
    private const val URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$ARCHIVE.tar.bz2"
    private const val BYTES = 128_774_318
    private const val SHA256 = "82fa96f91c4ef8abaae3a14a3f4153facf88bed821d1f7331cec2700f432c427"
    private const val ASSETS = "supertonic3"
    const val TERMS = "https://huggingface.co/supertone-oss-archive/supertonic-3/blob/aafc6e3/LICENSE"
    private val hosts = setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
    private val modelFiles = mapOf(
        "duration_predictor.int8.onnx" to 3_700_147L,
        "text_encoder.int8.onnx" to 36_416_150L,
        "vector_estimator.int8.onnx" to 78_400_833L,
        "vocoder.int8.onnx" to 25_991_073L,
        "tts.json" to 8_253L,
        "unicode_indexer.bin" to 262_144L,
        "LICENSE" to 1_070L
    )
    private val packedFiles = listOf("voice.bin", "voices.txt", "MODEL-LICENSE.txt", "MODEL-NOTICE.txt")
    private val lock = Mutex()

    fun folder(context: Context) = File(context.filesDir, "supertonic3-v1")
    fun packaged(context: Context) = context.assets.list(ASSETS)?.toSet()?.containsAll(packedFiles) == true
    fun installed(context: Context) = complete(folder(context))

    private fun complete(folder: File): Boolean =
        modelFiles.all { (name, length) -> File(folder, name).length() == length } &&
            File(folder, "voice.bin").length() in setOf(SupertonicVoiceIndex.tableSize(120), SupertonicVoiceIndex.tableSize(170)) &&
            packedFiles.all { File(folder, it).isFile && File(folder, it).length() > 0 }

    suspend fun install(context: Context): Int = lock.withLock {
        withContext(Dispatchers.IO) {
            val target = folder(context)
            if (complete(target)) return@withContext SupertonicVoiceIndex.read(target).size
            check(packaged(context)) { "This build does not include the Supertonic voice styles." }
            check(context.filesDir.usableSpace > 300_000_000L) { "Free at least 300 MB to prepare Supertonic voices." }
            val stage = File(context.filesDir, "supertonic3-stage-" + UUID.randomUUID())
            check(stage.mkdir()) { "Could not prepare Supertonic storage." }
            var committed = false
            try {
                val download = File(stage, "model.tar.bz2")
                val digest = MessageDigest.getInstance("SHA-256")
                DigestOutputStream(download.outputStream().buffered(), digest).use { output ->
                    SafeHttp.copyTo(URL, BYTES, output) { uri ->
                        uri.scheme == "https" && uri.host in hosts && uri.userInfo == null && uri.port in listOf(-1, 443)
                    }
                }
                require(download.length() == BYTES.toLong() && digest.digest().joinToString("") { "%02x".format(it) } == SHA256) {
                    "The Supertonic model did not match its checksum."
                }
                unpack(download, stage)
                check(download.delete()) { "Could not finish Supertonic setup." }
                for (name in packedFiles) {
                    context.assets.open("$ASSETS/$name").use { input ->
                        File(stage, name).outputStream().use { output -> input.copyTo(output) }
                    }
                }
                require(complete(stage)) { "Supertonic voice files are incomplete." }
                val count = SupertonicVoiceIndex.read(stage).size
                currentCoroutineContext().ensureActive()
                check(stage.renameTo(target)) { "Could not install Supertonic voices." }
                committed = true
                count
            } finally {
                if (!committed) stage.deleteRecursively()
            }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun unpack(download: File, stage: File) {
        val written = mutableSetOf<String>()
        TarArchiveInputStream(BZip2CompressorInputStream(download.inputStream().buffered())).use { tar ->
            while (true) {
                val entry = tar.nextTarEntry ?: break
                val name = entry.name.removePrefix("$ARCHIVE/")
                val length = modelFiles[name] ?: continue
                require(entry.isFile && entry.size == length && written.add(name)) { "Invalid Supertonic model entry." }
                File(stage, name).outputStream().buffered().use { output ->
                    val buffer = ByteArray(65536)
                    var remaining = length
                    while (remaining > 0) {
                        currentCoroutineContext().ensureActive()
                        val count = tar.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        require(count > 0) { "The Supertonic archive ended early." }
                        output.write(buffer, 0, count)
                        remaining -= count
                    }
                }
            }
        }
        require(written == modelFiles.keys) { "The Supertonic archive is incomplete." }
    }
}
