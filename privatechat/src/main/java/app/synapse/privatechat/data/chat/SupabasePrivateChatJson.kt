package app.synapse.privatechat.data.chat

import app.synapse.privatechat.data.supabase.SupabaseHttpResponse
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.UUID

internal fun SupabaseHttpResponse.requireChatRows(
    operation: String,
    maximumRows: Int = MAXIMUM_CHAT_RESPONSE_ROWS,
): List<JsonObject> {
    val rows = jsonBody as? JsonArray ?: malformedChatResponse("Supabase $operation response is malformed")
    if (rows.size > maximumRows) malformedChatResponse("Supabase $operation response exceeds its row limit")
    return rows.map { row ->
        row as? JsonObject ?: malformedChatResponse("Supabase $operation row is malformed")
    }
}

internal fun SupabaseHttpResponse.requireSingleChatRow(operation: String): JsonObject {
    val rows = requireChatRows(operation, maximumRows = 1)
    if (rows.size != 1) malformedChatResponse("Supabase $operation did not return exactly one receipt")
    return rows.single()
}

internal fun JsonObject.requireExactChatFields(vararg expectedFields: String) {
    if (keys != expectedFields.toSet()) malformedChatResponse("Supabase chat response fields are malformed")
}

internal fun JsonObject.requireChatString(field: String): String {
    val primitive = this[field] as? JsonPrimitive
    if (primitive == null || !primitive.isString) malformedChatResponse("Supabase chat field $field is malformed")
    return primitive.content
}

internal fun JsonObject.requireChatUuid(field: String): UUID {
    val rawUuid = requireChatString(field)
    if (rawUuid != rawUuid.lowercase(Locale.ROOT)) malformedChatResponse("Supabase chat field $field is malformed")
    val parsed =
        try {
            UUID.fromString(rawUuid)
        } catch (error: IllegalArgumentException) {
            throw SupabasePrivateChatResponseException("Supabase chat field $field is malformed", error)
        }
    if (parsed.toString() != rawUuid || parsed == NIL_UUID) {
        malformedChatResponse("Supabase chat field $field is malformed")
    }
    return parsed
}

internal fun JsonObject.requireNullableChatUuid(field: String): UUID? =
    when (val element = this[field]) {
        JsonNull -> null
        is JsonPrimitive -> {
            if (!element.isString) malformedChatResponse("Supabase chat field $field is malformed")
            buildJsonObjectForField(field, element).requireChatUuid(field)
        }

        else -> malformedChatResponse("Supabase chat field $field is malformed")
    }

internal fun JsonObject.requireChatInt(
    field: String,
    supportedRange: IntRange,
): Int {
    val parsed = (this[field] as? JsonPrimitive)?.intOrNull
    if (parsed == null || parsed !in supportedRange) malformedChatResponse("Supabase chat field $field is malformed")
    return parsed
}

internal fun JsonObject.requireChatBoolean(field: String): Boolean =
    (this[field] as? JsonPrimitive)?.booleanOrNull
        ?: malformedChatResponse("Supabase chat field $field is malformed")

internal fun JsonObject.requireChatInstant(field: String): Instant =
    try {
        Instant.parse(requireChatString(field))
    } catch (error: DateTimeParseException) {
        throw SupabasePrivateChatResponseException("Supabase chat field $field is malformed", error)
    }

internal fun JsonObject.requireNullableChatInstant(field: String): Instant? =
    when (val element = this[field]) {
        JsonNull -> null
        is JsonPrimitive -> {
            if (!element.isString) malformedChatResponse("Supabase chat field $field is malformed")
            try {
                Instant.parse(element.content)
            } catch (error: DateTimeParseException) {
                throw SupabasePrivateChatResponseException("Supabase chat field $field is malformed", error)
            }
        }

        else -> malformedChatResponse("Supabase chat field $field is malformed")
    }

internal fun JsonObject.requirePostgresBytea(
    field: String,
    supportedByteRange: IntRange,
): ByteArray {
    val encoded = requireChatString(field)
    if (!encoded.startsWith(POSTGRES_HEX_PREFIX)) malformedChatResponse("Supabase chat field $field is malformed")
    val hexadecimal = encoded.substring(POSTGRES_HEX_PREFIX.length)
    if (
        hexadecimal.length % 2 != 0 ||
        hexadecimal.length / 2 !in supportedByteRange ||
        !LOWER_HEXADECIMAL_PATTERN.matches(hexadecimal)
    ) {
        malformedChatResponse("Supabase chat field $field is malformed")
    }
    return ByteArray(hexadecimal.length / 2) { byteIndex ->
        hexadecimal.substring(byteIndex * 2, byteIndex * 2 + 2).toInt(16).toByte()
    }
}

internal fun JsonObject.requireNullablePostgresBytea(
    field: String,
    supportedByteRange: IntRange,
): ByteArray? =
    when (val element = this[field]) {
        JsonNull -> null
        is JsonPrimitive -> {
            if (!element.isString) malformedChatResponse("Supabase chat field $field is malformed")
            buildJsonObjectForField(field, element).requirePostgresBytea(field, supportedByteRange)
        }

        else -> malformedChatResponse("Supabase chat field $field is malformed")
    }

internal fun SupabaseHttpResponse.requireChatMutationSuccess(operation: String): JsonObject {
    if (statusCode !in 200..299) throw requireChatMutationRejection()
    return requireSingleChatRow(operation)
}

internal fun SupabaseHttpResponse.requireChatMutationRejection(): SupabasePrivateChatRequestRejectedException {
    val serverMessage =
        (jsonBody as? JsonObject)
            ?.get("message")
            ?.let { element -> element as? JsonPrimitive }
            ?.takeIf(JsonPrimitive::isString)
            ?.content
            ?.takeIf(::isSafeServerRejection)
    val userMessage =
        when (statusCode) {
            401, 403 -> "This encrypted chat action is not authorized."
            409 -> "This chat changed on another device. Refresh and try again."
            429 -> "Too many chat actions. Try again shortly."
            else -> serverMessage?.let(::mapSafeServerRejection) ?: "The encrypted chat action could not be completed."
        }
    return SupabasePrivateChatRequestRejectedException(userMessage)
}

internal fun ByteArray.toLowerHex(): String =
    joinToString(separator = "") { byte -> HEX_DIGITS[(byte.toInt() ushr 4) and 0xF].toString() + HEX_DIGITS[byte.toInt() and 0xF] }

internal class SupabasePrivateChatResponseException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

internal class SupabasePrivateChatRequestRejectedException(
    val userMessage: String,
) : IllegalStateException("Supabase rejected an authenticated private chat request")

private fun buildJsonObjectForField(
    field: String,
    element: JsonElement,
): JsonObject = JsonObject(mapOf(field to element))

private fun isSafeServerRejection(message: String): Boolean = message.length in 1..200 && message.none(Char::isISOControl)

private fun mapSafeServerRejection(serverMessage: String): String =
    when {
        "revision" in serverMessage.lowercase(Locale.ROOT) ->
            "This chat changed on another device. Refresh and try again."

        "mutation id" in serverMessage.lowercase(Locale.ROOT) ->
            "This chat action conflicts with an earlier request."

        else -> "The encrypted chat action was rejected."
    }

private fun malformedChatResponse(message: String): Nothing = throw SupabasePrivateChatResponseException(message)

private const val MAXIMUM_CHAT_RESPONSE_ROWS = 2_000
private const val POSTGRES_HEX_PREFIX = "\\x"
private val NIL_UUID = UUID(0L, 0L)
private val LOWER_HEXADECIMAL_PATTERN = Regex("^[0-9a-f]+$")
private val HEX_DIGITS = "0123456789abcdef"
