package app.synapse.localllm.ui

import app.synapse.localllm.domain.remote.RemoteAccountRole
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnerAdminPresentationTest {
    @Test
    fun adminNavigationExistsOnlyForServerConfirmedOwnerRole() {
        assertTrue(RemoteAppSection.ADMIN in availableRemoteAppSections(RemoteAccountRole.OWNER))
        assertFalse(RemoteAppSection.ADMIN in availableRemoteAppSections(RemoteAccountRole.ADMIN))
        assertFalse(RemoteAppSection.ADMIN in availableRemoteAppSections(RemoteAccountRole.USER))
        assertFalse(RemoteAppSection.ADMIN in availableRemoteAppSections(null))
    }

    @Test
    fun generatedTemporaryPasswordsAreLongAndNonRepeating() {
        val first = generateOwnerTemporaryPassword()
        val second = generateOwnerTemporaryPassword()

        assertTrue(first.length in 12..128)
        assertTrue(first.matches(Regex("^[A-Za-z0-9_-]+$")))
        assertNotEquals(first, second)
    }
}
