package app.synapse.privatechat.ui.chat

import app.synapse.privatechat.domain.account.PrivateAccountId
import app.synapse.privatechat.domain.chat.PrivateChatMutationOutcome
import app.synapse.privatechat.domain.chat.PrivateClientMutationIdFactory
import app.synapse.privatechat.domain.chat.PrivatePresenceSharingState
import app.synapse.privatechat.domain.chat.PrivateSocialGateway
import app.synapse.privatechat.domain.chat.PublishPrivatePresenceCommand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Clock

internal class PrivatePresencePublisher(
    private val gateway: PrivateSocialGateway,
    private val mutationIdFactory: PrivateClientMutationIdFactory,
    private val clock: Clock,
    private val coroutineScope: CoroutineScope,
    private val stateStore: PrivateChatUiStateStore,
) {
    private var accountId: PrivateAccountId? = null
    private var foreground = false
    private var sharingState = PrivatePresenceSharingState.DISABLED
    private var publicationJob: Job? = null

    fun activateAccount(accountId: PrivateAccountId) {
        this.accountId = accountId
        reconcilePublication()
    }

    fun updateSharingState(sharingState: PrivatePresenceSharingState) {
        this.sharingState = sharingState
        reconcilePublication()
    }

    fun enterForeground() {
        foreground = true
        reconcilePublication()
    }

    fun leaveForeground() {
        foreground = false
        reconcilePublication()
    }

    fun deactivateAccount() {
        publicationJob?.cancel()
        publicationJob = null
        accountId = null
        sharingState = PrivatePresenceSharingState.DISABLED
        stateStore.update { state ->
            state.copy(presencePublication = PrivatePresencePublicationUiState.NotSharing)
        }
    }

    private fun reconcilePublication() {
        val activeAccountId = accountId
        if (sharingState == PrivatePresenceSharingState.DISABLED || activeAccountId == null) {
            publicationJob?.cancel()
            publicationJob = null
            stateStore.update { state ->
                state.copy(presencePublication = PrivatePresencePublicationUiState.NotSharing)
            }
            return
        }
        if (!foreground) {
            publicationJob?.cancel()
            publicationJob = null
            stateStore.update { state ->
                state.copy(presencePublication = PrivatePresencePublicationUiState.Background)
            }
            return
        }
        if (publicationJob?.isActive == true) return
        publicationJob =
            coroutineScope.launch {
                while (isActive && accountId == activeAccountId && foreground) {
                    stateStore.update { state ->
                        state.copy(presencePublication = PrivatePresencePublicationUiState.Publishing)
                    }
                    val publicationState = publishPresence(activeAccountId)
                    if (!isActive || accountId != activeAccountId || !foreground) return@launch
                    stateStore.update { state ->
                        state.copy(presencePublication = publicationState)
                    }
                    delay(PRIVATE_PRESENCE_REPUBLICATION_MILLIS)
                }
            }
    }

    private suspend fun publishPresence(accountId: PrivateAccountId): PrivatePresencePublicationUiState {
        val publishedAt = clock.instant()
        val command =
            PublishPrivatePresenceCommand(
                accountId = accountId,
                mutationId = mutationIdFactory.createMutationId(),
                publishedAt = publishedAt,
                expiresAt = publishedAt.plusSeconds(PRIVATE_PRESENCE_TTL_SECONDS),
            )
        return try {
            when (val outcome = gateway.publishPresence(command)) {
                is PrivateChatMutationOutcome.Confirmed ->
                    if (PrivateSocialReceiptValidator.matches(outcome.receipt, command)) {
                        PrivatePresencePublicationUiState.Confirmed(outcome.receipt.expiresAt)
                    } else {
                        PrivatePresencePublicationUiState.UnexpectedFailure
                    }

                is PrivateChatMutationOutcome.Rejected ->
                    PrivatePresencePublicationUiState.UnexpectedFailure

                PrivateChatMutationOutcome.TransportUnavailable ->
                    PrivatePresencePublicationUiState.TransportUnavailable
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            PrivatePresencePublicationUiState.UnexpectedFailure
        }
    }
}

private const val PRIVATE_PRESENCE_TTL_SECONDS = 120L
private const val PRIVATE_PRESENCE_REPUBLICATION_MILLIS = 60_000L
