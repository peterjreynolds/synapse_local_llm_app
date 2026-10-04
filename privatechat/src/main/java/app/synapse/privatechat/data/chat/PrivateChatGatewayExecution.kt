package app.synapse.privatechat.data.chat

import app.synapse.privatechat.data.diagnostics.PrivateConnectionDiagnostics
import app.synapse.privatechat.data.diagnostics.PrivateDiagnosticOperation
import app.synapse.privatechat.data.supabase.SupabaseTransportException
import app.synapse.privatechat.domain.account.PrivateAccountId
import app.synapse.privatechat.domain.chat.PrivateChatMutationOutcome
import app.synapse.privatechat.domain.chat.PrivateChatObservation
import kotlinx.coroutines.CancellationException

internal class PrivateChatGatewayExecution(
    private val sessionResolver: PrivateChatSessionResolver,
    private val diagnostics: PrivateConnectionDiagnostics = PrivateConnectionDiagnostics(),
) {
    suspend fun <Receipt> mutate(
        accountId: PrivateAccountId,
        mutation: suspend (PrivateChatAuthenticatedSession) -> Receipt,
    ): PrivateChatMutationOutcome<Receipt> {
        val session =
            try {
                sessionResolver.resolve(accountId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                diagnostics.record(PrivateDiagnosticOperation.SESSION, failure = error)
                return PrivateChatMutationOutcome.TransportUnavailable
            } ?: run {
                diagnostics.record(PrivateDiagnosticOperation.SESSION)
                return PrivateChatMutationOutcome.TransportUnavailable
            }
        return try {
            PrivateChatMutationOutcome.Confirmed(mutation(session))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (rejection: SupabasePrivateChatRequestRejectedException) {
            diagnostics.record(PrivateDiagnosticOperation.MUTATE, failure = rejection)
            PrivateChatMutationOutcome.Rejected(rejection.userMessage)
        } catch (rejection: PrivateChatCommandRejectedException) {
            diagnostics.record(PrivateDiagnosticOperation.MUTATE, failure = rejection)
            PrivateChatMutationOutcome.Rejected(rejection.userMessage)
        } catch (transportFailure: SupabaseTransportException) {
            diagnostics.record(PrivateDiagnosticOperation.MUTATE, failure = transportFailure)
            PrivateChatMutationOutcome.TransportUnavailable
        } catch (invalidRemoteState: Exception) {
            diagnostics.record(PrivateDiagnosticOperation.MUTATE, failure = invalidRemoteState)
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
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                diagnostics.record(PrivateDiagnosticOperation.SESSION, failure = error)
                return PrivateChatObservation.TransportUnavailable
            } ?: run {
                diagnostics.record(PrivateDiagnosticOperation.SESSION)
                return PrivateChatObservation.TransportUnavailable
            }
        return try {
            PrivateChatObservation.Available(loadSnapshot(session))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            diagnostics.record(PrivateDiagnosticOperation.OBSERVE, failure = failure)
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
