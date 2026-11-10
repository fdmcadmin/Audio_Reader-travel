package app.roadleaf.playback

import android.os.Bundle
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Wait for destructive queue changes to finish before sending text for the replacement source. */
@UnstableApi
suspend fun MediaController.awaitCommand(action: String, args: Bundle = Bundle.EMPTY): SessionResult =
    suspendCancellableCoroutine { continuation ->
        val future = sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args)
        future.addListener({
            if (continuation.isActive) try {
                val result = future.get()
                check(result.resultCode == SessionResult.RESULT_SUCCESS) { result.extras.getString("error") ?: "Could not complete the request (${result.resultCode})" }
                continuation.resume(result)
            } catch (error: Exception) { continuation.resumeWithException(error.cause ?: error) }
        }, MoreExecutors.directExecutor())
        continuation.invokeOnCancellation { future.cancel(false) }
    }
