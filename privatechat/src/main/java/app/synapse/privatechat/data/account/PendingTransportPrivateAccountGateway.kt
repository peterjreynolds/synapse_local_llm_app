package app.synapse.privatechat.data.account

import app.synapse.privatechat.domain.account.PrivateAccountAccessCommand
import app.synapse.privatechat.domain.account.PrivateAccountAccessOutcome
import app.synapse.privatechat.domain.account.PrivateAccountGateway

internal object PendingTransportPrivateAccountGateway : PrivateAccountGateway {
    override suspend fun requestPrivateAccountAccess(command: PrivateAccountAccessCommand): PrivateAccountAccessOutcome =
        PrivateAccountAccessOutcome.TransportNotConfigured
}
