package app.synapse.privatechat.data.account

import app.synapse.privatechat.crypto.SignalDeviceAddress
import app.synapse.privatechat.crypto.SignalProtocolAdapter
import app.synapse.privatechat.crypto.SignalProtocolStateRepository
import java.util.UUID

internal class PrivateSignalDeviceBootstrapper(
    private val stateRepository: SignalProtocolStateRepository,
) {
    fun preparePublicBundle(reservation: PrivateDeviceRegistrationReservation) =
        synchronized(stateRepository) {
            val reservedAddress =
                SignalDeviceAddress.fromWire(
                    accountId = reservation.accountId.canonical,
                    transportDeviceId = reservation.transportDeviceId.toString(),
                    protocolDeviceId = reservation.signalDeviceId.raw,
                )
            val storedIdentity = stateRepository.loadLocalIdentity()
            if (storedIdentity != null && storedIdentity.address != reservedAddress) {
                throw PrivateDeviceIdentityConflictException(
                    existingAccountId = storedIdentity.address.accountId,
                    requestedAccountId = reservedAddress.accountId,
                )
            }
            val adapter = SignalProtocolAdapter(reservedAddress, stateRepository)
            if (storedIdentity == null) {
                adapter.initializeLocalDevice().publicPreKeyBundle
            } else {
                adapter.generatePublicPreKeyBundle()
            }
        }
}

internal class PrivateDeviceIdentityConflictException(
    val existingAccountId: UUID,
    val requestedAccountId: UUID,
) : IllegalStateException(
        "This installation is already bound to a different encrypted account identity",
    )
