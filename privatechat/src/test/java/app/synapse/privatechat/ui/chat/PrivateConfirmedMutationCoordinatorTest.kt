package app.synapse.privatechat.ui.chat

import app.synapse.privatechat.domain.chat.PrivateChatMutationOutcome
import app.synapse.privatechat.domain.chat.PrivateSocialMutationReceipt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PrivateConfirmedMutationCoordinatorTest {
    @Test
    fun transportFailureRunsReconnectPolicyBeforePublishingFailure() =
        runTest {
            val stateStore = PrivateChatUiStateStore()
            val coordinator = PrivateConfirmedMutationCoordinator(this, stateStore)
            var reconnectPolicyRan = false

            coordinator.requestConfirmedMutation<PrivateSocialMutationReceipt.RoomInvitationRedeemed>(
                kind = PrivateChatOperationKind.REDEEM_ROOM_INVITATION,
                request = { PrivateChatMutationOutcome.TransportUnavailable },
                receiptMatches = { true },
                onTransportUnavailable = { reconnectPolicyRan = true },
            )
            runCurrent()

            assertTrue(reconnectPolicyRan)
            assertSame(PrivateChatOperationUiState.TransportUnavailable, stateStore.current.operation)
        }

    @Test
    fun targetedCancellationStopsSuspendedRoomRedemptionAndClearsRunningState() =
        runTest {
            val requestStarted = CompletableDeferred<Unit>()
            val cancellationObserved = CompletableDeferred<Unit>()
            val stateStore = PrivateChatUiStateStore()
            val coordinator = PrivateConfirmedMutationCoordinator(this, stateStore)

            coordinator.requestConfirmedMutation<PrivateSocialMutationReceipt.RoomInvitationRedeemed>(
                kind = PrivateChatOperationKind.REDEEM_ROOM_INVITATION,
                request = {
                    requestStarted.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        cancellationObserved.complete(Unit)
                    }
                },
                receiptMatches = { true },
            )
            runCurrent()
            requestStarted.await()

            coordinator.cancelPendingMutation(PrivateChatOperationKind.REDEEM_ROOM_INVITATION)
            runCurrent()

            assertSame(PrivateChatOperationUiState.Idle, stateStore.current.operation)
            assertSame(true, cancellationObserved.isCompleted)
        }
}
