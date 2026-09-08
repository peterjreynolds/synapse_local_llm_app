package app.synapse.privatechat.data.account

import app.synapse.privatechat.data.supabase.SupabaseHttpRequest
import app.synapse.privatechat.data.supabase.SupabaseHttpResponse
import app.synapse.privatechat.data.supabase.SupabaseHttpTransport
import app.synapse.privatechat.domain.account.PrivateAccountAccessCommand
import app.synapse.privatechat.domain.account.PrivateAccountPassword
import app.synapse.privatechat.domain.account.PrivateUsername
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class AndroidSupabasePrivateAccountApiTest {
    @Test
    fun parsesProductionAuthenticationReceiptWithAnOldDeviceClock() =
        runBlocking {
            val transport = FixedResponseTransport(authenticationResponse())
            val api = SupabasePrivateAccountApi(transport, OLD_DEVICE_CLOCK)

            val outcome =
                api.authenticate(
                    command =
                        PrivateAccountAccessCommand.SignIn(
                            username = PrivateUsername("android_7_probe"),
                            password = PrivateAccountPassword("password-probe"),
                        ),
                    transportDeviceId = DEVICE_ID,
                    registrationRedemptionId = null,
                )

            assertTrue(outcome is PrivateAccountBackendOutcome.Confirmed)
            val receipt = (outcome as PrivateAccountBackendOutcome.Confirmed).receipt
            assertEquals(Instant.parse("2026-08-30T06:48:02.261968Z"), receipt.reservation.expiresAt)
            assertEquals(OLD_DEVICE_NOW.plusSeconds(3_600), receipt.tokens.expiresAt)
            assertEquals(listOf("functions", "v1", "sign-in"), transport.request?.pathSegments)
        }

    private fun authenticationResponse(): SupabaseHttpResponse =
        SupabaseHttpResponse(
            statusCode = 200,
            jsonBody =
                buildJsonObject {
                    put("account", buildJsonObject { put("user_id", ACCOUNT_ID.toString()) })
                    put(
                        "device_registration",
                        buildJsonObject {
                            put("user_id", ACCOUNT_ID.toString())
                            put("device_id", DEVICE_ID.toString())
                            put("signal_device_id", 7)
                            put("expires_at", POSTGRES_RESERVATION_EXPIRY)
                        },
                    )
                    put(
                        "session",
                        buildJsonObject {
                            put("access_token", "header.payload.signature")
                            put("refresh_token", "opaque-refresh-token")
                            put("expires_at", 1_788_070_582L)
                            put("expires_in", 3_600)
                            put("token_type", "bearer")
                        },
                    )
                },
        )

    private class FixedResponseTransport(
        private val response: SupabaseHttpResponse,
    ) : SupabaseHttpTransport {
        var request: SupabaseHttpRequest? = null

        override suspend fun execute(request: SupabaseHttpRequest): SupabaseHttpResponse {
            this.request = request
            return response
        }
    }

    private companion object {
        val ACCOUNT_ID: UUID = UUID.fromString("10000000-0000-4000-8000-000000000001")
        val DEVICE_ID: UUID = UUID.fromString("20000000-0000-4000-8000-000000000002")
        val OLD_DEVICE_NOW: Instant = Instant.parse("2017-10-25T21:06:42Z")
        val OLD_DEVICE_CLOCK: Clock = Clock.fixed(OLD_DEVICE_NOW, ZoneOffset.UTC)
        const val POSTGRES_RESERVATION_EXPIRY = "2026-08-30T06:48:02.261968+00:00"
    }
}
