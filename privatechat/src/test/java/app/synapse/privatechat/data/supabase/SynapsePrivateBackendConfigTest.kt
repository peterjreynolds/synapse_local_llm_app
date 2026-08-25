package app.synapse.privatechat.data.supabase

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SynapsePrivateBackendConfigTest {
    @Test
    fun acceptsCanonicalSupabaseOriginAndPublishableKey() {
        val config =
            SynapsePrivateBackendConfig.requireValid(
                projectUrl = "https://projectref.supabase.co",
                publishableKey = "sb_publishable_0123456789abcdefghij",
            )

        assertEquals("https://projectref.supabase.co", config.projectOrigin)
    }

    @Test
    fun rejectsUntrustedOriginsAndSecretKeys() {
        listOf(
            "http://projectref.supabase.co",
            "https://projectref.supabase.co/path",
            "https://projectref.supabase.co@attacker.example",
            "https://attacker.example",
        ).forEach { projectUrl ->
            assertThrows(IllegalArgumentException::class.java) {
                SynapsePrivateBackendConfig.requireValid(
                    projectUrl = projectUrl,
                    publishableKey = "sb_publishable_0123456789abcdefghij",
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            SynapsePrivateBackendConfig.requireValid(
                projectUrl = "https://projectref.supabase.co",
                publishableKey = "sb_secret_must_never_ship_in_android",
            )
        }
    }

    @Test
    fun buildsOnlyLockedOriginPathsAndEncodedQueries() {
        val config =
            SynapsePrivateBackendConfig.requireValid(
                projectUrl = "https://projectref.supabase.co",
                publishableKey = "sb_publishable_0123456789abcdefghij",
            )
        val request =
            SupabaseHttpRequest(
                method = SupabaseHttpMethod.POST,
                pathSegments = listOf("rest", "v1", "rpc", "send_message"),
                queryParameters = mapOf("select" to "id, created_at"),
                jsonBody = buildJsonObject { put("ciphertext", "opaque") },
            )

        assertEquals(
            "https://projectref.supabase.co/rest/v1/rpc/send_message?select=id%2C%20created_at",
            buildRequestUri(config.projectUri, request).toASCIIString(),
        )
    }

    @Test
    fun rejectsPathAndHeaderInjection() {
        assertThrows(IllegalArgumentException::class.java) {
            SupabaseHttpRequest(
                method = SupabaseHttpMethod.GET,
                pathSegments = listOf("rest", "../auth"),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            SupabaseHttpRequest(
                method = SupabaseHttpMethod.GET,
                pathSegments = listOf("rest", "v1"),
                accessToken = "valid-looking-token-that-is-long\r\nInjected: true",
            )
        }
    }
}
