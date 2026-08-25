package app.synapse.privatechat.data.chat

import app.synapse.privatechat.data.supabase.SupabaseTransportException
import app.synapse.privatechat.domain.account.PrivateAccountId
import app.synapse.privatechat.domain.chat.PrivateChatMutationOutcome
import app.synapse.privatechat.domain.chat.PrivateChatObservation
import kotlinx.coroutines.CancellationException

internal class PrivateChatGatewayExecution(
    private val sessionResolver: PrivateChatSessionResolver,
) {
    suspend fun <Receipt> mutate(
        accountId: PrivateAccountId,
        mutation: suspend (PrivateChatAuthenticatedSession) -> Receipt,
    ): PrivateChatMutationOutcome<Receipt> {
        val session =
            try {
                sessionResolver.resolve(accountId)
            } catch (error: Exception) {
                return PrivateChatMutationOutcome.TransportUnavailable
            } ?: return PrivateChatMutationOutcome.TransportUnavailable
        return try {
            PrivateChatMutationOutcome.Confirmed(mutation(session))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (rejection: SupabasePrivateChatRequestRejectedException) {
            PrivateChatMutationOutcome.Rejected(rejection.userMessage)
        } catch (rejection: PrivateChatCommandRejectedException) {
            PrivateChatMutationOutcome.Rejected(rejection.userMessage)
        } catch (transportFailure: SupabaseTransportException) {
            PrivateChatMutationOutcome.TransportUnavailable
        } catch (invalidRemoteState: Exception) {
            PrivateChatMutationOutcome.TransportUnavailable
        }
    }

    suspend fun <Snapshot> observe(
        accountId: PrivateAccountId,
        loadSnapshot: suspend (PrivateChatAuthenticatedSession) -> Snapshot,
    ): PrivateChatObservation<Snapshot> {
        val session =
            try {
                sessionResolver.resolve(accountId)
            } catch (error: Exception) {
                return PrivateChatObservation.TransportUnavailable
            } ?: return PrivateChatObservation.TransportUnavailable
        return try {
            PrivateChatObservation.Available(loadSnapshot(session))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            PrivateChatObservation.TransportUnavailable
        }
    }
}

internal class PrivateChatCommandRejectedException(
    val userMessage: String,
) : IllegalStateException("Private chat command was rejected before transport") {
    init {
        require(userMessage.isNotBlank() && userMessage.length <= 200 && userMessage.none(Char::isISOControl))
    }
}
