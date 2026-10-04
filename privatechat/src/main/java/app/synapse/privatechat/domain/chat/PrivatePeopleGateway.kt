package app.synapse.privatechat.domain.chat

import app.synapse.privatechat.domain.account.PrivateAccountId
import java.time.Instant

data class PrivateDirectoryPerson(
    val accountId: PrivateAccountId,
    val displayName: String,
    val activeUntil: Instant,
) {
    init {
        require(displayName.isNotBlank() && displayName.length <= 64 && displayName.none(Char::isISOControl))
    }
}

data class PrivateDirectConversationReceipt(
    val accountId: PrivateAccountId,
    val targetAccountId: PrivateAccountId,
    val roomId: PrivateRoomId,
)

interface PrivatePeopleGateway {
    suspend fun loadPeople(accountId: PrivateAccountId): PrivateChatObservation<List<PrivateDirectoryPerson>>

    suspend fun publishActivity(accountId: PrivateAccountId): PrivateChatMutationOutcome<Instant>

    suspend fun openDirectConversation(
        accountId: PrivateAccountId,
        targetAccountId: PrivateAccountId,
    ): PrivateChatMutationOutcome<PrivateDirectConversationReceipt>
}

object UnavailablePrivatePeopleGateway : PrivatePeopleGateway {
    override suspend fun loadPeople(accountId: PrivateAccountId) = PrivateChatObservation.TransportUnavailable

    override suspend fun publishActivity(accountId: PrivateAccountId) = PrivateChatMutationOutcome.TransportUnavailable

    override suspend fun openDirectConversation(
        accountId: PrivateAccountId,
        targetAccountId: PrivateAccountId,
    ) = PrivateChatMutationOutcome.TransportUnavailable
}
