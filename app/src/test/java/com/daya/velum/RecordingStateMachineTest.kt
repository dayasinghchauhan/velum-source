package com.daya.velum

import android.net.Uri
import com.daya.velum.video.RecEvent
import com.daya.velum.video.RecState
import com.daya.velum.video.RecordingStateMachine.reduce
import org.junit.Assert.*
import org.junit.Test

class RecordingStateMachineTest {
    private val uri: Uri = org.mockito.Mockito.mock(Uri::class.java)

    @Test fun happyPath() {
        var s: RecState = RecState.Idle
        s = reduce(s, RecEvent.StartRequested)!!; assertEquals(RecState.Preparing, s)
        s = reduce(s, RecEvent.Started(1))!!; assertTrue(s is RecState.Recording)
        s = reduce(s, RecEvent.Status(5, 10, 0.1))!!; assertEquals(5L, (s as RecState.Recording).elapsedNs)
        s = reduce(s, RecEvent.Paused)!!; assertTrue(s is RecState.Paused)
        s = reduce(s, RecEvent.Resumed)!!; assertTrue(s is RecState.Recording)
        s = reduce(s, RecEvent.StopRequested)!!; assertEquals(RecState.Finalizing, s)
        s = reduce(s, RecEvent.Verified(uri, 1000, 3840, 2160, null))!!; assertTrue(s is RecState.Saved)
        s = reduce(s, RecEvent.Reset)!!; assertEquals(RecState.Idle, s)
    }

    @Test fun doubleStartIsRejected() {
        val s = reduce(RecState.Idle, RecEvent.StartRequested)!!
        assertNull(reduce(s, RecEvent.StartRequested))
        assertNull(reduce(RecState.Recording(0), RecEvent.StartRequested))
    }

    @Test fun stopBeforeStartIsRejected() {
        assertNull(reduce(RecState.Idle, RecEvent.StopRequested))
        assertNull(reduce(RecState.Preparing, RecEvent.StopRequested))
    }

    @Test fun savedNeedsVerification() {
        assertNull(reduce(RecState.Recording(0), RecEvent.Verified(uri, 1, 1, 1, null)))
    }

    @Test fun finalizeWithoutFileIsError() {
        val s = reduce(RecState.Recording(0), RecEvent.Finalized(null, 4, "encoder"))
        assertTrue(s is RecState.Error)
    }

    @Test fun cannotResetWhileActive() {
        assertNull(reduce(RecState.Recording(0), RecEvent.Reset))
        assertNull(reduce(RecState.Finalizing, RecEvent.Reset))
    }

    @Test fun pauseOnlyWhenRecording() {
        assertNull(reduce(RecState.Idle, RecEvent.Paused))
        assertNull(reduce(RecState.Paused(0, 0), RecEvent.Paused))
    }

    @Test fun startAllowedAfterErrorOrSave() {
        assertEquals(RecState.Preparing, reduce(RecState.Error("x"), RecEvent.StartRequested))
        assertEquals(RecState.Preparing, reduce(RecState.Saved(uri, 1, 1, 1), RecEvent.StartRequested))
    }
}
