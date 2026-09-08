package app.synapse.privatechat.ui.chat

import app.synapse.privatechat.domain.chat.PrivateChatInputField
import app.synapse.privatechat.domain.chat.PrivateChatMutationOutcome
import app.synapse.privatechat.domain.chat.PrivateMutationReceipt
import app.synapse.privatechat.domain.chat.PrivateSocialInputField
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

internal class PrivateConfirmedMutationCoordinator(
    private val coroutineScope: CoroutineScope,
    private val stateStore: PrivateChatUiStateStore,
) {
    private val mutationInFlight = AtomicBoolean(false)
    private var mutationJob: Job? = null
    private var mutationKind: PrivateChatOperationKind? = null

    fun rejectChatInput(
        field: PrivateChatInputField,
        userMessage: String,
    ) {
        stateStore.update { state ->
            state.copy(operation = PrivateChatOperationUiState.InvalidInput(field, userMessage))
        }
    }

    fun rejectSocialInput(
        field: PrivateSocialInputField,
        userMessage: String,
    ) {
        stateStore.update { state ->
            state.copy(
                operation =
                    PrivateChatOperationUiState.Rejected(
                        when (field) {
                            PrivateSocialInputField.DISPLAY_NAME -> "Profile: $userMessage"
                            PrivateSocialInputField.ROOM_TITLE -> "Conversation: $userMessage"
                        },
                    ),
            )
        }
    }

    fun rejectAction(userMessage: String) {
        stateStore.update { state ->
            state.copy(operation = PrivateChatOperationUiState.Rejected(userMessage))
        }
    }

    fun dismissNotice() {
        if (mutationInFlight.get()) return
        stateStore.update { state -> state.copy(operation = PrivateChatOperationUiState.Idle) }
    }

    fun cancelPendingMutation(expectedKind: PrivateChatOperationKind? = null) {
        if (expectedKind != null && mutationKind != expectedKind) return
        val cancelledKind = mutationKind
        mutationJob?.cancel()
        mutationJob = null
        mutationKind = null
        mutationInFlight.set(false)
        if (cancelledKind != null) {
            stateStore.update { state ->
                val running = state.operation as? PrivateChatOperationUiState.Running
                if (running?.kind == cancelledKind) state.copy(operation = PrivateChatOperationUiState.Idle) else state
            }
        }
    }

    fun <Receipt : PrivateMutationReceipt> requestConfirmedMutation(
        kind: PrivateChatOperationKind,
        request: suspend () -> PrivateChatMutationOutcome<Receipt>,
        receiptMatches: (Receipt) -> Boolean,
        onConfirmed: (Receipt) -> Unit = {},
        onTransportUnavailable: () -> Unit = {},
    ) {
        if (!mutationInFlight.compareAndSet(false, true)) return
        stateStore.update { state -> state.copy(operation = PrivateChatOperationUiState.Running(kind)) }
        val launchedJob =
            coroutineScope.launch(start = CoroutineStart.LAZY) {
                val ownedJob = currentCoroutineContext().job
                val operationState =
                    try {
                        when (val outcome = request()) {
                            is PrivateChatMutationOutcome.Confirmed -> {
                                if (receiptMatches(outcome.receipt)) {
                                    onConfirmed(outcome.receipt)
                                    PrivateChatOperationUiState.Confirmed(outcome.receipt)
                                } else {
                                    PrivateChatOperationUiState.UnexpectedFailure
                                }
                            }

                            is PrivateChatMutationOutcome.Rejected ->
                                PrivateChatOperationUiState.Rejected(outcome.userMessage)

                            PrivateChatMutationOutcome.TransportUnavailable -> {
                                onTransportUnavailable()
                                PrivateChatOperationUiState.TransportUnavailable
                            }
                        }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Exception) {
                        PrivateChatOperationUiState.UnexpectedFailure
                    } finally {
                        if (mutationJob === ownedJob) {
                            mutationJob = null
                            mutationKind = null
                            mutationInFlight.set(false)
                        }
                    }
                stateStore.update { state -> state.copy(operation = operationState) }
            }
        mutationJob = launchedJob
        mutationKind = kind
        launchedJob.start()
    }
}
