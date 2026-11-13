package app.roadleaf

import app.roadleaf.playback.KokoroModelImport
import org.junit.Assert.*
import org.junit.Test

class KokoroModelImportTest {
    @Test fun officialDirectoryEntriesPermitTheirNormalTrailingSlash() {
        assertNull(KokoroModelImport.relativePath("kokoro-int8-multi-lang-v1_0/", true))
        assertEquals("espeak-ng-data", KokoroModelImport.relativePath("kokoro-int8-multi-lang-v1_0/espeak-ng-data/", true))
        assertEquals("espeak-ng-data/lang/en", KokoroModelImport.relativePath("./kokoro-int8-multi-lang-v1_0/espeak-ng-data/lang/en", false))
        assertNull(KokoroModelImport.relativePath("another-model/model.onnx", false))
    }
    @Test fun archiveTraversalAndMalformedPathsRemainRejected() {
        for (path in listOf("../outside", "lang/../../outside", "/absolute", "lang//en", "lang/./en", "..\\outside", "tokens.txt/")) {
            assertThrows(IllegalArgumentException::class.java) {
                KokoroModelImport.relativePath("kokoro-int8-multi-lang-v1_0/$path", false)
            }
        }
    }
}
