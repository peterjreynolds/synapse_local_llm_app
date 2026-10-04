package app.synapse.privatechat.ui.chat

import app.synapse.privatechat.domain.account.PrivateAccountId
import app.synapse.privatechat.domain.chat.PrivateChatMutationOutcome
import app.synapse.privatechat.domain.chat.PrivateChatObservation
import app.synapse.privatechat.domain.chat.PrivateDirectoryPerson
import app.synapse.privatechat.domain.chat.PrivatePeopleGateway
import app.synapse.privatechat.domain.chat.PrivateRoomId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant

enum class PrivatePeopleAvailability { LOADING, AVAILABLE, UNAVAILABLE }

sealed interface PrivateDirectChatUiState {
    data object Idle : PrivateDirectChatUiState

    data object Opening : PrivateDirectChatUiState

    data class Confirmed(
        val roomId: PrivateRoomId,
    ) : PrivateDirectChatUiState

    data object Unavailable : PrivateDirectChatUiState
}

data class PrivatePeopleUiState(
    val people: List<PrivateDirectoryPerson> = emptyList(),
    val availability: PrivatePeopleAvailability = PrivatePeopleAvailability.LOADING,
    val activityAvailability: PrivatePeopleAvailability = PrivatePeopleAvailability.LOADING,
    val directChat: PrivateDirectChatUiState = PrivateDirectChatUiState.Idle,
    val now: Instant = Instant.EPOCH,
)

internal class PrivatePeopleCoordinator(
    private val gateway: PrivatePeopleGateway,
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val onDirectRoomConfirmed: (PrivateRoomId) -> Unit,
) {
    private val mutableState = MutableStateFlow(PrivatePeopleUiState())
    val state = mutableState.asStateFlow()
    private var accountId: PrivateAccountId? = null
    private var foreground = false
    private var directoryJob: Job? = null
    private var heartbeatJob: Job? = null
    private var expiryJob: Job? = null
    private var openingJob: Job? = null

    fun activateAccount(accountId: PrivateAccountId) {
        deactivateAccount()
        this.accountId = accountId
        reconcile()
    }

    fun enterForeground() {
        foreground = true
        reconcile()
    }

    fun leaveForeground() {
        foreground = false
        stopPublication()
    }

    fun deactivateAccount() {
        stopPublication()
        openingJob?.cancel()
        openingJob = null
        accountId = null
        mutableState.value = PrivatePeopleUiState()
    }

    fun acknowledgeRoomOpened() {
        mutableState.update { it.copy(directChat = PrivateDirectChatUiState.Idle) }
    }

    fun openChat(targetAccountId: PrivateAccountId) {
        val actor = accountId ?: return
        if (openingJob?.isActive == true) return
        openingJob =
            scope.launch {
                mutableState.update { it.copy(directChat = PrivateDirectChatUiState.Opening) }
                val outcome =
                    try {
                        gateway.openDirectConversation(actor, targetAccountId)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        PrivateChatMutationOutcome.TransportUnavailable
                    }
                if (accountId != actor) return@launch
                if (outcome is PrivateChatMutationOutcome.Confirmed &&
                    outcome.receipt.accountId == actor &&
                    outcome.receipt.targetAccountId == targetAccountId &&
                    actor != targetAccountId
                ) {
                    mutableState.update { it.copy(directChat = PrivateDirectChatUiState.Confirmed(outcome.receipt.roomId)) }
                    onDirectRoomConfirmed(outcome.receipt.roomId)
                } else {
                    mutableState.update { it.copy(directChat = PrivateDirectChatUiState.Unavailable) }
                }
            }
    }

    private fun reconcile() {
        val actor = accountId ?: return
        if (!foreground || directoryJob?.isActive == true) return
        directoryJob =
            scope.launch {
                var failures = 0
                while (isActive) {
                    val observation =
                        try {
                            gateway.loadPeople(actor)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            PrivateChatObservation.TransportUnavailable
                        }
                    when (observation) {
                        is PrivateChatObservation.Available -> {
                            failures = 0
                            mutableState.update {
                                it.copy(
                                    people = observation.snapshot,
                                    availability = PrivatePeopleAvailability.AVAILABLE,
                                    now = clock.instant(),
                                )
                            }
                        }
                        PrivateChatObservation.TransportUnavailable -> {
                            failures = (failures + 1).coerceAtMost(3)
                            mutableState.update { it.copy(availability = PrivatePeopleAvailability.UNAVAILABLE, now = clock.instant()) }
                        }
                    }
                    delay(10_000L * (1L shl failures))
                }
            }
        heartbeatJob =
            scope.launch {
                while (isActive) {
                    val publication =
                        try {
                            gateway.publishActivity(actor)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            PrivateChatMutationOutcome.TransportUnavailable
                        }
                    mutableState.update {
                        it.copy(
                            activityAvailability =
                                if (publication is PrivateChatMutationOutcome.Confirmed) {
                                    PrivatePeopleAvailability.AVAILABLE
                                } else {
                                    PrivatePeopleAvailability.UNAVAILABLE
                                },
                        )
                    }
                    delay(25_000L)
                }
            }
        expiryJob =
            scope.launch {
                while (isActive) {
                    mutableState.update { it.copy(now = clock.instant()) }
                    delay(1_000L)
                }
            }
    }

    private fun stopPublication() {
        directoryJob?.cancel()
        directoryJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        expiryJob?.cancel()
        expiryJob = null
    }
}
