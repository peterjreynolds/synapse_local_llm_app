package app.synapse.privatechat.ui.call

import app.synapse.privatechat.domain.call.PrivateCallMediaKind
import app.synapse.privatechat.domain.call.PrivateCallMediaSignal
import app.synapse.privatechat.domain.call.PrivateCallSession
import app.synapse.privatechat.domain.call.PrivateCallState
import app.synapse.privatechat.domain.chat.PrivateRoomId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

class PrivateCallPermissionRequestOwnerTest {
    @Test
    fun exactConsentRequestConsumesThePermissionResultOnlyOnce() {
        val owner = PrivateCallPermissionRequestOwner()
        val request = incomingRequest()

        assertTrue(owner.begin(request))
        assertTrue(owner.consumeFor(consent(request)))
        assertFalse(owner.consumeFor(consent(request)))
    }

    @Test
    fun structurallyEqualButDifferentRequestCannotConsumeThePermissionResult() {
        val owner = PrivateCallPermissionRequestOwner()
        val request = incomingRequest()
        val replacement = request.copy()
        assertEquals(request, replacement)
        assertNotSame(request, replacement)

        assertTrue(owner.begin(request))
        assertFalse(owner.consumeFor(consent(replacement)))
        assertFalse(owner.consumeFor(consent(request)))
    }

    @Test
    fun expiryThatFinishesConsentInvalidatesThePendingPermissionResult() {
        val owner = PrivateCallPermissionRequestOwner()
        val request = incomingRequest()

        assertTrue(owner.begin(request))
        assertFalse(owner.consumeFor(PrivateCallUiState.Finished("The incoming call expired.")))
        assertFalse(owner.consumeFor(consent(request)))
    }

    @Test
    fun dismissedConsentCannotBeAuthorizedByALateResult() {
        val owner = PrivateCallPermissionRequestOwner()
        val request = incomingRequest()

        assertTrue(owner.begin(request))
        assertFalse(owner.consumeFor(PrivateCallUiState.Idle))
        assertFalse(owner.consumeFor(consent(request)))
    }

    @Test
    fun anotherRequestCannotReplaceAPendingPermissionRequest() {
        val owner = PrivateCallPermissionRequestOwner()
        val first = incomingRequest()
        val second = incomingRequest()

        assertTrue(owner.begin(first))
        assertFalse(owner.begin(second))
        assertTrue(owner.consumeFor(consent(first)))
        assertTrue(owner.begin(second))
        assertTrue(owner.consumeFor(consent(second)))
    }

    @Test
    fun staleGrantCannotStartTheNewCallOrPreventItsOwnPermissionRequest() {
        val owner = PrivateCallPermissionRequestOwner()
        val oldRequest = incomingRequest()
        val newRequest = incomingRequest()

        assertTrue(owner.begin(oldRequest))
        assertFalse(owner.begin(newRequest))
        // The overlay checks this result before applying either Android grant or denial.
        assertFalse(owner.consumeFor(consent(newRequest)))
        assertTrue(owner.begin(newRequest))
        assertTrue(owner.consumeFor(consent(newRequest)))
        assertFalse(owner.consumeFor(consent(newRequest)))
    }

    @Test
    fun staleDenialCannotCancelANewStructurallyEqualConsentRequest() {
        val owner = PrivateCallPermissionRequestOwner()
        val oldRequest = incomingRequest()
        val newRequest = oldRequest.copy()

        assertTrue(owner.begin(oldRequest))
        assertFalse(owner.consumeFor(consent(newRequest)))
        assertTrue(owner.begin(newRequest))
        assertTrue(owner.consumeFor(consent(newRequest)))
    }
}

private fun consent(request: PrivateCallConsentRequest) = PrivateCallUiState.Consent(request, emptyList())

private fun incomingRequest(): PrivateCallConsentRequest.Incoming {
    val createdAt = Instant.parse("2026-09-20T07:00:00Z")
    return PrivateCallConsentRequest.Incoming(
        session =
            PrivateCallSession(
                callId = UUID.randomUUID(),
                roomId = PrivateRoomId("11111111-1111-4111-8111-111111111111"),
                membershipEpoch = 1,
                callerAccountId = UUID.fromString("22222222-2222-4222-8222-222222222222"),
                callerDeviceId = UUID.fromString("33333333-3333-4333-8333-333333333333"),
                recipientAccountId = UUID.fromString("44444444-4444-4444-8444-444444444444"),
                acceptedDeviceId = null,
                mediaKind = PrivateCallMediaKind.VIDEO,
                state = PrivateCallState.RINGING,
                terminalReason = null,
                createdAt = createdAt,
                ringExpiresAt = createdAt.plusSeconds(60),
                leaseExpiresAt = createdAt.plusSeconds(60),
            ),
        offer = PrivateCallMediaSignal.Offer("permission-owner fixture"),
        title = "Invited contact",
    )
}
