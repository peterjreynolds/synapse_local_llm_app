package app.synapse.localllm.application

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteNotificationNavigationCoordinatorTest {
    @Test
    fun validRoomIsQueuedAsOneShotTrustedCommand() {
        val coordinator = RemoteNotificationNavigationCoordinator()
        val roomId = "direct_${"a".repeat(64)}"

        assertTrue(coordinator.queueRoom(roomId))
        assertEquals(roomId, coordinator.consumeRoom()?.raw)
        assertNull(coordinator.consumeRoom())
    }

    @Test
    fun validGroupRoomIsQueuedAsOneShotTrustedCommand() {
        val coordinator = RemoteNotificationNavigationCoordinator()
        val roomId = "group_${"b".repeat(32)}"

        assertTrue(coordinator.queueRoom(roomId))
        assertEquals(roomId, coordinator.consumeRoom()?.raw)
        assertNull(coordinator.consumeRoom())
    }

    @Test
    fun malformedRoomNeverBecomesNavigationCommand() {
        val coordinator = RemoteNotificationNavigationCoordinator()

        assertFalse(coordinator.queueRoom("not-a-room"))
        assertNull(coordinator.consumeRoom())
    }
}
