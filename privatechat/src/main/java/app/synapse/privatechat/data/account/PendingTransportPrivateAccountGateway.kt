package app.synapse.privatechat.data.account

import app.synapse.privatechat.domain.account.PrivateAccountAccessCommand
import app.synapse.privatechat.domain.account.PrivateAccountAccessOutcome
import app.synapse.privatechat.domain.account.PrivateAccountGateway
import app.synapse.privatechat.domain.account.PrivateAccountSessionOutcome
import app.synapse.privatechat.domain.account.PrivateAccountSignOutOutcome

internal object PendingTransportPrivateAccountGateway : PrivateAccountGateway {
    override suspend fun requestPrivateAccountAccess(command: PrivateAccountAccessCommand): PrivateAccountAccessOutcome =
        PrivateAccountAccessOutcome.TransportUnavailable

    override suspend fun restorePrivateAccountSession(): PrivateAccountSessionOutcome = PrivateAccountSessionOutcome.TransportUnavailable

    override suspend fun refreshPrivateAccountSession(): PrivateAccountSessionOutcome = PrivateAccountSessionOutcome.TransportUnavailable

    override suspend fun signOutPrivateAccount(): PrivateAccountSignOutOutcome = PrivateAccountSignOutOutcome.TransportUnavailable
}

internal object LocalStateUnavailablePrivateAccountGateway : PrivateAccountGateway {
    override suspend fun requestPrivateAccountAccess(command: PrivateAccountAccessCommand): PrivateAccountAccessOutcome =
        PrivateAccountAccessOutcome.LocalStateUnavailable

    override suspend fun restorePrivateAccountSession(): PrivateAccountSessionOutcome = PrivateAccountSessionOutcome.LocalStateUnavailable

    override suspend fun refreshPrivateAccountSession(): PrivateAccountSessionOutcome = PrivateAccountSessionOutcome.LocalStateUnavailable

    override suspend fun signOutPrivateAccount(): PrivateAccountSignOutOutcome = PrivateAccountSignOutOutcome.LocalStateUnavailable
}
