package com.daya.velum.video

import android.net.Uri

sealed interface RecState {
    data object Idle : RecState
    data object Preparing : RecState
    data class Recording(val startedAtMs: Long, val elapsedNs: Long = 0, val bytes: Long = 0, val audioAmplitude: Double = 0.0) : RecState
    data class Paused(val elapsedNs: Long, val bytes: Long) : RecState
    data object Finalizing : RecState
    data class Saved(val uri: Uri, val durationMs: Long, val width: Int, val height: Int, val note: String? = null) : RecState
    data class Error(val message: String, val uri: Uri? = null) : RecState

    val isActive get() = this is Preparing || this is Recording || this is Paused || this is Finalizing
}

sealed interface RecEvent {
    data object StartRequested : RecEvent
    data class Started(val atMs: Long) : RecEvent
    data class Status(val elapsedNs: Long, val bytes: Long, val amplitude: Double) : RecEvent
    data object PauseRequested : RecEvent
    data object Paused : RecEvent
    data object Resumed : RecEvent
    data object StopRequested : RecEvent
    data class Finalized(val uri: Uri?, val errorCode: Int, val errorMessage: String?) : RecEvent
    data class Verified(val uri: Uri, val durationMs: Long, val width: Int, val height: Int, val note: String?) : RecEvent
    data class VerifyFailed(val uri: Uri?, val reason: String) : RecEvent
    data object Reset : RecEvent
}

/**
 * Legal transitions only; anything else returns null and the caller ignores the event. This is what prevents double
 * starts and stop-before-start races.
 */
object RecordingStateMachine {
    fun reduce(s: RecState, e: RecEvent): RecState? = when (e) {
        RecEvent.StartRequested -> if (s is RecState.Idle || s is RecState.Saved || s is RecState.Error) RecState.Preparing else null
        is RecEvent.Started -> if (s is RecState.Preparing) RecState.Recording(e.atMs) else null
        is RecEvent.Status -> when (s) {
            is RecState.Recording -> s.copy(elapsedNs = e.elapsedNs, bytes = e.bytes, audioAmplitude = e.amplitude)
            is RecState.Paused -> s.copy(elapsedNs = e.elapsedNs, bytes = e.bytes)
            else -> null
        }
        RecEvent.PauseRequested -> null // pause is reflected only once the recorder confirms (Paused)
        RecEvent.Paused -> if (s is RecState.Recording) RecState.Paused(s.elapsedNs, s.bytes) else null
        RecEvent.Resumed -> if (s is RecState.Paused) RecState.Recording(System.currentTimeMillis(), s.elapsedNs, s.bytes) else null
        RecEvent.StopRequested -> if (s is RecState.Recording || s is RecState.Paused) RecState.Finalizing else null
        // A finalize can arrive from any active state (e.g. camera lost, storage full, source inactive).
        is RecEvent.Finalized -> if (s.isActive) {
            if (e.uri == null || e.uri == Uri.EMPTY) RecState.Error(e.errorMessage ?: "Recording failed (code ${e.errorCode})")
            else RecState.Finalizing
        } else null
        is RecEvent.Verified -> if (s is RecState.Finalizing) RecState.Saved(e.uri, e.durationMs, e.width, e.height, e.note) else null
        is RecEvent.VerifyFailed -> if (s is RecState.Finalizing || s.isActive) RecState.Error(e.reason, e.uri) else null
        RecEvent.Reset -> if (!s.isActive) RecState.Idle else null
    }
}
