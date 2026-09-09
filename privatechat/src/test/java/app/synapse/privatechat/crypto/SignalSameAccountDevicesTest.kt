package app.synapse.privatechat.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.time.Instant
import java.util.UUID

class SignalSameAccountDevicesTest {
    @Test
    fun offlineSiblingDeviceCanReceiveMessagesWithoutOneTimePreKeys() {
        val accountId = UUID.randomUUID()
        val phoneAddress = SignalDeviceAddress(accountId, UUID.randomUUID(), SignalDeviceId.fromWire(1))
        val tabletAddress = SignalDeviceAddress(accountId, UUID.randomUUID(), SignalDeviceId.fromWire(2))
        val phone = SignalProtocolAdapter(phoneAddress, InMemorySignalProtocolStateRepository())
        val tablet = SignalProtocolAdapter(tabletAddress, InMemorySignalProtocolStateRepository())
        phone.initializeLocalDevice(Instant.now())
        val publishedBundle = tablet.initializeLocalDevice(Instant.now()).publicPreKeyBundle
        phone.establishPairwiseSession(
            SignalPublicPreKeyBundle.fromWire(
                protocolVersion = publishedBundle.protocolVersion,
                address = publishedBundle.address,
                registrationId = publishedBundle.registrationId.raw,
                identityKeyBytes = publishedBundle.identityKeyBytes,
                oneTimePreKey = null,
                signedPreKey = publishedBundle.signedPreKey,
                kyberPreKey = publishedBundle.kyberPreKey,
            ),
        )
        val plaintext = "send while the other device is offline".encodeToByteArray()
        val waitingEnvelopes = (1..3).map { phone.encryptForDevice(tabletAddress, plaintext) }
        waitingEnvelopes.forEach { envelope ->
            assertArrayEquals(plaintext, tablet.decryptFromDevice(envelope))
        }
        val reply = tablet.encryptForDevice(phoneAddress, plaintext)
        assertArrayEquals(plaintext, phone.decryptFromDevice(reply))
    }
}
