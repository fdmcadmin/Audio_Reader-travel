package app.roadleaf

import app.roadleaf.playback.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SpeechRecoveryTest {
    @Test fun interruptedProviderRetriesTheSameWorkOnce() = runBlocking {
        var attempts = 0; var resets = 0
        val result = SpeechRecovery.run(reset = { resets++ }) { if (attempts++ == 0) throw SpeechSynthesisStopped() else "same source" }
        assertEquals("same source", result); assertEquals(2, attempts); assertEquals(1, resets)
    }
    @Test fun localDeadlineRetriesWithoutCancellingTheOwner() = runBlocking {
        var attempts = 0; var resets = 0
        val result = SpeechRecovery.run(reset = { resets++ }) { if (attempts++ == 0) withTimeout(10) { delay(100); "late" } else "ready" }
        assertEquals("ready", result); assertEquals(1, resets); assertTrue(currentCoroutineContext().isActive)
    }
    @Test fun parentCancellationNeverReconnectsOrRetries() = runBlocking {
        var attempts = 0; var resets = 0
        try { withTimeout(10) { SpeechRecovery.run(reset = { resets++ }) { attempts++; delay(100); "late" } }; fail("Expected owner cancellation") }
        catch (_: TimeoutCancellationException) { }
        assertEquals(1, attempts); assertEquals(0, resets)
    }
    @Test fun exhaustedProviderFailureBecomesAReportedErrorRatherThanSilentCancellation() = runBlocking {
        var attempts = 0; var resets = 0
        try { SpeechRecovery.run(reset = { resets++ }) { attempts++; throw SpeechSynthesisStopped() }; fail("Expected reported failure") }
        catch (failure: IllegalStateException) { assertFalse(failure is CancellationException); assertTrue(failure.message.orEmpty().contains("saved reading position")) }
        assertEquals(2, attempts); assertEquals(1, resets)
    }
    @Test fun permanentFailuresAreNotRetried() = runBlocking {
        var resets = 0
        try { SpeechRecovery.run(reset = { resets++ }) { throw IllegalArgumentException("Voice unavailable") }; fail("Expected permanent failure") }
        catch (_: IllegalArgumentException) { }
        assertEquals(0, resets)
    }
}
