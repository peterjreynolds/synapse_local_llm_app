package app.synapse.localllm.application

import app.synapse.localllm.domain.remote.RemoteRoomId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteRoomVisibilityTrackerTest {
    @Test
    fun suppressesOnlySelectedRoomWhileAppIsForegrounded() {
        val tracker = RemoteRoomVisibilityTracker()
        val selectedRoomId = RemoteRoomId("direct_${"a".repeat(64)}")
        val otherRoomId = RemoteRoomId("direct_${"b".repeat(64)}")

        tracker.setSelectedRoom(selectedRoomId)
        assertFalse(tracker.shouldSuppressNotification(selectedRoomId))

        tracker.setAppForegrounded(true)
        assertTrue(tracker.shouldSuppressNotification(selectedRoomId))
        assertFalse(tracker.shouldSuppressNotification(otherRoomId))

        tracker.setSelectedRoom(null)
        assertFalse(tracker.shouldSuppressNotification(selectedRoomId))

        tracker.setSelectedRoom(selectedRoomId)
        tracker.setAppForegrounded(false)
        assertFalse(tracker.shouldSuppressNotification(selectedRoomId))
    }
}
