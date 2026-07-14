package app.synapse.localllm.data.remote

import app.synapse.localllm.domain.remote.RemoteChatException
import app.synapse.localllm.domain.remote.RemoteProfileUid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FirebaseRemotePrivacyGatewayTest {
    @Test
    fun privacyStateParsesOnlyTypedBlockAndDeletionFields() {
        val state = parseRemotePrivacyState(
            mapOf(
                "blockedUids" to listOf("trish-uid", "josh-uid"),
                "deletionRequestPending" to true,
            ),
        )

        assertEquals(
            setOf(RemoteProfileUid("trish-uid"), RemoteProfileUid("josh-uid")),
            state.blockedProfileUids,
        )
        assertEquals(true, state.deletionRequestPending)
    }

    @Test
    fun privacyStateFailsClosedForMalformedOrDuplicateUids() {
        assertThrows(RemoteChatException::class.java) {
            parseRemotePrivacyState(
                mapOf(
                    "blockedUids" to listOf("trish-uid", "trish-uid"),
                    "deletionRequestPending" to false,
                ),
            )
        }
        assertThrows(RemoteChatException::class.java) {
            parseRemotePrivacyState(
                mapOf(
                    "blockedUids" to emptyList<String>(),
                    "deletionRequestPending" to false,
                    "unexpectedInternalField" to "must not cross the contract",
                ),
            )
        }
        assertThrows(RemoteChatException::class.java) {
            parseRemotePrivacyState(
                mapOf(
                    "blockedUids" to listOf(42),
                    "deletionRequestPending" to false,
                ),
            )
        }
    }
}
