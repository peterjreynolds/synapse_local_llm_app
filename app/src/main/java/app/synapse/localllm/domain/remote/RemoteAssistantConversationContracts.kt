package app.synapse.localllm.domain.remote

import java.time.Instant
import kotlinx.coroutines.flow.Flow

@JvmInline
value class RemoteAssistantId(val raw: String) {
    init {
        require(raw.matches(REMOTE_ASSISTANT_ID_PATTERN)) { "Remote assistant ID is invalid." }
    }
}

@JvmInline
value class RemoteAssistantParticipantId(val raw: String) {
    init {
        require(raw.matches(REMOTE_ASSISTANT_PARTICIPANT_ID_PATTERN)) {
            "Remote assistant participant ID is invalid."
        }
    }
}

data class RemoteAssistantConversationEndpoint(
    val assistantId: RemoteAssistantId,
    val participantId: RemoteAssistantParticipantId,
    val roomId: RemoteRoomId,
    val displayName: String,
    val summary: String,
) {
    init {
        require(displayName.isNotBlank() && displayName.length <= 64) {
            "Remote assistant display name must contain 1-64 characters."
        }
        require(summary.isNotBlank() && summary.length <= 160) {
            "Remote assistant summary must contain 1-160 characters."
        }
    }
}

sealed interface RemoteAssistantAvailability {
    data object Available : RemoteAssistantAvailability

    data class Unavailable(
        val userMessage: String,
    ) : RemoteAssistantAvailability {
        init {
            require(userMessage.isNotBlank() && userMessage.length <= 240) {
                "Remote assistant unavailability must be concise and actionable."
            }
        }
    }
}

data class EnsureRemoteAssistantConversationCommand(
    val accountUid: RemoteAccountUid,
    val endpoint: RemoteAssistantConversationEndpoint,
)

interface RemoteAssistantConversationGateway {
    fun availability(endpoint: RemoteAssistantConversationEndpoint): RemoteAssistantAvailability

    fun observeMessages(
        accountUid: RemoteAccountUid,
        endpoint: RemoteAssistantConversationEndpoint,
    ): Flow<List<RemoteCachedMessage>>

    suspend fun sendMessage(
        endpoint: RemoteAssistantConversationEndpoint,
        command: SendRemoteMessageCommand,
    ): RemoteMessageSendReceipt
}

object RemoteAssistantConversationCatalog {
    val cinder = RemoteAssistantConversationEndpoint(
        assistantId = RemoteAssistantId("cinder"),
        participantId = RemoteAssistantParticipantId("participant-cinder-remote-ai"),
        roomId = RemoteRoomId("assistant_cinder"),
        displayName = "Cinder",
        summary = "Remote assistant",
    )

    val endpoints: List<RemoteAssistantConversationEndpoint> = listOf(cinder)

    fun findByRoomId(roomId: RemoteRoomId): RemoteAssistantConversationEndpoint? =
        endpoints.singleOrNull { endpoint -> endpoint.roomId == roomId }

    fun findByParticipantId(participantId: String): RemoteAssistantConversationEndpoint? =
        endpoints.singleOrNull { endpoint -> endpoint.participantId.raw == participantId }
}

fun isValidRemoteAssistantRoomId(rawRoomId: String): Boolean =
    RemoteAssistantConversationCatalog.endpoints.any { endpoint -> endpoint.roomId.raw == rawRoomId }

fun RemoteAssistantConversationEndpoint.toCachedRoom(accountUid: RemoteAccountUid): RemoteCachedRoom =
    RemoteCachedRoom(
        accountUid = accountUid,
        roomId = roomId,
        kind = RemoteRoomKind.ASSISTANT,
        directKey = null,
        peerUid = null,
        title = displayName,
        avatarObjectPath = null,
        unreadCount = 0,
        latestMessagePreview = summary,
        latestMessageSenderUid = null,
        currentMemberRole = RemoteRoomMemberRole.MEMBER,
        notificationsEnabled = true,
        isMuted = false,
        isArchived = false,
        isPinned = false,
        joinedAt = REMOTE_ASSISTANT_CONVERSATION_EPOCH,
        lastReadAt = null,
        remoteUpdatedAt = REMOTE_ASSISTANT_CONVERSATION_EPOCH,
    )

private val REMOTE_ASSISTANT_CONVERSATION_EPOCH: Instant = Instant.parse("2026-07-18T00:00:00Z")
private val REMOTE_ASSISTANT_ID_PATTERN = Regex("^[a-z][a-z0-9-]{1,63}$")
private val REMOTE_ASSISTANT_PARTICIPANT_ID_PATTERN = Regex("^participant-[a-z][a-z0-9-]{1,95}$")
