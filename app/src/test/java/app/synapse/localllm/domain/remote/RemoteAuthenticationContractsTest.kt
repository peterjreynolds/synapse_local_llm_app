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
}
