package app.synapse.privatechat.crypto

import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.UUID

class SignalProtocolAdapterOwnerTest {
    @Test
    fun freshInstallPinsOneAdapterBeforeIdentityInitialization() {
        val owner = SignalProtocolAdapterOwner(InMemorySignalProtocolStateRepository())

        val initialAdapter = owner.adapterFor(LOCAL_ADDRESS)

        assertSame(initialAdapter, owner.adapterFor(LOCAL_ADDRESS))
        assertThrows(SignalProtocolStateCorruptedException::class.java) {
            owner.adapterFor(OTHER_ADDRESS)
        }
    }

    private companion object {
        val LOCAL_ADDRESS =
            SignalDeviceAddress(
                accountId = UUID.fromString("10000000-0000-4000-8000-000000000001"),
                transportDeviceId = UUID.fromString("20000000-0000-4000-8000-000000000002"),
                protocolDeviceId = SignalDeviceId.fromWire(7),
            )
        val OTHER_ADDRESS =
            SignalDeviceAddress(
                accountId = UUID.fromString("30000000-0000-4000-8000-000000000003"),
                transportDeviceId = UUID.fromString("40000000-0000-4000-8000-000000000004"),
                protocolDeviceId = SignalDeviceId.fromWire(8),
            )
    }
}
