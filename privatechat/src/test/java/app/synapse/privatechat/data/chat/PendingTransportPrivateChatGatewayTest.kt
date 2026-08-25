package app.synapse.privatechat.data.chat

import app.synapse.privatechat.domain.account.PrivateAccountId
import app.synapse.privatechat.domain.chat.CreatePrivateRoomCommand
import app.synapse.privatechat.domain.chat.PrivateChatMutationOutcome
import app.synapse.privatechat.domain.chat.PrivateChatObservation
import app.synapse.privatechat.domain.chat.PrivateClientMutationId
import app.synapse.privatechat.domain.chat.PrivateMessageRetention
import app.synapse.privatechat.domain.chat.PrivateRoomKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertSame
import org.junit.Test

class PendingTransportPrivateChatGatewayTest {
    @Test
    fun `social observations and group mutations fail closed while transport is pending`() =
        runBlocking {
            val accountId = PrivateAccountId("current")
            val observation = PendingTransportPrivateChatGateway.observeSocial(accountId).first()
            val outcome =
                PendingTransportPrivateChatGateway.createRoom(
                    CreatePrivateRoomCommand(
                        accountId = accountId,
                        mutationId = PrivateClientMutationId("mutation"),
                        kind = PrivateRoomKind.GROUP,
                        title = "Private circle",
                        retention = PrivateMessageRetention.ONE_DAY,
                    ),
                )

            assertSame(PrivateChatObservation.TransportUnavailable, observation)
            assertSame(PrivateChatMutationOutcome.TransportUnavailable, outcome)
        }
}
