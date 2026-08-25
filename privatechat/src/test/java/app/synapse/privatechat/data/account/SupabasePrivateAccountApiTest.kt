package app.synapse.privatechat.data.account

import app.synapse.privatechat.crypto.SignalDeviceAddress
import app.synapse.privatechat.crypto.SignalDeviceId
import app.synapse.privatechat.crypto.SignalKyberPreKey
import app.synapse.privatechat.crypto.SignalOneTimePreKey
import app.synapse.privatechat.crypto.SignalPublicPreKeyBundle
import app.synapse.privatechat.crypto.SignalSignedPreKey
import app.synapse.privatechat.data.supabase.SupabaseHttpRequest
import app.synapse.privatechat.data.supabase.SupabaseHttpResponse
import app.synapse.privatechat.data.supabase.SupabaseHttpTransport
import app.synapse.privatechat.domain.account.PrivateAccountAccessCommand
import app.synapse.privatechat.domain.account.PrivateAccountId
import app.synapse.privatechat.domain.account.PrivateAccountPassword
import app.synapse.privatechat.domain.account.PrivateDisplayName
import app.synapse.privatechat.domain.account.PrivateInvitationCode
import app.synapse.privatechat.domain.account.PrivateUsername
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class SupabasePrivateAccountApiTest {
    @Test
    fun registrationAuthenticatesBeforeAnySignalKeyLeavesTheDevice() =
        runBlocking {
            val transport = RecordingTransport(authenticationResponse(accountContainer = "registration"))
            val api = SupabasePrivateAccountApi(transport, FIXED_CLOCK)
            val command =
                PrivateAccountAccessCommand.RegisterWithInvite(
                    displayName = PrivateDisplayName("Peter"),
                    username = PrivateUsername("peter_01"),
                    password = PrivateAccountPassword(PASSWORD),
                    invitationCode = PrivateInvitationCode(INVITE_CODE),
                )

            val outcome = api.authenticate(command, DEVICE_ID, REDEMPTION_ID)

            val confirmed = outcome as PrivateAccountBackendOutcome.Confirmed
            assertEquals(ACCOUNT_ID.toString(), confirmed.receipt.reservation.accountId.canonical)
            assertEquals(7, confirmed.receipt.reservation.signalDeviceId.raw)
            val request = transport.requests.single()
            assertEquals(listOf("functions", "v1", "redeem-invite"), request.pathSegments)
            val body = request.jsonBody!!.jsonObject
            assertEquals(
                setOf("invite_code", "redemption_id", "device_id", "username", "password", "display_name"),
                body.keys,
            )
            assertFalse(body.keys.any { field -> "key" in field || "signal" in field })
            assertEquals(DEVICE_ID.toString(), body.getValue("device_id").jsonPrimitive.content)
            assertFalse(request.toString().contains(PASSWORD))
            assertFalse(request.toString().contains(INVITE_CODE))
            assertFalse(outcome.toString().contains(ACCESS_TOKEN))
            assertFalse(outcome.toString().contains(REFRESH_TOKEN))
        }

    @Test
    fun signInRejectsAReservationForAnotherDevice() {
        runBlocking {
            val transport = RecordingTransport(authenticationResponse(deviceId = UUID.randomUUID()))
            val api = SupabasePrivateAccountApi(transport, FIXED_CLOCK)

            assertThrows(SupabaseAccountResponseException::class.java) {
                runBlocking {
                    api.authenticate(
                        PrivateAccountAccessCommand.SignIn(
                            username = PrivateUsername("peter_01"),
                            password = PrivateAccountPassword(PASSWORD),
                        ),
                        DEVICE_ID,
                        null,
                    )
                }
            }
        }
    }

    @Test
    fun deviceRegistrationCarriesOnlyPublicSignalMaterialAndRequiresMatchingReservation() =
        runBlocking {
            val transport = RecordingTransport(authenticationResponse())
            val api = SupabasePrivateAccountApi(transport, FIXED_CLOCK)
            val authenticationOutcome =
                api.authenticate(
                    PrivateAccountAccessCommand.SignIn(
                        username = PrivateUsername("peter_01"),
                        password = PrivateAccountPassword(PASSWORD),
                    ),
                    DEVICE_ID,
                    null,
                )
            val authentication = (authenticationOutcome as PrivateAccountBackendOutcome.Confirmed).receipt
            transport.responses.add(deviceBindingResponse())

            val outcome =
                api.registerDevice(
                    PrivateDeviceBindingCommand(
                        reservation = authentication.reservation,
                        tokens = authentication.tokens,
                        publicPreKeyBundle = publicBundle(),
                    ),
                )

            val confirmed = outcome as PrivateAccountBackendOutcome.Confirmed
            assertEquals("Peter", confirmed.receipt.displayName.canonical)
            val request = transport.requests.last()
            assertEquals(listOf("functions", "v1", "register-device"), request.pathSegments)
            assertEquals(ACCESS_TOKEN, request.accessToken)
            val device =
                request.jsonBody!!
                    .jsonObject
                    .getValue("device")
                    .jsonObject
            assertEquals(
                7,
                device
                    .getValue("signal_device_id")
                    .jsonPrimitive
                    .content
                    .toInt(),
            )
            assertEquals(
                66,
                device
                    .getValue("identity_key_hex")
                    .jsonPrimitive
                    .content
                    .length,
            )
            assertEquals(
                3_138,
                device
                    .getValue("kyber_pre_key")
                    .jsonObject
                    .getValue("public_key_hex")
                    .jsonPrimitive
                    .content
                    .length,
            )
            assertFalse(request.toString().contains(ACCESS_TOKEN))
        }

    @Test
    fun registerDeviceFailsBeforeTransportWhenSignalAddressDoesNotMatchReservation() =
        runBlocking {
            val transport = RecordingTransport(deviceBindingResponse())
            val api = SupabasePrivateAccountApi(transport, FIXED_CLOCK)
            val reservation =
                PrivateDeviceRegistrationReservation(
                    accountId = PrivateAccountId(ACCOUNT_ID.toString()),
                    transportDeviceId = DEVICE_ID,
                    signalDeviceId = SignalDeviceId.fromWire(8),
                    expiresAt = FIXED_NOW.plusSeconds(300),
                )

            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    api.registerDevice(
                        PrivateDeviceBindingCommand(
                            reservation = reservation,
                            tokens = sessionTokens(),
                            publicPreKeyBundle = publicBundle(),
                        ),
                    )
                }
            }
            assertTrue(transport.requests.isEmpty())
        }

    private fun publicBundle(): SignalPublicPreKeyBundle =
        SignalPublicPreKeyBundle.fromWire(
            protocolVersion = 1,
            address = SignalDeviceAddress.fromWire(ACCOUNT_ID.toString(), DEVICE_ID.toString(), 7),
            registrationId = 42,
            identityKeyBytes = curveKey(1),
            oneTimePreKey = SignalOneTimePreKey.fromWire(11, curveKey(2)),
            signedPreKey = SignalSignedPreKey.fromWire(12, curveKey(3), ByteArray(64) { 4 }),
            kyberPreKey =
                SignalKyberPreKey.fromWire(
                    13,
                    ByteArray(1_569) { index -> if (index == 0) 8.toByte() else 5.toByte() },
                    ByteArray(64) { 6 },
                ),
        )

    private fun curveKey(fill: Int): ByteArray = ByteArray(33) { index -> if (index == 0) 5.toByte() else fill.toByte() }

    private fun authenticationResponse(
        deviceId: UUID = DEVICE_ID,
        accountContainer: String = "account",
    ): SupabaseHttpResponse =
        SupabaseHttpResponse(
            statusCode = 200,
            jsonBody =
                buildJsonObject {
                    put(accountContainer, buildJsonObject { put("user_id", ACCOUNT_ID.toString()) })
                    put(
                        "device_registration",
                        buildJsonObject {
                            put("user_id", ACCOUNT_ID.toString())
                            put("device_id", deviceId.toString())
                            put("signal_device_id", 7)
                            put("expires_at", FIXED_NOW.plusSeconds(300).toString())
                        },
                    )
                    put(
                        "session",
                        buildJsonObject {
                            put("access_token", ACCESS_TOKEN)
                            put("refresh_token", REFRESH_TOKEN)
                            put("expires_at", FIXED_NOW.plusSeconds(3_600).epochSecond)
                            put("expires_in", 3_600)
                            put("token_type", "bearer")
                        },
                    )
                },
        )

    private fun deviceBindingResponse(): SupabaseHttpResponse =
        SupabaseHttpResponse(
            statusCode = 200,
            jsonBody =
                buildJsonObject {
                    put(
                        "device_registration",
                        buildJsonObject {
                            put("user_id", ACCOUNT_ID.toString())
                            put("device_id", DEVICE_ID.toString())
                            put("signal_device_id", 7)
                            put("display_name", "Peter")
                            put("bound_at", FIXED_NOW.toString())
                        },
                    )
                },
        )

    private fun sessionTokens(): PrivateBackendSessionTokens =
        PrivateBackendSessionTokens(
            expiresAt = FIXED_NOW.plusSeconds(3_600),
            accessToken = ACCESS_TOKEN,
            refreshToken = REFRESH_TOKEN,
        )

    private class RecordingTransport(
        initialResponse: SupabaseHttpResponse,
    ) : SupabaseHttpTransport {
        val requests = mutableListOf<SupabaseHttpRequest>()
        val responses = ArrayDeque<SupabaseHttpResponse>().apply { add(initialResponse) }

        override suspend fun execute(request: SupabaseHttpRequest): SupabaseHttpResponse {
            requests += request
            return responses.removeFirst()
        }
    }

    private companion object {
        val FIXED_NOW: Instant = Instant.parse("2026-08-22T12:00:00Z")
        val FIXED_CLOCK: Clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC)
        val ACCOUNT_ID: UUID = UUID.fromString("10000000-0000-4000-8000-000000000001")
        val DEVICE_ID: UUID = UUID.fromString("20000000-0000-4000-8000-000000000002")
        val REDEMPTION_ID: UUID = UUID.fromString("30000000-0000-4000-8000-000000000003")
        const val PASSWORD = "correct-horse-battery"
        const val INVITE_CODE = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        const val ACCESS_TOKEN = "access.token.with-safe-characters-123456789"
        const val REFRESH_TOKEN = "refresh-token-with-safe-characters-123456789"
    }
}
