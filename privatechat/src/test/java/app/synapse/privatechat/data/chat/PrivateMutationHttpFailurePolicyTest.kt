package app.synapse.privatechat.data.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateMutationHttpFailurePolicyTest {
    @Test
    fun serverFailuresRetainTheOriginalPendingMutation() {
        for (statusCode in 500..599) {
            assertTrue(
                "HTTP $statusCode must retain the pending request",
                shouldRetainPrivateMutationAfterHttpFailure(statusCode),
            )
        }
    }

    @Test
    fun authenticationRefreshTimeoutAndThrottlingRetainThePendingMutation() {
        for (statusCode in listOf(401, 408, 425, 429)) {
            assertTrue(
                "HTTP $statusCode must retain the pending request",
                shouldRetainPrivateMutationAfterHttpFailure(statusCode),
            )
        }
    }

    @Test
    fun otherClientRejectionsKeepTheirExistingCleanupBehavior() {
        for (statusCode in 400..499) {
            if (statusCode in setOf(401, 408, 425, 429)) continue
            assertFalse(
                "HTTP $statusCode must keep its previous cleanup behavior",
                shouldRetainPrivateMutationAfterHttpFailure(statusCode),
            )
        }
    }
}
