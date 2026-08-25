package app.synapse.privatechat.data.account

import app.synapse.privatechat.crypto.SignalDeviceAddress
import app.synapse.privatechat.crypto.SignalProtocolAdapterOwner
import java.util.UUID

internal class PrivateSignalDeviceBootstrapper(
    private val adapterOwner: SignalProtocolAdapterOwner,
) {
    fun preparePublicBundle(reservation: PrivateDeviceRegistrationReservation) =
        synchronized(adapterOwner) {
            val reservedAddress =
                SignalDeviceAddress.fromWire(
                    accountId = reservation.accountId.canonical,
                    transportDeviceId = reservation.transportDeviceId.toString(),
                    protocolDeviceId = reservation.signalDeviceId.raw,
                )
            val storedAddress = adapterOwner.storedLocalAddress()
            if (storedAddress != null && storedAddress != reservedAddress) {
                throw PrivateDeviceIdentityConflictException(
                    existingAccountId = storedAddress.accountId,
                    requestedAccountId = reservedAddress.accountId,
                )
            }
            val adapter = adapterOwner.adapterFor(reservedAddress)
            if (storedAddress == null) {
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
