package app.synapse.privatechat.domain.chat

@JvmInline
value class PrivateRoomId internal constructor(
    val canonical: String,
)

@JvmInline
value class PrivateMessageId internal constructor(
    val canonical: String,
)

@JvmInline
value class PrivateClientMutationId internal constructor(
    val canonical: String,
)

@JvmInline
value class PrivateRoomInvitationId internal constructor(
    val canonical: String,
)

@JvmInline
value class PrivateAccountInvitationId internal constructor(
    val canonical: String,
)

@JvmInline
value class PrivateMessageText internal constructor(
    val plaintext: String,
) {
    override fun toString(): String = "PrivateMessageText([REDACTED])"
}

@JvmInline
value class PrivateReactionCode internal constructor(
    val canonical: String,
)

@JvmInline
value class PrivateRoomInvitationCode internal constructor(
    val secret: String,
) {
    override fun toString(): String = "PrivateRoomInvitationCode([REDACTED])"
}

fun parsePrivateRoomId(input: String): PrivateRoomId? = normalizePrivateOpaqueIdentifier(input)?.let(::PrivateRoomId)

fun parsePrivateMessageId(input: String): PrivateMessageId? = normalizePrivateOpaqueIdentifier(input)?.let(::PrivateMessageId)

fun parsePrivateClientMutationId(input: String): PrivateClientMutationId? =
    normalizePrivateOpaqueIdentifier(input)?.let(::PrivateClientMutationId)

fun parsePrivateRoomInvitationId(input: String): PrivateRoomInvitationId? =
    normalizePrivateOpaqueIdentifier(input)?.let(::PrivateRoomInvitationId)

fun parsePrivateAccountInvitationId(input: String): PrivateAccountInvitationId? =
    normalizePrivateOpaqueIdentifier(input)?.let(::PrivateAccountInvitationId)

fun parsePrivateRoomInvitationCode(input: String): PrivateRoomInvitationCode? =
    input
        .trim()
        .takeIf(PRIVATE_ROOM_INVITATION_CODE_PATTERN::matches)
        ?.let(::PrivateRoomInvitationCode)

private fun normalizePrivateOpaqueIdentifier(input: String): String? =
    input
        .trim()
        .takeIf { identifier ->
            identifier.length in PRIVATE_OPAQUE_IDENTIFIER_LENGTH_RANGE &&
                identifier.none(Char::isISOControl)
        }

private val PRIVATE_OPAQUE_IDENTIFIER_LENGTH_RANGE = 1..128
private val PRIVATE_ROOM_INVITATION_CODE_PATTERN = Regex("^[A-Za-z0-9_-]{43}$")
