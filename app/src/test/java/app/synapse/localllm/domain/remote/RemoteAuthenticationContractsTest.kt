package app.synapse.localllm.domain.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RemoteAuthenticationContractsTest {
    @Test
    fun usernameMappingMatchesBackendNormalization() {
        assertEquals("peter", normalizeRemoteUsername("  PeTeR  "))
        assertEquals(
            "trish@accounts.synapse.invalid",
            buildRemoteSyntheticEmail("Ｔｒｉｓｈ"),
        )
    }

    @Test
    fun usernameMappingRejectsAmbiguousOrUnsafeInput() {
        listOf("ab", "peter@example.com", "../peter", "peter smith").forEach { candidate ->
            assertThrows(IllegalArgumentException::class.java) {
                normalizeRemoteUsername(candidate)
            }
        }
    }

    @Test
    fun registrationValidationMatchesTheServerBoundary() {
        val validated = validateRemoteInviteRegistrationCommand(
            RemoteInviteRegistrationCommand(
                username = "  JoSh ",
                displayName = " Josh R. ",
                password = "a secure family password",
                invitationCode = " invite_abcdefghijklmnopqrstuvwxyz0123456789 ",
            ),
        )

        assertEquals("josh", validated.username)
        assertEquals("Josh R.", validated.displayName)
        assertEquals("invite_abcdefghijklmnopqrstuvwxyz0123456789", validated.invitationCode)
        assertThrows(IllegalArgumentException::class.java) {
            validateRemoteInviteRegistrationCommand(validated.copy(password = "too-short"))
        }
    }

    @Test
    fun accountClaimsAreNarrowedBeforeUse() {
        assertEquals(
            RemoteAccountClaims(
                role = RemoteAccountRole.OWNER,
                state = RemoteAccountState.ACTIVE,
                mustChangePassword = false,
            ),
            parseRemoteAccountClaims(
                mapOf(
                    "claimsVersion" to 1L,
                    "role" to "OWNER",
                    "accountState" to "ACTIVE",
                    "mustChangePassword" to false,
                ),
            ),
        )
        assertEquals(null, parseRemoteAccountClaims(mapOf("role" to "OWNER")))
        assertEquals(
            null,
            parseRemoteAccountClaims(
                mapOf(
                    "claimsVersion" to 1L,
                    "role" to "SUPERUSER",
                    "accountState" to "ACTIVE",
                    "mustChangePassword" to false,
                ),
            ),
        )
    }
}
