package app.synapse.privatechat.data.chat

import app.synapse.privatechat.crypto.SignalProtocolException
import app.synapse.privatechat.crypto.SignalProtocolFailureKind
import app.synapse.privatechat.crypto.local.DeviceLocalContentEnvelopeKeyErasedException
import app.synapse.privatechat.data.diagnostics.PrivateConnectionDiagnostics
import app.synapse.privatechat.data.diagnostics.PrivateDiagnosticOperation
import java.util.UUID

/** Old clients could erase the plaintext cache after consuming a Signal envelope.
 * Reject that unavailable item without treating all current chats as disconnected.
 * Identity changes, invalid ciphertext/context, and damaged vaults still fail closed.
 */
internal class PrivateUnavailableHistory(
    private val diagnostics: PrivateConnectionDiagnostics,
) {
    private val counts = mutableMapOf<UUID, Int>()

    fun countsByRoom(): Map<UUID, Int> = counts.toMap()

    fun <Payload : Any> decodeAvailable(
        roomId: UUID,
        decode: () -> Payload,
    ): Payload? =
        try {
            decode()
        } catch (failure: DeviceLocalContentEnvelopeKeyErasedException) {
            recordUnavailable(roomId, failure)
            null
        } catch (failure: SignalProtocolException) {
            if (failure.kind != SignalProtocolFailureKind.REPLAY_DETECTED) throw failure
            recordUnavailable(roomId, failure)
            null
        }

    private fun recordUnavailable(
        roomId: UUID,
        failure: Exception,
    ) {
        counts[roomId] = (counts[roomId] ?: 0) + 1
        diagnostics.record(PrivateDiagnosticOperation.DECRYPT, failure = failure)
    }
}
