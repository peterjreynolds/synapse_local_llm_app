package app.synapse.localllm.data.remote

import app.synapse.localllm.domain.remote.RemoteAccountRole
import app.synapse.localllm.domain.remote.RemoteAccountState
import app.synapse.localllm.domain.remote.RemoteChatException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FirebaseOwnerAdminGatewayTest {
    @Test
    fun ownerAccountResponsesAreNarrowedIntoExplicitSummaries() {
        val accounts = parseOwnerAccountSummaries(
            mapOf(
                "accounts" to listOf(
                    mapOf(
                        "accountState" to "ACTIVE",
                        "createdAtMillis" to 1_000L,
                        "displayName" to "Trish",
                        "lastSeenAtMillis" to 2_000L,
                        "mustChangePassword" to false,
                        "role" to "USER",
                        "uid" to "trish-uid",
                        "usernameNormalized" to "trish",
                    ),
                ),
            ),
        )

        assertEquals(1, accounts.size)
        assertEquals("trish", accounts.single().usernameNormalized)
        assertEquals(RemoteAccountRole.USER, accounts.single().role)
        assertEquals(RemoteAccountState.ACTIVE, accounts.single().state)
        assertEquals(2_000L, accounts.single().lastSeenAtMillis)
    }

    @Test
    fun malformedOwnerResponsesFailClosed() {
        assertThrows(RemoteChatException::class.java) {
            parseOwnerAccountSummaries(
                mapOf(
                    "accounts" to listOf(
                        mapOf(
                            "accountState" to "ACTIVE",
                            "createdAtMillis" to 1.5,
                            "displayName" to "Trish",
                            "lastSeenAtMillis" to null,
                            "mustChangePassword" to false,
                            "role" to "OWNERISH",
                            "uid" to "trish-uid",
                            "usernameNormalized" to "trish",
                        ),
                    ),
                ),
            )
        }
        assertThrows(RemoteChatException::class.java) {
            parseOwnerDeviceSummaries(
                mapOf(
                    "devices" to listOf(
                        mapOf(
                            "active" to true,
                            "deviceId" to "device-id",
                            "platform" to "IOS",
                            "updatedAtMillis" to 1_000L,
                        ),
                    ),
                ),
            )
        }
    }

    @Test
    fun ownerOperationsResponsesAreNarrowedWithoutLeakingBackendRecords() {
        val operations = parseOwnerOperationsSummary(
            mapOf(
                "activeDeviceCount" to 2,
                "activeRoomCount" to 3,
                "attachmentCleanup" to cleanup("NEVER_RUN"),
                "backendRevision" to "revision-1",
                "backendState" to "HEALTHY",
                "failedNotificationDeliveryCount" to 1,
                "generatedAtMillis" to 4_000L,
                "integrity" to mapOf(
                    "checkedRoomCount" to 3,
                    "issueCodes" to listOf("ACTIVE_MEMBERSHIP_MISSING"),
                    "issueCount" to 1,
                    "sampleLimit" to 25,
                    "sampleLimitReached" to false,
                ),
                "operationalDataCleanup" to cleanup("SUCCEEDED", 7),
                "pendingNotificationDeliveryCount" to 4,
                "totalDeviceCount" to 5,
            ),
        )

        assertEquals("revision-1", operations.backendRevision)
        assertEquals(1, operations.integrity.issueCount)
        assertEquals(7, operations.operationalDataCleanup.affectedDocumentCount)
    }
}

private fun cleanup(
    state: String,
    affectedDocumentCount: Int? = null,
): Map<String, Any?> = mapOf(
    "affectedDocumentCount" to affectedDocumentCount,
    "lastCompletedAtMillis" to null,
    "lastStartedAtMillis" to null,
    "state" to state,
)
