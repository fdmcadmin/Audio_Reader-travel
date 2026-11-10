package app.roadleaf.playback

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class SpeechSynthesisStopped : Exception("The local speech request stopped before completion")

/** A provider timeout is recoverable work failure; the owner's cancellation is not. */
object SpeechRecovery {
    suspend fun <T> run(reset: suspend () -> Unit, attempt: suspend () -> T): T {
        repeat(2) { index ->
            try { return attempt() }
            catch (failure: Exception) {
                if (failure !is TimeoutCancellationException && failure !is SpeechSynthesisStopped) throw failure
                currentCoroutineContext().ensureActive()
                if (index == 1) throw IllegalStateException("The local voice stopped responding after one retry. Continue from the saved reading position.", failure)
                reset()
            }
        }
        error("Unreachable speech recovery state")
    }
}
