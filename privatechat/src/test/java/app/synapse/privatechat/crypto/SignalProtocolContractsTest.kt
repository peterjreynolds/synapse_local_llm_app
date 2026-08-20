package app.synapse.privatechat.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.UUID

class SignalProtocolContractsTest {
    @Test
    fun deviceAddressRejectsNonCanonicalOrOutOfRangeWireValues() {
        val accountId = "aaaaaaaa-0000-4000-8000-000000000001"
        val deviceId = "bbbbbbbb-0000-4000-8000-000000000002"

        assertThrows(IllegalArgumentException::class.java) {
            SignalDeviceAddress.fromWire(accountId.uppercase(), deviceId, 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SignalDeviceAddress.fromWire(accountId, deviceId, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SignalDeviceAddress.fromWire(accountId, deviceId, 128)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SignalDeviceAddress(
                accountId = UUID(0L, 0L),
                transportDeviceId = UUID.fromString(deviceId),
                protocolDeviceId = SignalDeviceId.fromWire(1),
            )
        }
    }

    @Test
    fun publicPreKeyBundleValidatesExactKeyTypesSizesAndVersion() {
        val identityKey = curvePublicKey(fill = 1)
        val oneTimePreKeyBytes = curvePublicKey(fill = 2)
        val signedPreKeyBytes = curvePublicKey(fill = 3)
        val kyberPreKeyBytes = kyberPublicKey(fill = 4)
        val signature = ByteArray(SignalProtocolWireLimits.SIGNAL_SIGNATURE_BYTES) { 5 }

        val bundle =
            SignalPublicPreKeyBundle.fromWire(
                protocolVersion = SignalPublicPreKeyBundle.CURRENT_PROTOCOL_VERSION,
                address = ALICE_ADDRESS,
                registrationId = 42,
                identityKeyBytes = identityKey,
                oneTimePreKey = SignalOneTimePreKey.fromWire(7, oneTimePreKeyBytes),
                signedPreKey = SignalSignedPreKey.fromWire(8, signedPreKeyBytes, signature),
                kyberPreKey = SignalKyberPreKey.fromWire(9, kyberPreKeyBytes, signature),
            )

        identityKey.fill(0)
        oneTimePreKeyBytes.fill(0)
        signedPreKeyBytes.fill(0)
        kyberPreKeyBytes.fill(0)
        signature.fill(0)

        assertArrayEquals(curvePublicKey(fill = 1), bundle.identityKeyBytes)
        assertArrayEquals(curvePublicKey(fill = 2), bundle.oneTimePreKey?.publicKeyBytes)
        assertArrayEquals(curvePublicKey(fill = 3), bundle.signedPreKey.publicKeyBytes)
        assertArrayEquals(kyberPublicKey(fill = 4), bundle.kyberPreKey.publicKeyBytes)

        assertThrows(IllegalArgumentException::class.java) {
            SignalPublicPreKeyBundle.fromWire(
                protocolVersion = 2,
                address = ALICE_ADDRESS,
                registrationId = 42,
                identityKeyBytes = curvePublicKey(fill = 1),
                oneTimePreKey = null,
                signedPreKey =
                    SignalSignedPreKey.fromWire(
                        8,
                        curvePublicKey(fill = 3),
                        ByteArray(SignalProtocolWireLimits.SIGNAL_SIGNATURE_BYTES),
                    ),
                kyberPreKey =
                    SignalKyberPreKey.fromWire(
                        9,
                        kyberPublicKey(fill = 4),
                        ByteArray(SignalProtocolWireLimits.SIGNAL_SIGNATURE_BYTES),
                    ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            SignalOneTimePreKey.fromWire(7, ByteArray(32))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SignalKyberPreKey.fromWire(
                9,
                kyberPublicKey(fill = 4).also { it[0] = 0x0A },
                ByteArray(SignalProtocolWireLimits.SIGNAL_SIGNATURE_BYTES),
            )
        }
    }

    @Test
    fun envelopeRejectsUnknownTypesVersionsAndMutableCiphertext() {
        val ciphertext = byteArrayOf(1, 2, 3)
        val envelope =
            SignalEnvelope.fromWire(
                protocolVersion = SignalEnvelope.CURRENT_PROTOCOL_VERSION,
                sender = ALICE_ADDRESS,
                recipient = BOB_ADDRESS,
                ciphertextTypeCode = SignalCiphertextType.PREKEY.wireCode,
                serializedCiphertext = ciphertext,
            )
        ciphertext.fill(0)

        assertArrayEquals(byteArrayOf(1, 2, 3), envelope.serializedCiphertext)
        assertThrows(IllegalArgumentException::class.java) {
            SignalEnvelope.fromWire(2, ALICE_ADDRESS, BOB_ADDRESS, 3, byteArrayOf(1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SignalEnvelope.fromWire(1, ALICE_ADDRESS, BOB_ADDRESS, 7, byteArrayOf(1))
        }
        assertEquals(SignalCiphertextType.PREKEY, SignalCiphertextType.fromWire("PREKEY"))
        assertThrows(IllegalArgumentException::class.java) {
            SignalCiphertextType.fromWire("prekey")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SignalEnvelope.fromWire(1, ALICE_ADDRESS, ALICE_ADDRESS, 3, byteArrayOf(1))
        }
    }

    @Test
    fun numericSafetyNumberUsesTwelveFiveDigitGroups() {
        val safetyNumber = NumericSafetyNumber.fromLibSignal("12345".repeat(12))

        assertEquals(60, safetyNumber.digits.length)
        assertEquals(12, safetyNumber.grouped.split(' ').size)
        assertThrows(IllegalArgumentException::class.java) {
            NumericSafetyNumber.fromLibSignal("1".repeat(59))
        }
    }

    private fun curvePublicKey(fill: Byte): ByteArray =
        ByteArray(SignalProtocolWireLimits.CURVE_PUBLIC_KEY_BYTES) { fill }.also {
            it[0] = SignalProtocolWireLimits.CURVE_PUBLIC_KEY_TYPE.toByte()
        }

    private fun kyberPublicKey(fill: Byte): ByteArray =
        ByteArray(SignalProtocolWireLimits.KYBER_1024_PUBLIC_KEY_BYTES) { fill }.also {
            it[0] = SignalProtocolWireLimits.KYBER_1024_TYPE.toByte()
        }

    private companion object {
        val ALICE_ADDRESS: SignalDeviceAddress =
            SignalDeviceAddress.fromWire(
                accountId = "00000000-0000-4000-8000-000000000001",
                transportDeviceId = "00000000-0000-4000-8000-000000000002",
                protocolDeviceId = 1,
            )
        val BOB_ADDRESS: SignalDeviceAddress =
            SignalDeviceAddress.fromWire(
                accountId = "00000000-0000-4000-8000-000000000003",
                transportDeviceId = "00000000-0000-4000-8000-000000000004",
                protocolDeviceId = 1,
            )
    }
}
