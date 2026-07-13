package app.synapse.localllm.data.remote

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
}
