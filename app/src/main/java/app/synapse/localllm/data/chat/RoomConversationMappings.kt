package app.synapse.localllm.data.chat

import app.synapse.localllm.data.db.ChatMessageWithAuthorEntity
import app.synapse.localllm.data.db.ChatParticipantEntity
import app.synapse.localllm.data.db.ChatThreadEntity
import app.synapse.localllm.data.db.RoomMemberWithParticipantEntity
import app.synapse.localllm.domain.chat.AiResponsePolicy
import app.synapse.localllm.domain.chat.ChatMessageRecord
import app.synapse.localllm.domain.chat.ChatRoomRecord
import app.synapse.localllm.domain.chat.ConversationRole
import app.synapse.localllm.domain.chat.MessageDeliveryState
import app.synapse.localllm.domain.chat.ParticipantKind
import app.synapse.localllm.domain.chat.ParticipantRecord
import app.synapse.localllm.domain.chat.RoomId
import app.synapse.localllm.domain.chat.RoomKind
import app.synapse.localllm.domain.chat.RoomMemberRecord
import app.synapse.localllm.domain.chat.RoomMemberRole
import app.synapse.localllm.domain.chat.SyncMetadata
import app.synapse.localllm.domain.chat.SyncState
import app.synapse.localllm.domain.ids.ChatMessageId
import app.synapse.localllm.domain.ids.ChatThreadId
import app.synapse.localllm.domain.ids.ParticipantId
import java.time.Instant

internal fun ChatThreadEntity.toDomain(members: List<RoomMemberRecord>): ChatRoomRecord =
    ChatRoomRecord(
        id = ChatThreadId(id),
        title = title,
        kind = RoomKind.valueOf(roomKind),
        isPinned = pinnedAtEpochMillis != null,
        members = members,
        syncMetadata = SyncMetadata(
            remoteId = remoteId,
            revision = revision,
            state = SyncState.valueOf(syncState),
        ),
        createdAt = Instant.ofEpochMilli(createdAtEpochMillis),
        updatedAt = Instant.ofEpochMilli(updatedAtEpochMillis),
    )

internal fun RoomMemberWithParticipantEntity.toDomain(): RoomMemberRecord {
    val resolvedParticipant = checkNotNull(participant) {
        "Room $membership.roomId member ${membership.participantId} has no participant profile."
    }
    return RoomMemberRecord(
        roomId = RoomId(membership.roomId),
        participant = resolvedParticipant.toDomain(),
        role = RoomMemberRole.valueOf(membership.role),
        canPost = membership.canPost,
        joinedAt = Instant.ofEpochMilli(membership.joinedAtEpochMillis),
        leftAt = membership.leftAtEpochMillis?.let(Instant::ofEpochMilli),
        aiResponsePolicy = AiResponsePolicy.valueOf(membership.aiResponsePolicy),
        syncMetadata = SyncMetadata(
            remoteId = membership.remoteId,
            revision = membership.revision,
            state = SyncState.valueOf(membership.syncState),
        ),
    )
}

internal fun ChatMessageWithAuthorEntity.toDomain(): ChatMessageRecord {
    val resolvedAuthor = checkNotNull(author) {
        "Chat message ${message.id} has no durable participant author."
    }
    return ChatMessageRecord(
        id = ChatMessageId(message.id),
        threadId = ChatThreadId(message.threadId),
        author = resolvedAuthor.toDomain(),
        role = ConversationRole.valueOf(message.role),
        body = message.body,
        deliveryState = MessageDeliveryState.valueOf(message.deliveryState),
        syncMetadata = SyncMetadata(
            remoteId = message.remoteId,
            revision = message.revision,
            state = SyncState.valueOf(message.syncState),
        ),
        createdAt = Instant.ofEpochMilli(message.createdAtEpochMillis),
        completedAt = message.completedAtEpochMillis?.let(Instant::ofEpochMilli),
        failureReason = message.failureReason,
    )
}

internal fun ChatParticipantEntity.toDomain(): ParticipantRecord =
    ParticipantRecord(
        id = ParticipantId(id),
        kind = ParticipantKind.valueOf(kind),
        displayName = displayName,
        avatarUri = avatarUri,
        avatarColorArgb = avatarColorArgb,
        syncMetadata = SyncMetadata(
            remoteId = remoteId,
            revision = revision,
            state = SyncState.valueOf(syncState),
        ),
        createdAt = Instant.ofEpochMilli(createdAtEpochMillis),
        updatedAt = Instant.ofEpochMilli(updatedAtEpochMillis),
    )
