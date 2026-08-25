package app.synapse.privatechat.domain.chat

import app.synapse.privatechat.domain.account.PrivateAccountId
import app.synapse.privatechat.domain.account.PrivateInvitationCode
import java.text.Normalizer
import java.time.Instant

data class PrivateProfileSnapshot(
    val accountId: PrivateAccountId,
    val displayName: String,
    val username: String,
) {
    init {
        requireValidPrivateProfileDisplayName(displayName)
        require(PRIVATE_PROFILE_USERNAME_PATTERN.matches(username)) { "Profile username is invalid." }
    }
}

enum class PrivateSocialInputField {
    DISPLAY_NAME,
    ROOM_TITLE,
}

sealed interface PrivateSocialTextValidation {
    data class Accepted(
        val normalizedText: String,
    ) : PrivateSocialTextValidation

    data class Rejected(
        val field: PrivateSocialInputField,
        val userMessage: String,
    ) : PrivateSocialTextValidation
}

fun validatePrivateProfileDisplayName(input: String): PrivateSocialTextValidation {
    val normalizedDisplayName = normalizePrivateDisplayText(input)
    return if (isValidPrivateDisplayText(normalizedDisplayName, PRIVATE_PROFILE_DISPLAY_NAME_LIMIT)) {
        PrivateSocialTextValidation.Accepted(normalizedDisplayName)
    } else {
        PrivateSocialTextValidation.Rejected(
            field = PrivateSocialInputField.DISPLAY_NAME,
            userMessage = "Enter a display name with 1–$PRIVATE_PROFILE_DISPLAY_NAME_LIMIT supported characters.",
        )
    }
}

fun validatePrivateRoomTitle(input: String): PrivateSocialTextValidation {
    val normalizedTitle = normalizePrivateDisplayText(input)
    return if (isValidPrivateDisplayText(normalizedTitle, PRIVATE_ROOM_TITLE_LIMIT)) {
        PrivateSocialTextValidation.Accepted(normalizedTitle)
    } else {
        PrivateSocialTextValidation.Rejected(
            field = PrivateSocialInputField.ROOM_TITLE,
            userMessage = "Enter a conversation name with 1–$PRIVATE_ROOM_TITLE_LIMIT supported characters.",
        )
    }
}

data class UpdatePrivateProfileCommand(
    val accountId: PrivateAccountId,
    val mutationId: PrivateClientMutationId,
    val displayName: String,
) {
    init {
        requireValidPrivateProfileDisplayName(displayName)
    }
}

data class CreatePrivateRoomCommand(
    val accountId: PrivateAccountId,
    val mutationId: PrivateClientMutationId,
    val kind: PrivateRoomKind,
    val title: String,
    val retention: PrivateMessageRetention,
) {
    init {
        require(isValidPrivateDisplayText(title, PRIVATE_ROOM_TITLE_LIMIT)) { "Conversation title is invalid." }
    }
}

data class ChangePrivateGroupMemberRoleCommand(
    val accountId: PrivateAccountId,
    val roomId: PrivateRoomId,
    val mutationId: PrivateClientMutationId,
    val memberAccountId: PrivateAccountId,
    val role: PrivateRoomMemberRole,
) {
    init {
        require(accountId != memberAccountId) { "A member cannot change their own role with this command." }
        require(role != PrivateRoomMemberRole.OWNER) { "Ownership transfer requires a separate explicit command." }
    }
}

data class RemovePrivateGroupMemberCommand(
    val accountId: PrivateAccountId,
    val roomId: PrivateRoomId,
    val mutationId: PrivateClientMutationId,
    val memberAccountId: PrivateAccountId,
) {
    init {
        require(accountId != memberAccountId) { "A member cannot remove themselves with this command." }
    }
}

data class CreatePrivateOneUseAccountInvitationCommand(
    val accountId: PrivateAccountId,
    val mutationId: PrivateClientMutationId,
)

data class RedeemPrivateRoomInvitationCommand(
    val accountId: PrivateAccountId,
    val mutationId: PrivateClientMutationId,
    val invitationCode: PrivateRoomInvitationCode,
)

enum class PrivatePresenceSharingState {
    DISABLED,
    ENABLED,
}

data class ChangePrivatePresenceSharingCommand(
    val accountId: PrivateAccountId,
    val mutationId: PrivateClientMutationId,
    val sharingState: PrivatePresenceSharingState,
)

data class PublishPrivatePresenceCommand(
    val accountId: PrivateAccountId,
    val mutationId: PrivateClientMutationId,
)

data class PrivatePresenceSnapshot(
    val accountId: PrivateAccountId,
    val displayName: String,
    val publishedAt: Instant,
    val expiresAt: Instant,
) {
    init {
        requireValidPrivateProfileDisplayName(displayName)
        require(expiresAt.isAfter(publishedAt) && expiresAt <= publishedAt.plusSeconds(PRIVATE_PRESENCE_MAX_TTL_SECONDS)) {
            "Visible presence must be short-lived."
        }
    }
}

data class PrivateSocialSnapshot(
    val accountId: PrivateAccountId,
    val profile: PrivateProfileSnapshot,
    val presenceSharing: PrivatePresenceSharingState,
    val visiblePresence: List<PrivatePresenceSnapshot>,
) {
    init {
        require(profile.accountId == accountId) { "The social profile must belong to the current account." }
        require(visiblePresence.none { presence -> presence.accountId == accountId }) {
            "The current account must not appear in its own visible presence list."
        }
        require(visiblePresence.distinctBy(PrivatePresenceSnapshot::accountId).size == visiblePresence.size) {
            "Visible presence entries must be unique."
        }
    }
}

sealed interface PrivateSocialMutationReceipt : PrivateMutationReceipt {
    data class ProfileUpdated(
        override val accountId: PrivateAccountId,
        override val mutationId: PrivateClientMutationId,
        val profile: PrivateProfileSnapshot,
    ) : PrivateSocialMutationReceipt

    data class RoomCreated(
        override val accountId: PrivateAccountId,
        override val mutationId: PrivateClientMutationId,
        val roomId: PrivateRoomId,
        val kind: PrivateRoomKind,
    ) : PrivateSocialMutationReceipt

    data class GroupMemberRoleChanged(
        override val accountId: PrivateAccountId,
        override val mutationId: PrivateClientMutationId,
        val roomId: PrivateRoomId,
        val memberAccountId: PrivateAccountId,
        val role: PrivateRoomMemberRole,
    ) : PrivateSocialMutationReceipt

    data class GroupMemberRemoved(
        override val accountId: PrivateAccountId,
        override val mutationId: PrivateClientMutationId,
        val roomId: PrivateRoomId,
        val memberAccountId: PrivateAccountId,
    ) : PrivateSocialMutationReceipt

    data class OneUseAccountInvitationCreated(
        override val accountId: PrivateAccountId,
        override val mutationId: PrivateClientMutationId,
        val invitationId: PrivateAccountInvitationId,
        val invitationCode: PrivateInvitationCode,
        val expiresAt: Instant,
    ) : PrivateSocialMutationReceipt

    data class RoomInvitationRedeemed(
        override val accountId: PrivateAccountId,
        override val mutationId: PrivateClientMutationId,
        val roomId: PrivateRoomId,
        val membershipEpoch: Int,
        val completedAt: Instant,
    ) : PrivateSocialMutationReceipt {
        init {
            require(membershipEpoch > 0) { "Redeemed room membership epoch must be positive." }
        }
    }

    data class PresenceSharingChanged(
        override val accountId: PrivateAccountId,
        override val mutationId: PrivateClientMutationId,
        val sharingState: PrivatePresenceSharingState,
    ) : PrivateSocialMutationReceipt

    data class PresencePublished(
        override val accountId: PrivateAccountId,
        override val mutationId: PrivateClientMutationId,
        val publishedAt: Instant,
        val expiresAt: Instant,
    ) : PrivateSocialMutationReceipt {
        init {
            require(
                expiresAt.isAfter(publishedAt) &&
                    expiresAt <= publishedAt.plusSeconds(PRIVATE_PRESENCE_MAX_TTL_SECONDS),
            ) {
                "Persisted presence must use a short-lived server interval."
            }
        }
    }
}

interface PrivateSocialGateway {
    fun observeSocial(accountId: PrivateAccountId): kotlinx.coroutines.flow.Flow<PrivateChatObservation<PrivateSocialSnapshot>>

    suspend fun updateProfile(
        command: UpdatePrivateProfileCommand,
    ): PrivateChatMutationOutcome<PrivateSocialMutationReceipt.ProfileUpdated>

    suspend fun createRoom(command: CreatePrivateRoomCommand): PrivateChatMutationOutcome<PrivateSocialMutationReceipt.RoomCreated>

    suspend fun changeGroupMemberRole(
        command: ChangePrivateGroupMemberRoleCommand,
    ): PrivateChatMutationOutcome<PrivateSocialMutationReceipt.GroupMemberRoleChanged>

    suspend fun removeGroupMember(
        command: RemovePrivateGroupMemberCommand,
    ): PrivateChatMutationOutcome<PrivateSocialMutationReceipt.GroupMemberRemoved>

    suspend fun createOneUseAccountInvitation(
        command: CreatePrivateOneUseAccountInvitationCommand,
    ): PrivateChatMutationOutcome<PrivateSocialMutationReceipt.OneUseAccountInvitationCreated>

    suspend fun redeemRoomInvitation(
        command: RedeemPrivateRoomInvitationCommand,
    ): PrivateChatMutationOutcome<PrivateSocialMutationReceipt.RoomInvitationRedeemed>

    suspend fun changePresenceSharing(
        command: ChangePrivatePresenceSharingCommand,
    ): PrivateChatMutationOutcome<PrivateSocialMutationReceipt.PresenceSharingChanged>

    suspend fun publishPresence(
        command: PublishPrivatePresenceCommand,
    ): PrivateChatMutationOutcome<PrivateSocialMutationReceipt.PresencePublished>
}

private fun normalizePrivateDisplayText(input: String): String = Normalizer.normalize(input, Normalizer.Form.NFKC).trim()

private fun requireValidPrivateProfileDisplayName(displayName: String) {
    require(isValidPrivateDisplayText(displayName, PRIVATE_PROFILE_DISPLAY_NAME_LIMIT)) {
        "Profile display name is invalid."
    }
}

private fun isValidPrivateDisplayText(
    text: String,
    maximumLength: Int,
): Boolean = text.isNotBlank() && text.length <= maximumLength && text.none(Char::isISOControl)

private val PRIVATE_PROFILE_USERNAME_PATTERN = Regex("^[a-z][a-z0-9_]{2,31}$")
private const val PRIVATE_PROFILE_DISPLAY_NAME_LIMIT = 64
private const val PRIVATE_ROOM_TITLE_LIMIT = 128
internal const val PRIVATE_PRESENCE_MAX_TTL_SECONDS = 120L
