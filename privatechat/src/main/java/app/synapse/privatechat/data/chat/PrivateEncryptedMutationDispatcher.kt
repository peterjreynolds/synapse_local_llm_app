package app.synapse.privatechat.data.chat

internal class PrivateEncryptedMutationDispatcher(
    private val backend: PrivateChatBackend,
) {
    suspend fun execute(
        session: PrivateChatAuthenticatedSession,
        request: PrivatePendingEncryptedMutation,
    ): PrivateEncryptedMutationBackendReceipt =
        when (request) {
            is PrivatePendingEncryptedMutation.SendMessage ->
                PrivateEncryptedMutationBackendReceipt.MessageSent(
                    backend.sendMessage(
                        session = session,
                        roomId = request.roomId,
                        clientMutationId = request.clientMutationId,
                        replyToMessageId = request.replyToMessageId,
                        envelopes = request.envelopes,
                    ),
                )

            is PrivatePendingEncryptedMutation.EditMessage ->
                PrivateEncryptedMutationBackendReceipt.MessageEdited(
                    backend.editMessage(
                        session = session,
                        messageId = request.messageId,
                        clientMutationId = request.clientMutationId,
                        expectedServerRevision = request.expectedServerRevision,
                        envelopes = request.envelopes,
                    ),
                )

            is PrivatePendingEncryptedMutation.AddReaction ->
                PrivateEncryptedMutationBackendReceipt.ReactionAdded(
                    backend.addReaction(
                        session = session,
                        messageId = request.messageId,
                        clientMutationId = request.clientMutationId,
                        envelopes = request.envelopes,
                    ),
                )

            is PrivatePendingEncryptedMutation.CreateRoom ->
                PrivateEncryptedMutationBackendReceipt.RoomCreated(
                    backend.createRoom(
                        session = session,
                        roomId = request.roomId,
                        kind = request.kind,
                        retention = request.retention,
                        clientMutationId = request.clientMutationId,
                        envelopes = request.envelopes,
                    ),
                )
        }
}
