package app.synapse.localllm.data.remote

import app.synapse.localllm.domain.remote.RemoteChatException
import app.synapse.localllm.domain.remote.RemoteDeviceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class FirebaseRemoteDeviceRegistrationGatewayTest {
    @Test
    fun deviceDocumentIdIsDeterministicAndDoesNotExposeInstallationId() {
        val installationId = "firebase-installation-123456789"

        val firstDeviceId = buildFirebaseDeviceDocumentId(installationId)
        val secondDeviceId = buildFirebaseDeviceDocumentId(installationId)

        assertEquals(firstDeviceId, secondDeviceId)
        assertEquals(64, firstDeviceId.raw.length)
        assertFalse(firstDeviceId.raw.contains(installationId))
    }

    @Test
    fun deviceDocumentIdRejectsInvalidInstallationId() {
        assertThrows(IllegalArgumentException::class.java) {
            buildFirebaseDeviceDocumentId("too-short")
        }
    }

    @Test
    fun registeredDeviceParserMarksCurrentPhoneWithoutReturningInstallationId() {
        val currentDeviceId = RemoteDeviceId("a".repeat(64))
        val privateInstallationId = "private-firebase-installation-id"

        val parsed = parseRemoteRegisteredDevices(
            response = mapOf(
                "devices" to listOf(
                    mapOf(
                        "active" to true,
                        "deviceId" to currentDeviceId.raw,
                        "platform" to "ANDROID",
                        "updatedAtMillis" to 1234L,
                    ),
                ),
            ),
            currentDeviceId = currentDeviceId,
        )

        assertEquals(1, parsed.size)
        assertEquals(true, parsed.single().isCurrentDevice)
        assertFalse(parsed.single().toString().contains(privateInstallationId))
        assertThrows(RemoteChatException::class.java) {
            parseRemoteRegisteredDevices(
                response = mapOf(
                    "devices" to listOf(
                        mapOf(
                            "active" to true,
                            "deviceId" to currentDeviceId.raw,
                            "installationId" to privateInstallationId,
                            "platform" to "ANDROID",
                            "updatedAtMillis" to 1234L,
                        ),
                    ),
                ),
                currentDeviceId = currentDeviceId,
            )
        }
    }

    @Test
    fun registeredDeviceParserFailsClosedForInvalidIdentifiers() {
        assertThrows(RemoteChatException::class.java) {
            parseRemoteRegisteredDevices(
                response = mapOf(
                    "devices" to listOf(
                        mapOf(
                            "active" to true,
                            "deviceId" to "private-installation-id",
                            "platform" to "ANDROID",
                            "updatedAtMillis" to null,
                        ),
                    ),
                ),
                currentDeviceId = RemoteDeviceId("a".repeat(64)),
            )
        }
    }
}
