package app.synapse.localllm.data.chat

import androidx.room.withTransaction
import app.synapse.localllm.data.db.AttachmentEntity
import app.synapse.localllm.data.db.ChatDao
import app.synapse.localllm.data.db.ChatMessageAuthorEntity
import app.synapse.localllm.data.db.ChatMessageEntity
import app.synapse.localllm.data.db.ChatParticipantEntity
import app.synapse.localllm.data.db.ChatThreadEntity
import app.synapse.localllm.data.db.RoomMembershipEntity
import app.synapse.localllm.data.db.SynapseDatabase
import app.synapse.localllm.domain.chat.AddHumanRoomMemberCommand
import app.synapse.localllm.domain.chat.AiResponsePolicy
import app.synapse.localllm.domain.chat.AiResponseStartReceipt
import app.synapse.localllm.domain.chat.BuiltInParticipantIds
import app.synapse.localllm.domain.chat.ChatMessageRecord
import app.synapse.localllm.domain.chat.ChatRoomRecord
import app.synapse.localllm.domain.chat.ChatThreadMutation
import app.synapse.localllm.domain.chat.ChatThreadMutationReceipt
import app.synapse.localllm.domain.chat.ConversationRepository
import app.synapse.localllm.domain.chat.ConversationRole
import app.synapse.localllm.domain.chat.CreateRoomCommand
import app.synapse.localllm.domain.chat.HumanMessageReceipt
import app.synapse.localllm.domain.chat.MessageDeliveryState
import app.synapse.localllm.domain.chat.ParticipantKind
import app.synapse.localllm.domain.chat.RoomId
import app.synapse.localllm.domain.chat.RoomKind
import app.synapse.localllm.domain.chat.RoomMemberRecord
import app.synapse.localllm.domain.chat.RoomMemberRole
import app.synapse.localllm.domain.chat.RoomMembershipMutation
import app.synapse.localllm.domain.chat.RoomMembershipMutationReceipt
import app.synapse.localllm.domain.chat.SubmitHumanMessageCommand
import app.synapse.localllm.domain.chat.SyncState
import app.synapse.localllm.domain.ids.ChatMessageId
import app.synapse.localllm.domain.ids.ParticipantId
import app.synapse.localllm.domain.ids.SynapseIdFactory
import app.synapse.localllm.domain.sms.SmsAutoReplyState
import app.synapse.localllm.domain.time.SynapseClock
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class RoomConversationRepository(
    private val database: SynapseDatabase,
    private val chatDao: ChatDao,
    private val idFactory: SynapseIdFactory,
    private val clock: SynapseClock,
) : ConversationRepository {
    override suspend fun ensureDefaultRoom(): ChatRoomRecord {
        ensureBuiltInParticipants()
        val latestRoom = chatDao.findLatestThread()
        return if (latestRoom == null) {
            createRoom(
                CreateRoomCommand(
                    title = "Synapse",
                    kind = RoomKind.AI_CHAT,
                    placeholderHumanDisplayNames = emptyList(),
                    includeSynapseAi = true,
                    synapseAiAutoResponseEnabled = true,
                ),
            )
        } else {
            latestRoom.toDomain(chatDao.listRoomMembers(latestRoom.id).map { member -> member.toDomain() })
        }
    }

    override suspend fun findRoom(roomId: RoomId): ChatRoomRecord? {
        val room = chatDao.findThread(roomId.raw) ?: return null
        val members = chatDao.listRoomMembers(roomId.raw).map { member -> member.toDomain() }
        return room.toDomain(members)
    }

    override suspend fun createRoom(command: CreateRoomCommand): ChatRoomRecord {
        val title = normalizeManualRoomTitle(command.title)
        val placeholderHumanNames = normalizePlaceholderHumanNames(command.placeholderHumanDisplayNames)
        validateCreateRoomCommand(command, placeholderHumanNames)
        val now = clock.now()
        val roomId = idFactory.createChatThreadId()

        database.withTransaction {
            ensureBuiltInParticipants(now)
            val insertedRoom = chatDao.insertThreadIfAbsent(
                ChatThreadEntity(
                    id = roomId.raw,
                    title = title,
                    pinnedAtEpochMillis = null,
                    archivedAtEpochMillis = null,
                    titleEditedByUser = true,
                    createdAtEpochMillis = now.toEpochMilli(),
                    updatedAtEpochMillis = now.toEpochMilli(),
                    roomKind = command.kind.name,
                    remoteId = null,
                    revision = 0,
                    syncState = SyncState.LOCAL_ONLY.name,
                ),
            )
            check(insertedRoom != INSERT_IGNORED) { "Room ${roomId.raw} already exists." }
            insertMembership(
                roomId = roomId,
                participantId = BuiltInParticipantIds.LOCAL_HUMAN,
                role = RoomMemberRole.OWNER,
                canPost = true,
                joinedAt = now,
                aiResponsePolicy = AiResponsePolicy.NEVER,
            )
            placeholderHumanNames.forEach { displayName ->
                val participantId = idFactory.createParticipantId()
                insertParticipant(
                    participant = newHumanParticipant(
                        participantId = participantId,
                        displayName = displayName,
                        avatarUri = null,
                        avatarColorArgb = null,
                        now = now,
                    ),
                )
                insertMembership(
                    roomId = roomId,
                    participantId = participantId,
                    role = RoomMemberRole.MEMBER,
                    canPost = true,
                    joinedAt = now,
                    aiResponsePolicy = AiResponsePolicy.NEVER,
                )
            }
            if (command.includeSynapseAi) {
                insertMembership(
                    roomId = roomId,
                    participantId = BuiltInParticipantIds.SYNAPSE_LOCAL_AI,
                    role = RoomMemberRole.MEMBER,
                    canPost = true,
                    joinedAt = now,
                    aiResponsePolicy = if (
                        command.kind == RoomKind.AI_CHAT || command.synapseAiAutoResponseEnabled
                    ) {
                        AiResponsePolicy.AUTOMATIC
                    } else {
                        AiResponsePolicy.MENTION_ONLY
                    },
                )
            }
        }

        return checkNotNull(findRoom(roomId)) { "Room ${roomId.raw} was not found after creation." }
    }

    override fun observeRooms(): Flow<List<ChatRoomRecord>> =
        combine(
            chatDao.observeThreads(),
            chatDao.observeAllRoomMembers(),
        ) { rooms, members ->
            val membersByRoom = members
                .map { member -> member.toDomain() }
                .groupBy(RoomMemberRecord::roomId)
            rooms.map { room -> room.toDomain(membersByRoom[RoomId(room.id)].orEmpty()) }
        }

    override fun observeRoomMembers(roomId: RoomId): Flow<List<RoomMemberRecord>> =
        chatDao.observeRoomMembers(roomId.raw).map { members ->
            members.map { member -> member.toDomain() }
        }

    override suspend fun addHumanRoomMember(
        command: AddHumanRoomMemberCommand,
    ): RoomMembershipMutationReceipt {
        val displayName = normalizeParticipantDisplayName(command.displayName)
        val now = clock.now()
        val participantId = idFactory.createParticipantId()
        database.withTransaction {
            val room = checkNotNull(chatDao.findThread(command.roomId.raw)) {
                "Room ${command.roomId.raw} was not found."
            }
            check(room.archivedAtEpochMillis == null) { "Room ${command.roomId.raw} is archived." }
            val roomKind = RoomKind.valueOf(room.roomKind)
            require(roomKind != RoomKind.AI_CHAT) { "Human members cannot be added to an AI chat." }
            val historicalOtherHumanCount = chatDao.listRoomMembers(command.roomId.raw).count { member ->
                val participant = checkNotNull(member.participant) {
                    "Room ${command.roomId.raw} member ${member.membership.participantId} has no participant profile."
                }
                participant.kind == ParticipantKind.HUMAN.name &&
                    participant.id != BuiltInParticipantIds.LOCAL_HUMAN.raw
            }
            require(roomKind != RoomKind.DIRECT || historicalOtherHumanCount == 0) {
                "A direct room remains bound to its original human member; create a new direct room instead."
            }
            insertParticipant(
                newHumanParticipant(
                    participantId = participantId,
                    displayName = displayName,
                    avatarUri = command.avatarUri,
                    avatarColorArgb = command.avatarColorArgb,
                    now = now,
                ),
            )
            insertMembership(
                roomId = command.roomId,
                participantId = participantId,
                role = RoomMemberRole.MEMBER,
                canPost = true,
                joinedAt = now,
                aiResponsePolicy = AiResponsePolicy.NEVER,
            )
        }
        return RoomMembershipMutationReceipt(
            roomId = command.roomId,
            participantId = participantId,
            mutation = RoomMembershipMutation.HUMAN_ADDED,
            changedAt = now,
            affectedRows = 1,
        )
    }

    override suspend fun removeRoomMember(
        roomId: RoomId,
        participantId: ParticipantId,
    ): RoomMembershipMutationReceipt {
        val room = requireRoom(roomId)
        require(participantId != BuiltInParticipantIds.LOCAL_HUMAN) { "The local room owner cannot be removed." }
        if (participantId == BuiltInParticipantIds.SYNAPSE_LOCAL_AI) {
            require(room.kind != RoomKind.AI_CHAT) { "Synapse cannot be removed from an AI chat." }
        }
        val participant = checkNotNull(chatDao.findParticipant(participantId.raw)) {
            "Participant ${participantId.raw} was not found."
        }
        val changedAt = clock.now()
        val affectedRows = chatDao.softLeaveRoomMembership(
            roomId = roomId.raw,
            participantId = participantId.raw,
            leftAtEpochMillis = changedAt.toEpochMilli(),
        )
        return RoomMembershipMutationReceipt(
            roomId = roomId,
            participantId = participantId,
            mutation = if (participant.kind == ParticipantKind.LOCAL_AI.name) {
                RoomMembershipMutation.SYNAPSE_AI_REMOVED
            } else {
                RoomMembershipMutation.MEMBER_REMOVED
            },
            changedAt = changedAt,
            affectedRows = affectedRows,
        )
    }

    override suspend fun setSynapseAiEnabled(
        roomId: RoomId,
        enabled: Boolean,
    ): RoomMembershipMutationReceipt {
        val room = requireRoom(roomId)
        if (!enabled) {
            return removeRoomMember(roomId, BuiltInParticipantIds.SYNAPSE_LOCAL_AI)
        }
        val changedAt = clock.now()
        val affectedRows = database.withTransaction {
            ensureBuiltInParticipants(changedAt)
            val existingMembership = chatDao.findRoomMembership(
                roomId = roomId.raw,
                participantId = BuiltInParticipantIds.SYNAPSE_LOCAL_AI.raw,
            )
            when {
                existingMembership == null -> {
                    insertMembership(
                        roomId = roomId,
                        participantId = BuiltInParticipantIds.SYNAPSE_LOCAL_AI,
                        role = RoomMemberRole.MEMBER,
                        canPost = true,
                        joinedAt = changedAt,
                        aiResponsePolicy = if (room.kind == RoomKind.AI_CHAT) {
                            AiResponsePolicy.AUTOMATIC
                        } else {
                            AiResponsePolicy.MENTION_ONLY
                        },
                    )
                    1
                }

                existingMembership.leftAtEpochMillis != null ->
                    chatDao.reactivateRoomMembership(
                        roomId = roomId.raw,
                        participantId = BuiltInParticipantIds.SYNAPSE_LOCAL_AI.raw,
                        role = RoomMemberRole.MEMBER.name,
                        canPost = true,
                        joinedAtEpochMillis = changedAt.toEpochMilli(),
                        aiResponsePolicy = if (room.kind == RoomKind.AI_CHAT) {
                            AiResponsePolicy.AUTOMATIC.name
                        } else {
                            AiResponsePolicy.MENTION_ONLY.name
                        },
                    )

                else -> 0
            }
        }
        return RoomMembershipMutationReceipt(
            roomId = roomId,
            participantId = BuiltInParticipantIds.SYNAPSE_LOCAL_AI,
            mutation = RoomMembershipMutation.SYNAPSE_AI_ADDED,
            changedAt = changedAt,
            affectedRows = affectedRows,
        )
    }

    override suspend fun setRoomAiAutoResponse(
        roomId: RoomId,
        enabled: Boolean,
    ): RoomMembershipMutationReceipt {
        val room = requireRoom(roomId)
        require(room.kind != RoomKind.AI_CHAT) { "AI chats always respond automatically." }
        val synapseMember = room.activeMembers.firstOrNull { member ->
            member.participant.id == BuiltInParticipantIds.SYNAPSE_LOCAL_AI
        }
        requireNotNull(synapseMember) { "Synapse is not an active member of this room." }
        val changedAt = clock.now()
        val affectedRows = chatDao.updateRoomMemberAiResponsePolicy(
            roomId = roomId.raw,
            participantId = BuiltInParticipantIds.SYNAPSE_LOCAL_AI.raw,
            aiResponsePolicy = if (enabled) {
                AiResponsePolicy.AUTOMATIC.name
            } else {
                AiResponsePolicy.MENTION_ONLY.name
            },
        )
        return RoomMembershipMutationReceipt(
            roomId = roomId,
            participantId = BuiltInParticipantIds.SYNAPSE_LOCAL_AI,
            mutation = RoomMembershipMutation.AI_RESPONSE_POLICY_UPDATED,
            changedAt = changedAt,
            affectedRows = affectedRows,
        )
    }

    override fun observeMessages(threadId: RoomId): Flow<List<ChatMessageRecord>> =
        chatDao.observeMessagesWithAuthors(threadId.raw).map { messages ->
            messages.map { message -> message.toDomain() }
        }

    override suspend fun listRecentMessages(
        threadId: RoomId,
        limit: Int,
    ): List<ChatMessageRecord> =
        chatDao.listRecentMessagesWithAuthors(threadId.raw, limit)
            .asReversed()
            .map { message -> message.toDomain() }

    override suspend fun findMessage(messageId: ChatMessageId): ChatMessageRecord? =
        chatDao.findMessageWithAuthor(messageId.raw)?.toDomain()

    override suspend fun setRoomPinned(
        roomId: RoomId,
        pinned: Boolean,
    ): ChatThreadMutationReceipt {
        val changedAt = clock.now()
        val affectedRows = chatDao.updateThreadPin(
            threadId = roomId.raw,
            pinnedAtEpochMillis = if (pinned) changedAt.toEpochMilli() else null,
        )
        return ChatThreadMutationReceipt(
            threadId = roomId,
            mutation = if (pinned) ChatThreadMutation.PINNED else ChatThreadMutation.UNPINNED,
            changedAt = changedAt,
            affectedRows = affectedRows,
        )
    }

    override suspend fun renameRoom(
        roomId: RoomId,
        title: String,
    ): ChatThreadMutationReceipt {
        val changedAt = clock.now()
        val affectedRows = chatDao.renameThread(
            threadId = roomId.raw,
            title = normalizeManualRoomTitle(title),
        )
        return ChatThreadMutationReceipt(
            threadId = roomId,
            mutation = ChatThreadMutation.RENAMED,
            changedAt = changedAt,
            affectedRows = affectedRows,
        )
    }

    override suspend fun archiveRoom(roomId: RoomId): ChatThreadMutationReceipt {
        val changedAt = clock.now()
        val affectedRows = chatDao.archiveThread(
            threadId = roomId.raw,
            archivedAtEpochMillis = changedAt.toEpochMilli(),
        )
        return ChatThreadMutationReceipt(
            threadId = roomId,
            mutation = ChatThreadMutation.ARCHIVED,
            changedAt = changedAt,
            affectedRows = affectedRows,
        )
    }

    override suspend fun deleteRoom(roomId: RoomId): ChatThreadMutationReceipt {
        val changedAt = clock.now()
        val affectedRows = chatDao.deleteThread(roomId.raw)
        return ChatThreadMutationReceipt(
            threadId = roomId,
            mutation = ChatThreadMutation.DELETED,
            changedAt = changedAt,
            affectedRows = affectedRows,
        )
    }

    override suspend fun failStaleStreamingAssistantMessages(
        reason: String,
        activeSmsAutoReplyAfter: Instant,
    ): Int =
        chatDao.failStreamingAssistantMessages(
            assistantRole = ConversationRole.ASSISTANT.name,
            streamingState = MessageDeliveryState.STREAMING.name,
            failedState = MessageDeliveryState.FAILED.name,
            completedAtEpochMillis = clock.now().toEpochMilli(),
            failureReason = reason,
            smsGeneratingState = SmsAutoReplyState.GENERATING.name,
            activeSmsAutoReplyAfterEpochMillis = activeSmsAutoReplyAfter.toEpochMilli(),
        )

    override suspend fun submitHumanMessage(command: SubmitHumanMessageCommand): HumanMessageReceipt {
        val submittedAt = clock.now()
        val messageId = idFactory.createChatMessageId()
        database.withTransaction {
            val room = checkNotNull(chatDao.findThread(command.threadId.raw)) {
                "Room ${command.threadId.raw} was not found."
            }
            check(room.archivedAtEpochMillis == null) { "Room ${command.threadId.raw} is archived." }
            val membership = checkNotNull(
                chatDao.findRoomMembership(command.threadId.raw, command.authorParticipantId.raw),
            ) {
                "Message author ${command.authorParticipantId.raw} is not a room member."
            }
            check(membership.leftAtEpochMillis == null) { "Message author is no longer an active room member." }
            check(membership.canPost) { "Message author does not have permission to post in this room." }
            val participant = checkNotNull(chatDao.findParticipant(command.authorParticipantId.raw)) {
                "Message author ${command.authorParticipantId.raw} was not found."
            }
            check(participant.kind == ParticipantKind.HUMAN.name) { "Only a human participant can submit a human message." }

            val submittedAtMillis = submittedAt.toEpochMilli()
            chatDao.insertMessage(
                ChatMessageEntity(
                    id = messageId.raw,
                    threadId = command.threadId.raw,
                    role = ConversationRole.USER.name,
                    body = command.body,
                    deliveryState = MessageDeliveryState.COMPLETE.name,
                    createdAtEpochMillis = submittedAtMillis,
                    completedAtEpochMillis = submittedAtMillis,
                    failureReason = null,
                    remoteId = null,
                    revision = 0,
                    syncState = SyncState.LOCAL_ONLY.name,
                ),
            )
            chatDao.insertMessageAuthor(
                ChatMessageAuthorEntity(
                    messageId = messageId.raw,
                    authorParticipantId = command.authorParticipantId.raw,
                ),
            )
            if (command.attachments.isNotEmpty()) {
                chatDao.upsertAttachments(
                    command.attachments.map { pendingAttachment ->
                        AttachmentEntity(
                            id = idFactory.createAttachmentId().raw,
                            messageId = messageId.raw,
                            displayName = pendingAttachment.displayName,
                            mimeType = pendingAttachment.mimeType,
                            uri = pendingAttachment.uri,
                            byteCount = pendingAttachment.byteCount,
                            kind = pendingAttachment.kind.name,
                            createdAtEpochMillis = submittedAtMillis,
                        )
                    },
                )
            }
            chatDao.updateThreadSummary(
                threadId = command.threadId.raw,
                title = buildRoomTitle(command.body),
                updatedAtEpochMillis = submittedAtMillis,
            )
        }
        return HumanMessageReceipt(
            roomId = command.threadId,
            messageId = messageId,
            authorParticipantId = command.authorParticipantId,
            submittedAt = submittedAt,
        )
    }

    override suspend fun startAiResponse(
        roomId: RoomId,
        inReplyToHumanMessageId: ChatMessageId?,
        authorParticipantId: ParticipantId,
    ): AiResponseStartReceipt {
        var startedAt = clock.now()
        val messageId = idFactory.createChatMessageId()
        database.withTransaction {
            val room = checkNotNull(chatDao.findThread(roomId.raw)) { "Room ${roomId.raw} was not found." }
            check(room.archivedAtEpochMillis == null) { "Room ${roomId.raw} is archived." }
            val membership = checkNotNull(chatDao.findRoomMembership(roomId.raw, authorParticipantId.raw)) {
                "AI participant ${authorParticipantId.raw} is not a room member."
            }
            check(membership.leftAtEpochMillis == null) { "AI participant is not an active room member." }
            check(membership.canPost) { "AI participant does not have permission to post in this room." }
            val participant = checkNotNull(chatDao.findParticipant(authorParticipantId.raw)) {
                "AI participant ${authorParticipantId.raw} was not found."
            }
            check(participant.kind == ParticipantKind.LOCAL_AI.name) {
                "Only the local Synapse AI can start phone-local inference."
            }
            if (inReplyToHumanMessageId != null) {
                val humanMessage = checkNotNull(chatDao.findMessageWithAuthor(inReplyToHumanMessageId.raw)) {
                    "Human message ${inReplyToHumanMessageId.raw} was not found."
                }
                check(humanMessage.message.threadId == roomId.raw) {
                    "Human message ${inReplyToHumanMessageId.raw} belongs to another room."
                }
                val humanAuthor = checkNotNull(humanMessage.author) {
                    "Human message ${inReplyToHumanMessageId.raw} has no durable author."
                }
                check(
                    humanMessage.message.role == ConversationRole.USER.name &&
                        humanAuthor.kind == ParticipantKind.HUMAN.name,
                ) {
                    "AI responses can only reply to a human-authored message."
                }
                val firstOrderedAiInstant = Instant
                    .ofEpochMilli(humanMessage.message.createdAtEpochMillis)
                    .plusMillis(1)
                if (startedAt.isBefore(firstOrderedAiInstant)) {
                    startedAt = firstOrderedAiInstant
                }
            }
            chatDao.insertMessage(
                ChatMessageEntity(
                    id = messageId.raw,
                    threadId = roomId.raw,
                    role = ConversationRole.ASSISTANT.name,
                    body = "",
                    deliveryState = MessageDeliveryState.STREAMING.name,
                    createdAtEpochMillis = startedAt.toEpochMilli(),
                    completedAtEpochMillis = null,
                    failureReason = null,
                    remoteId = null,
                    revision = 0,
                    syncState = SyncState.LOCAL_ONLY.name,
                ),
            )
            chatDao.insertMessageAuthor(
                ChatMessageAuthorEntity(
                    messageId = messageId.raw,
                    authorParticipantId = authorParticipantId.raw,
                ),
            )
        }
        return AiResponseStartReceipt(
            roomId = roomId,
            messageId = messageId,
            authorParticipantId = authorParticipantId,
            startedAt = startedAt,
        )
    }

    override suspend fun appendAssistantToken(messageId: ChatMessageId, token: String) {
        database.withTransaction {
            val currentMessage = requireStreamingLocalAiMessage(messageId)
            chatDao.updateMessageDelivery(
                messageId = messageId.raw,
                body = currentMessage.message.body + token,
                deliveryState = MessageDeliveryState.STREAMING.name,
                completedAtEpochMillis = null,
                failureReason = null,
            )
        }
    }

    override suspend fun completeAssistantMessage(messageId: ChatMessageId) {
        val currentMessage = requireStreamingLocalAiMessage(messageId)
        chatDao.updateMessageDelivery(
            messageId = messageId.raw,
            body = currentMessage.message.body,
            deliveryState = MessageDeliveryState.COMPLETE.name,
            completedAtEpochMillis = clock.now().toEpochMilli(),
            failureReason = null,
        )
    }

    override suspend fun failAssistantMessage(messageId: ChatMessageId, reason: String) {
        val currentMessage = checkNotNull(chatDao.findMessageWithAuthor(messageId.raw)) {
            "AI message ${messageId.raw} was not found."
        }
        val author = checkNotNull(currentMessage.author) { "AI message ${messageId.raw} has no durable author." }
        check(author.kind == ParticipantKind.LOCAL_AI.name) { "Message ${messageId.raw} is not authored by local AI." }
        chatDao.updateMessageDelivery(
            messageId = messageId.raw,
            body = currentMessage.message.body,
            deliveryState = MessageDeliveryState.FAILED.name,
            completedAtEpochMillis = clock.now().toEpochMilli(),
            failureReason = reason,
        )
    }

    private suspend fun requireRoom(roomId: RoomId): ChatRoomRecord =
        checkNotNull(findRoom(roomId)) { "Room ${roomId.raw} was not found." }

    private suspend fun requireStreamingLocalAiMessage(messageId: ChatMessageId) =
        checkNotNull(chatDao.findMessageWithAuthor(messageId.raw)) {
            "AI message ${messageId.raw} was not found."
        }.also { message ->
            val author = checkNotNull(message.author) { "AI message ${messageId.raw} has no durable author." }
            check(author.kind == ParticipantKind.LOCAL_AI.name) { "Message ${messageId.raw} is not authored by local AI." }
            check(message.message.deliveryState == MessageDeliveryState.STREAMING.name) {
                "AI message ${messageId.raw} is not streaming."
            }
        }

    private suspend fun ensureBuiltInParticipants(now: Instant = clock.now()) {
        insertParticipant(builtInParticipant(BuiltInParticipantIds.LOCAL_HUMAN, ParticipantKind.HUMAN, "You", now))
        insertParticipant(
            builtInParticipant(BuiltInParticipantIds.SYNAPSE_LOCAL_AI, ParticipantKind.LOCAL_AI, "Synapse", now),
        )
        insertParticipant(builtInParticipant(BuiltInParticipantIds.SYSTEM, ParticipantKind.SYSTEM, "System", now))
    }

    private suspend fun insertParticipant(participant: ChatParticipantEntity) {
        val inserted = chatDao.insertParticipantIfAbsent(participant)
        if (inserted == INSERT_IGNORED) {
            val existing = checkNotNull(chatDao.findParticipant(participant.id)) {
                "Participant ${participant.id} disappeared after a conflicting insert."
            }
            check(existing.kind == participant.kind) {
                "Participant ${participant.id} changed kind from ${existing.kind} to ${participant.kind}."
            }
        }
    }

    private suspend fun insertMembership(
        roomId: RoomId,
        participantId: ParticipantId,
        role: RoomMemberRole,
        canPost: Boolean,
        joinedAt: Instant,
        aiResponsePolicy: AiResponsePolicy,
    ) {
        val inserted = chatDao.insertRoomMembershipIfAbsent(
            RoomMembershipEntity(
                roomId = roomId.raw,
                participantId = participantId.raw,
                role = role.name,
                canPost = canPost,
                joinedAtEpochMillis = joinedAt.toEpochMilli(),
                leftAtEpochMillis = null,
                aiResponsePolicy = aiResponsePolicy.name,
                remoteId = null,
                revision = 0,
                syncState = SyncState.LOCAL_ONLY.name,
            ),
        )
        check(inserted != INSERT_IGNORED) {
            "Participant ${participantId.raw} is already a member of room ${roomId.raw}."
        }
    }

    private fun builtInParticipant(
        participantId: ParticipantId,
        kind: ParticipantKind,
        displayName: String,
        now: Instant,
    ): ChatParticipantEntity =
        ChatParticipantEntity(
            id = participantId.raw,
            kind = kind.name,
            displayName = displayName,
            avatarUri = null,
            avatarColorArgb = null,
            remoteId = null,
            revision = 0,
            syncState = SyncState.LOCAL_ONLY.name,
            createdAtEpochMillis = now.toEpochMilli(),
            updatedAtEpochMillis = now.toEpochMilli(),
        )

    private fun newHumanParticipant(
        participantId: ParticipantId,
        displayName: String,
        avatarUri: String?,
        avatarColorArgb: Long?,
        now: Instant,
    ): ChatParticipantEntity =
        ChatParticipantEntity(
            id = participantId.raw,
            kind = ParticipantKind.HUMAN.name,
            displayName = displayName,
            avatarUri = avatarUri,
            avatarColorArgb = avatarColorArgb,
            remoteId = null,
            revision = 0,
            syncState = SyncState.LOCAL_ONLY.name,
            createdAtEpochMillis = now.toEpochMilli(),
            updatedAtEpochMillis = now.toEpochMilli(),
        )

    private fun validateCreateRoomCommand(
        command: CreateRoomCommand,
        placeholderHumanNames: List<String>,
    ) {
        when (command.kind) {
            RoomKind.AI_CHAT -> {
                require(command.includeSynapseAi) { "An AI chat must include Synapse." }
                require(placeholderHumanNames.isEmpty()) { "An AI chat cannot include placeholder human members." }
            }

            RoomKind.DIRECT ->
                require(placeholderHumanNames.size == 1) {
                    "A direct room requires exactly one placeholder human member."
                }

            RoomKind.GROUP ->
                require(placeholderHumanNames.isNotEmpty()) {
                    "A group room requires at least one placeholder human member."
                }
        }
        require(command.includeSynapseAi || !command.synapseAiAutoResponseEnabled) {
            "AI auto-response cannot be enabled without Synapse in the room."
        }
    }

    private fun normalizePlaceholderHumanNames(displayNames: List<String>): List<String> =
        displayNames
            .map(::normalizeParticipantDisplayName)
            .distinctBy(String::lowercase)

    private fun normalizeParticipantDisplayName(displayName: String): String {
        val normalized = displayName
            .filterNot(Char::isISOControl)
            .replace(Regex("\\s+"), " ")
            .trim()
        require(normalized.isNotBlank()) { "Participant display name cannot be blank." }
        require(normalized.length <= PARTICIPANT_DISPLAY_NAME_LIMIT) {
            "Participant display name cannot exceed $PARTICIPANT_DISPLAY_NAME_LIMIT characters."
        }
        return normalized
    }

    private fun buildRoomTitle(body: String): String {
        val trimmedBody = body.trim()
        return when {
            trimmedBody.isBlank() -> "Synapse Chat"
            trimmedBody.length <= GENERATED_TITLE_LIMIT -> trimmedBody
            else -> trimmedBody.take(GENERATED_TITLE_LIMIT).trimEnd() + "..."
        }
    }

    private fun normalizeManualRoomTitle(title: String): String {
        val trimmedTitle = title.trim()
        require(trimmedTitle.isNotBlank()) { "Room title cannot be blank." }
        return when {
            trimmedTitle.length <= MANUAL_TITLE_LIMIT -> trimmedTitle
            else -> trimmedTitle.take(MANUAL_TITLE_LIMIT).trimEnd() + "..."
        }
    }

    private companion object {
        const val INSERT_IGNORED = -1L
        const val GENERATED_TITLE_LIMIT = 42
        const val MANUAL_TITLE_LIMIT = 72
        const val PARTICIPANT_DISPLAY_NAME_LIMIT = 64
    }
}
