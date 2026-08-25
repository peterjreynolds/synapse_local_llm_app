package app.synapse.privatechat.data.account

import app.synapse.privatechat.crypto.SignalDeviceId
import app.synapse.privatechat.crypto.SignalPublicPreKeyBundle
import app.synapse.privatechat.data.supabase.SupabaseHttpMethod
import app.synapse.privatechat.data.supabase.SupabaseHttpRequest
import app.synapse.privatechat.data.supabase.SupabaseHttpResponse
import app.synapse.privatechat.data.supabase.SupabaseHttpTransport
import app.synapse.privatechat.domain.account.PrivateAccountAccessCommand
import app.synapse.privatechat.domain.account.PrivateAccountId
import app.synapse.privatechat.domain.account.PrivateDisplayName
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.time.Clock
import java.time.DateTimeException
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.UUID

internal class SupabasePrivateAccountApi(
    private val transport: SupabaseHttpTransport,
    private val clock: Clock = Clock.systemUTC(),
) : PrivateAccountBackend {
    override suspend fun authenticate(
        command: PrivateAccountAccessCommand,
        transportDeviceId: UUID,
        registrationRedemptionId: UUID?,
    ): PrivateAccountBackendOutcome<UnboundPrivateAccountSession> {
        val requestBody =
            when (command) {
                is PrivateAccountAccessCommand.SignIn -> {
                    require(registrationRedemptionId == null) {
                        "Sign-in must not carry a registration redemption ID"
                    }
                    buildJsonObject {
                        put("device_id", transportDeviceId.toString())
                        put("username", command.username.canonical)
                        put("password", command.password.exposeForAuthentication())
                    }
                }

                is PrivateAccountAccessCommand.RegisterWithInvite -> {
                    val redemptionId =
                        requireNotNull(registrationRedemptionId) {
                            "Invite registration requires a stable redemption ID"
                        }
                    buildJsonObject {
                        put("invite_code", command.invitationCode.canonical)
                        put("redemption_id", redemptionId.toString())
                        put("device_id", transportDeviceId.toString())
                        put("username", command.username.canonical)
                        put("password", command.password.exposeForAuthentication())
                        put("display_name", command.displayName.canonical)
                    }
                }
            }
        val functionName =
            when (command) {
                is PrivateAccountAccessCommand.SignIn -> "sign-in"
                is PrivateAccountAccessCommand.RegisterWithInvite -> "redeem-invite"
            }
        val response = invokeFunction(functionName, requestBody, accessToken = null)
        return if (response.statusCode in 200..299) {
            PrivateAccountBackendOutcome.Confirmed(
                parseAuthenticationReceipt(response, transportDeviceId),
            )
        } else {
            parseRejection(response)
        }
    }

    override suspend fun registerDevice(command: PrivateDeviceBindingCommand): PrivateAccountBackendOutcome<PrivateDeviceBindingReceipt> {
        requireBundleMatchesReservation(command)
        val response =
            invokeFunction(
                functionName = "register-device",
                body = buildDeviceRegistrationBody(command.publicPreKeyBundle),
                accessToken = command.tokens.exposeAccessTokenForRequest(),
            )
        return if (response.statusCode in 200..299) {
            PrivateAccountBackendOutcome.Confirmed(parseDeviceBindingReceipt(response))
        } else {
            parseRejection(response)
        }
    }

    override suspend fun refreshSession(
        command: PrivateSessionRefreshCommand,
    ): PrivateAccountBackendOutcome<RefreshedPrivateAccountSession> {
        val response =
            transport.execute(
                SupabaseHttpRequest(
                    method = SupabaseHttpMethod.POST,
                    pathSegments = listOf("auth", "v1", "token"),
                    queryParameters = mapOf("grant_type" to "refresh_token"),
                    jsonBody =
                        buildJsonObject {
                            put("refresh_token", command.exposeRefreshTokenForRequest())
                        },
                ),
            )
        return when {
            response.statusCode == 200 ->
                PrivateAccountBackendOutcome.Confirmed(
                    parseRefreshedSession(response),
                )

            response.statusCode in 200..299 ->
                malformedResponse("Supabase session refresh returned an unexpected success status")

            else -> parseRejection(response)
        }
    }

    override suspend fun signOut(command: PrivateSessionSignOutCommand): PrivateAccountBackendOutcome<PrivateBackendSignOutReceipt> {
        val response =
            transport.execute(
                SupabaseHttpRequest(
                    method = SupabaseHttpMethod.POST,
                    pathSegments = listOf("auth", "v1", "logout"),
                    queryParameters = mapOf("scope" to "local"),
                    accessToken = command.exposeAccessTokenForRequest(),
                ),
            )
        return when {
            response.statusCode == 204 ->
                PrivateAccountBackendOutcome.Confirmed(PrivateBackendSignOutReceipt)

            response.statusCode in 200..299 ->
                malformedResponse("Supabase sign-out returned an unexpected success status")

            else -> parseRejection(response)
        }
    }

    private suspend fun invokeFunction(
        functionName: String,
        body: JsonObject,
        accessToken: String?,
    ): SupabaseHttpResponse =
        transport.execute(
            SupabaseHttpRequest(
                method = SupabaseHttpMethod.POST,
                pathSegments = listOf("functions", "v1", functionName),
                accessToken = accessToken,
                jsonBody = body,
            ),
        )

    private fun parseAuthenticationReceipt(
        response: SupabaseHttpResponse,
        expectedDeviceId: UUID,
    ): UnboundPrivateAccountSession {
        val responseBody = response.requireObject("account authentication")
        val accountContainer =
            when {
                "account" in responseBody -> responseBody.requireObjectField("account")
                "registration" in responseBody -> responseBody.requireObjectField("registration")
                else -> malformedResponse("Account authentication receipt is missing its account")
            }
        val accountId = accountContainer.requireAccountId("user_id")
        val reservationObject = responseBody.requireObjectField("device_registration")
        val reservationAccountId = reservationObject.requireAccountId("user_id")
        val reservedDeviceId = reservationObject.requireUuid("device_id")
        if (reservationAccountId != accountId || reservedDeviceId != expectedDeviceId) {
            malformedResponse("Device reservation does not match the authenticated account")
        }
        val reservation =
            PrivateDeviceRegistrationReservation(
                accountId = accountId,
                transportDeviceId = reservedDeviceId,
                signalDeviceId = SignalDeviceId.fromWire(reservationObject.requireInt("signal_device_id")),
                expiresAt = reservationObject.requireInstant("expires_at"),
            )
        if (!reservation.expiresAt.isAfter(clock.instant())) {
            malformedResponse("Device reservation is already expired")
        }
        return UnboundPrivateAccountSession(
            reservation = reservation,
            tokens = responseBody.requireObjectField("session").toSessionTokens(clock),
        )
    }

    private fun parseDeviceBindingReceipt(response: SupabaseHttpResponse): PrivateDeviceBindingReceipt {
        val receipt = response.requireObject("device registration").requireObjectField("device_registration")
        return PrivateDeviceBindingReceipt(
            accountId = receipt.requireAccountId("user_id"),
            transportDeviceId = receipt.requireUuid("device_id"),
            signalDeviceId = SignalDeviceId.fromWire(receipt.requireInt("signal_device_id")),
            displayName = receipt.requireDisplayName("display_name"),
            boundAt = receipt.requireInstant("bound_at"),
        )
    }

    private fun parseRefreshedSession(response: SupabaseHttpResponse): RefreshedPrivateAccountSession {
        val responseBody = response.requireObject("session refresh")
        return RefreshedPrivateAccountSession(
            accountId = responseBody.requireObjectField("user").requireAccountId("id"),
            tokens = responseBody.toSessionTokens(clock),
        )
    }

    private fun parseRejection(response: SupabaseHttpResponse): PrivateAccountBackendOutcome.Rejected {
        val candidateMessage =
            (response.jsonBody as? JsonObject)
                ?.get("error")
                ?.let { error -> error as? JsonPrimitive }
                ?.takeIf { error -> error.isString }
                ?.content
        val userMessage =
            candidateMessage
                ?.takeIf { message ->
                    message.length in 1..200 && message.none(Char::isISOControl)
                } ?: when (response.statusCode) {
                401, 403 -> "Account access was denied."
                429 -> "Too many attempts. Try again later."
                else -> "Account access could not be completed."
            }
        val reason =
            when (response.statusCode) {
                400, 401, 403 -> PrivateBackendRejectionReason.ACCESS_DENIED
                429 -> PrivateBackendRejectionReason.RATE_LIMITED
                else -> PrivateBackendRejectionReason.REMOTE_FAILURE
            }
        return PrivateAccountBackendOutcome.Rejected(userMessage, reason)
    }
}

private fun requireBundleMatchesReservation(command: PrivateDeviceBindingCommand) {
    val reservation = command.reservation
    val bundleAddress = command.publicPreKeyBundle.address
    require(bundleAddress.accountId.toString() == reservation.accountId.canonical) {
        "Signal bundle account does not match the device reservation"
    }
    require(bundleAddress.transportDeviceId == reservation.transportDeviceId) {
        "Signal bundle transport device does not match the device reservation"
    }
    require(bundleAddress.protocolDeviceId == reservation.signalDeviceId) {
        "Signal bundle protocol device does not match the device reservation"
    }
}

private fun buildDeviceRegistrationBody(bundle: SignalPublicPreKeyBundle): JsonObject =
    buildJsonObject {
        put(
            "device",
            buildJsonObject {
                put("device_id", bundle.address.transportDeviceId.toString())
                put("protocol_adapter_version", bundle.protocolVersion)
                put("registration_id", bundle.registrationId.raw)
                put("signal_device_id", bundle.address.protocolDeviceId.raw)
                put("identity_key_hex", bundle.identityKeyBytes.toLowerHex())
                put(
                    "signed_pre_key",
                    buildJsonObject {
                        put("id", bundle.signedPreKey.id.raw)
                        put("public_key_hex", bundle.signedPreKey.publicKeyBytes.toLowerHex())
                        put("signature_hex", bundle.signedPreKey.signatureBytes.toLowerHex())
                    },
                )
                put(
                    "kyber_pre_key",
                    buildJsonObject {
                        put("id", bundle.kyberPreKey.id.raw)
                        put("public_key_hex", bundle.kyberPreKey.publicKeyBytes.toLowerHex())
                        put("signature_hex", bundle.kyberPreKey.signatureBytes.toLowerHex())
                    },
                )
                bundle.oneTimePreKey?.let { oneTimePreKey ->
                    put(
                        "one_time_pre_key",
                        buildJsonObject {
                            put("id", oneTimePreKey.id.raw)
                            put("public_key_hex", oneTimePreKey.publicKeyBytes.toLowerHex())
                        },
                    )
                }
            },
        )
    }

private fun SupabaseHttpResponse.requireObject(operation: String): JsonObject =
    jsonBody as? JsonObject ?: malformedResponse("Supabase $operation response is malformed")

private fun JsonObject.requireObjectField(field: String): JsonObject =
    this[field] as? JsonObject ?: malformedResponse("Supabase response field $field is malformed")

private fun JsonObject.requireString(field: String): String {
    val primitive = this[field] as? JsonPrimitive
    if (primitive == null || !primitive.isString) malformedResponse("Supabase response field $field is malformed")
    return primitive.content
}

private fun JsonObject.requireInt(field: String): Int =
    try {
        this[field]?.jsonPrimitive?.int ?: malformedResponse("Supabase response field $field is malformed")
    } catch (error: IllegalArgumentException) {
        malformedResponse("Supabase response field $field is malformed", error)
    }

private fun JsonObject.requireLong(field: String): Long =
    try {
        this[field]?.jsonPrimitive?.long ?: malformedResponse("Supabase response field $field is malformed")
    } catch (error: IllegalArgumentException) {
        malformedResponse("Supabase response field $field is malformed", error)
    }

private fun JsonObject.requireUuid(field: String): UUID {
    val rawUuid = requireString(field)
    val parsed =
        try {
            UUID.fromString(rawUuid)
        } catch (error: IllegalArgumentException) {
            malformedResponse("Supabase response field $field is malformed", error)
        }
    if (parsed.toString() != rawUuid || rawUuid != rawUuid.lowercase(Locale.ROOT)) {
        malformedResponse("Supabase response field $field is malformed")
    }
    return parsed
}

private fun JsonObject.requireAccountId(field: String): PrivateAccountId = PrivateAccountId(requireUuid(field).toString())

private fun JsonObject.requireDisplayName(field: String): PrivateDisplayName {
    val displayName = requireString(field)
    if (displayName.isBlank() || displayName.length > 64 || displayName.any(Char::isISOControl)) {
        malformedResponse("Supabase response field $field is malformed")
    }
    return PrivateDisplayName(displayName)
}

private fun JsonObject.requireInstant(field: String): Instant =
    try {
        Instant.parse(requireString(field))
    } catch (error: DateTimeParseException) {
        malformedResponse("Supabase response field $field is malformed", error)
    }

private fun JsonObject.toSessionTokens(clock: Clock): PrivateBackendSessionTokens {
    val tokenType = requireString("token_type")
    if (!tokenType.equals("bearer", ignoreCase = true)) {
        malformedResponse("Supabase session token type is unsupported")
    }
    val expiresInSeconds = requireLong("expires_in")
    if (expiresInSeconds !in 1..86_400) malformedResponse("Supabase session expiry is malformed")
    val explicitExpiry = this["expires_at"]
    val expiry =
        try {
            if (explicitExpiry == null || explicitExpiry is JsonNull) {
                clock.instant().plusSeconds(expiresInSeconds)
            } else {
                Instant.ofEpochSecond(requireLong("expires_at"))
            }
        } catch (error: DateTimeException) {
            malformedResponse("Supabase session expiry is malformed", error)
        }
    if (!expiry.isAfter(clock.instant().minusSeconds(MAXIMUM_CLOCK_SKEW_SECONDS))) {
        malformedResponse("Supabase session is already expired")
    }
    return PrivateBackendSessionTokens(
        expiresAt = expiry,
        accessToken = requireString("access_token"),
        refreshToken = requireString("refresh_token"),
    )
}

private fun ByteArray.toLowerHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xFF) }

private fun malformedResponse(
    message: String,
    cause: Throwable? = null,
): Nothing = throw SupabaseAccountResponseException(message, cause)

internal class SupabaseAccountResponseException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

private const val MAXIMUM_CLOCK_SKEW_SECONDS = 60L
