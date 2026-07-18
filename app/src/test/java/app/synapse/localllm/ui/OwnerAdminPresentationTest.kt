package app.synapse.localllm.ui

import app.synapse.localllm.domain.remote.OwnerInvitationSummary
import app.synapse.localllm.domain.remote.RemoteAccountRole
import org.junit.Assert.assertEquals
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

    @Test
    fun standardInviteIsOneUseAndValidForSevenDays() {
        val command = defaultOwnerInvitationCommand()

        assertEquals(null, command.intendedLabel)
        assertEquals(24 * 7, command.lifetimeHours)
        assertEquals(1, command.maximumUses)
    }

    @Test
    fun invitationListShowsOnlyUsableActiveCodes() {
        val invitations = listOf(
            invitation("revoked", "REVOKED", remainingUses = 1, expiresAtMillis = 3_000),
            invitation("used", "ACTIVE", remainingUses = 0, expiresAtMillis = 3_000),
            invitation("expired", "ACTIVE", remainingUses = 1, expiresAtMillis = 900),
            invitation("later", "ACTIVE", remainingUses = 2, expiresAtMillis = 3_000),
            invitation("sooner", "ACTIVE", remainingUses = 1, expiresAtMillis = 2_000),
        )

        assertEquals(
            listOf("sooner", "later"),
            activeOwnerInvitations(invitations, currentTimeMillis = 1_000).map { invitation ->
                invitation.invitationId
            },
        )
    }

    @Test
    fun ownerAuditEventTypesArePresentedAsPlainLanguage() {
        assertEquals("Temporary password set", ownerAuditEventLabel("ACCOUNT_PASSWORD_RESET"))
        assertEquals("Custom owner action", ownerAuditEventLabel("CUSTOM_OWNER_ACTION"))
    }

    private fun invitation(
        id: String,
        state: String,
        remainingUses: Int,
        expiresAtMillis: Long,
    ): OwnerInvitationSummary = OwnerInvitationSummary(
        invitationId = id,
        intendedLabel = null,
        state = state,
        maximumUses = 2,
        remainingUses = remainingUses,
        expiresAtMillis = expiresAtMillis,
    )
}
