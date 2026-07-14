package app.synapse.localllm.data.remote

import app.synapse.localllm.domain.remote.AcknowledgeRemoteMessagesCommand
import app.synapse.localllm.domain.remote.LoadRemoteMessagesPageCommand
import app.synapse.localllm.domain.remote.OpenRemoteDirectRoomCommand
import app.synapse.localllm.domain.remote.OpenRemoteDirectRoomReceipt
import app.synapse.localllm.domain.remote.RemoteAccountSessionController
import app.synapse.localllm.domain.remote.RemoteAccountUid
import app.synapse.localllm.domain.remote.RemoteAttachmentId
import app.synapse.localllm.domain.remote.RemoteAttachmentKind
import app.synapse.localllm.domain.remote.RemoteCachedAttachment
import app.synapse.localllm.domain.remote.RemoteCachedMessage
import app.synapse.localllm.domain.remote.RemoteCachedRoom
import app.synapse.localllm.domain.remote.RemoteChatException
import app.synapse.localllm.domain.remote.RemoteConversationGateway
import app.synapse.localllm.domain.remote.RemoteIdempotencyKey
import app.synapse.localllm.domain.remote.RemoteMessageAcknowledgementReceipt
import app.synapse.localllm.domain.remote.RemoteMessageDeliveryState
import app.synapse.localllm.domain.remote.RemoteMessageId
import app.synapse.localllm.domain.remote.RemoteMessagePage
import app.synapse.localllm.domain.remote.RemoteMessageRevisionReceipt
import app.synapse.localllm.domain.remote.RemoteMessageSendReceipt
import app.synapse.localllm.domain.remote.RemoteProfileUid
import app.synapse.localllm.domain.remote.RemoteReactionReceipt
import app.synapse.localllm.domain.remote.RemoteRoomId
import app.synapse.localllm.domain.remote.RemoteRoomKind
import app.synapse.localllm.domain.remote.RemoteRoomMemberRole
import app.synapse.localllm.domain.remote.RemoteTypingParticipant
import app.synapse.localllm.domain.remote.ReviseRemoteMessageCommand
import app.synapse.localllm.domain.remote.SendRemoteMessageCommand
import app.synapse.localllm.domain.remote.ToggleRemoteReactionCommand
import app.synapse.localllm.domain.remote.isValidRemoteDirectRoomId
import app.synapse.localllm.domain.remote.isValidRemoteGroupRoomId
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class FirebaseRemoteConversationGateway(
    private val firebaseAuth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val functions: FirebaseFunctions,
    private val sessionController: RemoteAccountSessionController,
) : RemoteConversationGateway {
    override fun observeRooms(accountUid: RemoteAccountUid): Flow<List<RemoteCachedRoom>> =
        callbackFlow {
            val token = sessionController.requireActiveToken(accountUid)
            requireAuthenticatedUid(accountUid)
            val listenerLock = Any()
            var roomDocuments = emptyList<DocumentSnapshot>()
            val membershipDocuments = mutableMapOf<String, DocumentSnapshot>()
            val membershipRegistrations = mutableMapOf<String, ListenerRegistration>()

            fun emitRoomSnapshots() {
                val rooms = synchronized(listenerLock) {
                    if (roomDocuments.any { roomDocument -> roomDocument.id !in membershipDocuments }) {
                        return
                    }
                    roomDocuments.mapNotNull { roomDocument ->
                        membershipDocuments[roomDocument.id]?.let { membershipDocument ->
                            roomDocument.toRoomSnapshot(accountUid, membershipDocument)
                        }
                    }
                }
                trySend(rooms)
            }

            fun addMembershipListener(roomDocument: DocumentSnapshot) {
                val roomId = roomDocument.id
                val membershipRegistration = roomDocument.reference.collection(MEMBERS_COLLECTION)
                    .document(accountUid.raw)
                    .addSnapshotListener { membershipDocument, exception ->
                        if (exception != null) {
                            close(exception.toRemoteChatFailure("load remote room membership"))
                            return@addSnapshotListener
                        }
                        synchronized(listenerLock) {
                            if (membershipDocument == null) {
                                membershipDocuments.remove(roomId)
                            } else {
                                membershipDocuments[roomId] = membershipDocument
                            }
                        }
                        emitRoomSnapshots()
                    }
                synchronized(listenerLock) {
                    membershipRegistrations[roomId] = membershipRegistration
                }
                launch {
                    runCatching { sessionController.registerListener(token, membershipRegistration) }
                        .onFailure(::close)
                }
            }

            val registration = firestore.collection(ROOMS_COLLECTION)
                .whereArrayContains("activeMemberIds", accountUid.raw)
                .orderBy("updatedAt", Query.Direction.DESCENDING)
                .addSnapshotListener { snapshot, exception ->
                    if (exception != null) {
                        close(exception.toRemoteChatFailure("load remote conversations"))
                        return@addSnapshotListener
                    }
                    val updatedRoomDocuments = snapshot?.documents.orEmpty()
                    val updatedRoomIds = updatedRoomDocuments.mapTo(mutableSetOf()) { roomDocument ->
                        roomDocument.id
                    }
                    val removedRegistrations = synchronized(listenerLock) {
                        val removedRoomIds = membershipRegistrations.keys - updatedRoomIds
                        val registrations = removedRoomIds.mapNotNull(membershipRegistrations::remove)
                        removedRoomIds.forEach(membershipDocuments::remove)
                        roomDocuments = updatedRoomDocuments
                        registrations
                    }
                    removedRegistrations.forEach(ListenerRegistration::remove)
                    val roomsNeedingMembershipListeners = synchronized(listenerLock) {
                        updatedRoomDocuments.filter { roomDocument ->
                            roomDocument.id !in membershipRegistrations
                        }
                    }
                    roomsNeedingMembershipListeners.forEach(::addMembershipListener)
                    emitRoomSnapshots()
                }
            val registrationJob = launch {
                runCatching { sessionController.registerListener(token, registration) }
                    .onFailure(::close)
            }
            awaitClose {
                registrationJob.cancel()
                registration.remove()
                synchronized(listenerLock) {
                    membershipRegistrations.values.toList().also {
                        membershipRegistrations.clear()
                        membershipDocuments.clear()
                        roomDocuments = emptyList()
                    }
                }.forEach(ListenerRegistration::remove)
            }
        }

    override fun observeMessages(
        accountUid: RemoteAccountUid,
        roomId: RemoteRoomId,
    ): Flow<List<RemoteCachedMessage>> =
        callbackFlow {
            val token = sessionController.requireActiveToken(accountUid)
            requireAuthenticatedUid(accountUid)
            val registration = firestore.collection(ROOMS_COLLECTION)
                .document(roomId.raw)
                .collection(MESSAGES_COLLECTION)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .orderBy(FieldPath.documentId(), Query.Direction.DESCENDING)
                .limit(MESSAGE_PAGE_LIMIT)
                .addSnapshotListener { snapshot, exception ->
                    if (exception != null) {
                        close(exception.toRemoteChatFailure("load remote messages"))
                        return@addSnapshotListener
                    }
                    trySend(snapshot.toMessages(accountUid, roomId))
                }
            val registrationJob = launch {
                runCatching { sessionController.registerListener(token, registration) }
                    .onFailure(::close)
            }
            awaitClose {
                registrationJob.cancel()
                registration.remove()
            }
        }

    override suspend fun openDirectRoom(
        command: OpenRemoteDirectRoomCommand,
    ): OpenRemoteDirectRoomReceipt {
        requireAuthenticatedUid(command.accountUid)
        try {
            val result = functions.getHttpsCallable("openDirectRoom")
                .call(mapOf("targetUid" to command.targetUid.raw))
                .await()
            val resultMap = result.data as? Map<*, *>
                ?: throw RemoteChatException("Firebase returned an invalid room receipt.")
            val roomId = resultMap["roomId"] as? String
                ?: throw RemoteChatException("Firebase returned an invalid room identifier.")
            if (!isValidRemoteDirectRoomId(roomId)) {
                throw RemoteChatException("Firebase returned an invalid direct room identifier.")
            }
            return OpenRemoteDirectRoomReceipt(command.accountUid, RemoteRoomId(roomId))
        } catch (exception: RemoteChatException) {
            throw exception
        } catch (exception: Exception) {
            throw exception.toRemoteChatFailure("open the direct conversation")
        }
    }

    override suspend fun sendMessage(command: SendRemoteMessageCommand): RemoteMessageSendReceipt {
        val message = command.message
        requireAuthenticatedUid(message.accountUid)
        require(message.senderUid.raw == message.accountUid.raw) { "Remote sender must match the active account." }
        require(message.authorKind == HUMAN_AUTHOR_KIND) { "Only human messages can be sent from this device." }
        require(message.messageId.raw == message.idempotencyKey.raw) {
            "Remote message ID and idempotency key must match."
        }
        val normalizedBody = message.body.trim()
        require(
            normalizedBody.length <= MESSAGE_BODY_LIMIT &&
                (normalizedBody.isNotEmpty() || message.attachments.isNotEmpty()),
        ) {
            "Message must contain text or a ready attachment."
        }
        val payload = mapOf(
            "attachmentIds" to message.attachments.map { attachment -> attachment.attachmentId.raw },
            "body" to normalizedBody,
            "clientCreatedAtMillis" to message.clientCreatedAt.toEpochMilli(),
            "messageId" to message.messageId.raw,
            "replyToMessageId" to message.replyToMessageId?.raw,
            "roomId" to message.roomId.raw,
        )
        try {
            functions.getHttpsCallable("sendRemoteMessage").call(payload).await()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            throw exception.toRemoteChatFailure("send the message")
        }
        return RemoteMessageSendReceipt(message.accountUid, message.roomId, message.messageId)
    }

    override suspend fun editMessage(command: ReviseRemoteMessageCommand): RemoteMessageRevisionReceipt {
        val body = command.body?.trim()
        require(!body.isNullOrEmpty() && body.length <= MESSAGE_BODY_LIMIT) {
            "Message must contain 1-$MESSAGE_BODY_LIMIT characters."
        }
        return reviseMessage("editRemoteMessage", "edit the message", command, body)
    }

    override suspend fun deleteMessage(command: ReviseRemoteMessageCommand): RemoteMessageRevisionReceipt =
        reviseMessage("deleteRemoteMessage", "delete the message", command, null)

    override suspend fun toggleReaction(command: ToggleRemoteReactionCommand): RemoteReactionReceipt {
        requireAuthenticatedUid(command.accountUid)
        require(command.emoji.isNotBlank() && command.emoji.length <= MAXIMUM_EMOJI_LENGTH) {
            "Choose a valid reaction."
        }
        try {
            val result = functions.getHttpsCallable("toggleRemoteReaction").call(
                mapOf(
                    "emoji" to command.emoji,
                    "messageId" to command.messageId.raw,
                    "reacted" to command.reacted,
                    "roomId" to command.roomId.raw,
                ),
            ).await().data.requireCallableMap("reaction")
            return RemoteReactionReceipt(
                roomId = RemoteRoomId(result.requireString("roomId")),
                messageId = RemoteMessageId(result.requireString("messageId")),
                emoji = result.requireString("emoji"),
                reacted = result["reacted"] as? Boolean
                    ?: throw RemoteChatException("Firebase returned an invalid reaction receipt."),
                reactionCount = result.requireNonNegativeInt("reactionCount"),
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: RemoteChatException) {
            throw exception
        } catch (exception: Exception) {
            throw exception.toRemoteChatFailure("update the reaction")
        }
    }

    override suspend fun acknowledgeMessages(
        command: AcknowledgeRemoteMessagesCommand,
    ): RemoteMessageAcknowledgementReceipt {
        requireAuthenticatedUid(command.accountUid)
        require(command.messageIds.isNotEmpty() && command.messageIds.size <= MAXIMUM_ACKNOWLEDGEMENT_SIZE) {
            "A message acknowledgement must contain 1-$MAXIMUM_ACKNOWLEDGEMENT_SIZE messages."
        }
        try {
            val result = functions.getHttpsCallable("acknowledgeRemoteMessages").call(
                mapOf(
                    "messageIds" to command.messageIds.map { messageId -> messageId.raw },
                    "read" to command.read,
                    "roomId" to command.roomId.raw,
                ),
            ).await().data.requireCallableMap("message acknowledgement")
            return RemoteMessageAcknowledgementReceipt(
                roomId = RemoteRoomId(result.requireString("roomId")),
                acknowledgedCount = result.requireNonNegativeInt("acknowledgedCount"),
                read = result["read"] as? Boolean
                    ?: throw RemoteChatException("Firebase returned an invalid acknowledgement receipt."),
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: RemoteChatException) {
            throw exception
        } catch (exception: Exception) {
            throw exception.toRemoteChatFailure("acknowledge the messages")
        }
    }

    override suspend fun loadMessagesBefore(command: LoadRemoteMessagesPageCommand): RemoteMessagePage {
        requireAuthenticatedUid(command.accountUid)
        require(command.limit in 1..MAXIMUM_MESSAGE_PAGE_SIZE) { "Message page size is invalid." }
        try {
            val snapshot = firestore.collection(ROOMS_COLLECTION)
                .document(command.roomId.raw)
                .collection(MESSAGES_COLLECTION)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .orderBy(FieldPath.documentId(), Query.Direction.DESCENDING)
                .startAfter(
                    Timestamp(command.beforeCreatedAt.epochSecond, command.beforeCreatedAt.nano),
                    command.beforeMessageId.raw,
                )
                .limit(command.limit.toLong())
                .get()
                .await()
            val messages = snapshot.documents.mapNotNull { document ->
                document.toRemoteCachedMessage(command.accountUid, command.roomId)
            }.reversed()
            return RemoteMessagePage(messages, snapshot.size() < command.limit)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            throw exception.toRemoteChatFailure("load earlier messages")
        }
    }

    override suspend fun loadMessage(
        accountUid: RemoteAccountUid,
        roomId: RemoteRoomId,
        messageId: RemoteMessageId,
    ): RemoteCachedMessage? {
        requireAuthenticatedUid(accountUid)
        try {
            return firestore.collection(ROOMS_COLLECTION)
                .document(roomId.raw)
                .collection(MESSAGES_COLLECTION)
                .document(messageId.raw)
                .get()
                .await()
                .takeIf(DocumentSnapshot::exists)
                ?.toRemoteCachedMessage(accountUid, roomId)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            throw exception.toRemoteChatFailure("load the replied message")
        }
    }

    override fun observeTypingParticipants(
        accountUid: RemoteAccountUid,
        roomId: RemoteRoomId,
    ): Flow<List<RemoteTypingParticipant>> = callbackFlow {
        val token = sessionController.requireActiveToken(accountUid)
        requireAuthenticatedUid(accountUid)
        val lock = Any()
        var documents = emptyList<DocumentSnapshot>()
        fun emitActiveParticipants() {
            val now = Timestamp.now().toInstant()
            val participants = synchronized(lock) { documents }.mapNotNull { document ->
                val uid = document.getString("uid") ?: return@mapNotNull null
                val expiresAt = document.getTimestamp("expiresAt")?.toInstant() ?: return@mapNotNull null
                if (uid == accountUid.raw || !expiresAt.isAfter(now)) null else {
                    RemoteTypingParticipant(RemoteProfileUid(uid), expiresAt)
                }
            }
            trySend(participants)
        }
        val registration = firestore.collection(ROOMS_COLLECTION)
            .document(roomId.raw)
            .collection(TYPING_COLLECTION)
            .addSnapshotListener { snapshot, exception ->
                if (exception != null) {
                    close(exception.toRemoteChatFailure("load typing activity"))
                    return@addSnapshotListener
                }
                synchronized(lock) { documents = snapshot?.documents.orEmpty() }
                emitActiveParticipants()
            }
        val registrationJob = launch {
            runCatching { sessionController.registerListener(token, registration) }.onFailure(::close)
        }
        val expiryJob = launch {
            while (true) {
                delay(TYPING_REFRESH_MILLIS)
                emitActiveParticipants()
            }
        }
        awaitClose {
            registrationJob.cancel()
            expiryJob.cancel()
            registration.remove()
        }
    }

    override suspend fun setTyping(
        accountUid: RemoteAccountUid,
        roomId: RemoteRoomId,
        isTyping: Boolean,
    ) {
        requireAuthenticatedUid(accountUid)
        val reference = firestore.collection(ROOMS_COLLECTION)
            .document(roomId.raw)
            .collection(TYPING_COLLECTION)
            .document(accountUid.raw)
        try {
            if (isTyping) {
                val now = Timestamp.now()
                reference.set(
                    mapOf(
                        "expiresAt" to Timestamp(now.seconds + TYPING_EXPIRY_SECONDS, now.nanoseconds),
                        "uid" to accountUid.raw,
                        "updatedAt" to FieldValue.serverTimestamp(),
                    ),
                ).await()
            } else {
                reference.delete().await()
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            throw exception.toRemoteChatFailure("update typing activity")
        }
    }

    override suspend fun markRoomRead(
        accountUid: RemoteAccountUid,
        roomId: RemoteRoomId,
    ) {
        requireAuthenticatedUid(accountUid)
        try {
            functions.getHttpsCallable("markRoomRead")
                .call(mapOf("roomId" to roomId.raw))
                .await()
        } catch (exception: Exception) {
            throw exception.toRemoteChatFailure("mark the conversation read")
        }
    }

    private fun DocumentSnapshot.toRoomSnapshot(
        accountUid: RemoteAccountUid,
        membershipDocument: DocumentSnapshot,
    ): RemoteCachedRoom? {
        val roomKind = getString("kind")
            ?.let { rawKind -> runCatching { RemoteRoomKind.valueOf(rawKind) }.getOrNull() }
            ?: return null
        if (
            (roomKind == RemoteRoomKind.DIRECT && !isValidRemoteDirectRoomId(id)) ||
            (roomKind == RemoteRoomKind.GROUP && !isValidRemoteGroupRoomId(id))
        ) {
            return null
        }
        val directIdentity = when (roomKind) {
            RemoteRoomKind.DIRECT -> {
                val memberIds = (get("memberIds") as? List<*>)
                    ?.filterIsInstance<String>()
                    ?.distinct()
                    ?: return null
                val peerUid = memberIds.singleOrNull { uid -> uid != accountUid.raw } ?: return null
                (getString("directKey") ?: return null) to RemoteProfileUid(peerUid)
            }

            RemoteRoomKind.GROUP -> null to null
        }
        val title = getString("title")?.takeIf(String::isNotBlank) ?: return null
        val updatedAt = getTimestamp("updatedAt") ?: return null
        if (!membershipDocument.exists() || membershipDocument.getBoolean("active") != true) return null
        val joinedAt = membershipDocument.getTimestamp("joinedAt") ?: return null
        val memberRole = membershipDocument.getString("role")
            ?.let { rawRole -> runCatching { RemoteRoomMemberRole.valueOf(rawRole) }.getOrNull() }
            ?: return null
        if (roomKind == RemoteRoomKind.DIRECT && memberRole != RemoteRoomMemberRole.MEMBER) return null
        val latestMessage = get("latestMessage") as? Map<*, *>
        val latestBody = latestMessage?.get("body") as? String
        val latestSenderUid = latestMessage?.get("senderUid") as? String
        val unreadCount = membershipDocument.getLong("unreadCount")
            ?.coerceIn(0L, Int.MAX_VALUE.toLong())
            ?.toInt()
            ?: 0
        val avatarObjectPathValue = get("avatarObjectPath")
        if (roomKind == RemoteRoomKind.GROUP && avatarObjectPathValue != null && avatarObjectPathValue !is String) {
            return null
        }
        val isMuted = membershipDocument.getBoolean("muted") ?: false
        return RemoteCachedRoom(
            accountUid = accountUid,
            roomId = RemoteRoomId(id),
            kind = roomKind,
            directKey = directIdentity.first,
            peerUid = directIdentity.second,
            title = title,
            avatarObjectPath = if (roomKind == RemoteRoomKind.GROUP) {
                avatarObjectPathValue as? String
            } else {
                null
            },
            unreadCount = unreadCount,
            latestMessagePreview = latestBody,
            latestMessageSenderUid = latestSenderUid?.let(::RemoteProfileUid),
            currentMemberRole = memberRole,
            notificationsEnabled = !isMuted,
            isMuted = isMuted,
            isArchived = membershipDocument.getBoolean("archived") ?: false,
            isPinned = membershipDocument.getBoolean("pinned") ?: false,
            joinedAt = joinedAt.toInstant(),
            lastReadAt = membershipDocument.getTimestamp("lastReadAt")?.toInstant(),
            remoteUpdatedAt = updatedAt.toInstant(),
        )
    }

    private fun QuerySnapshot?.toMessages(
        accountUid: RemoteAccountUid,
        roomId: RemoteRoomId,
    ): List<RemoteCachedMessage> =
        this?.documents.orEmpty().mapNotNull { document ->
            document.toRemoteCachedMessage(accountUid, roomId)
        }.reversed()

    private fun DocumentSnapshot.toRemoteCachedMessage(
        accountUid: RemoteAccountUid,
        roomId: RemoteRoomId,
    ): RemoteCachedMessage? {
        val body = getString("body") ?: return null
        val senderUid = getString("senderUid") ?: return null
        val authorKind = getString("authorKind") ?: return null
        val clientMessageId = getString("clientMessageId") ?: return null
        val clientCreatedAt = getTimestamp("clientCreatedAt") ?: return null
        val deletedAt = getTimestamp("deletedAt")?.toInstant()
        val replyToMessageId = getString("replyToMessageId")?.let(::RemoteMessageId)
        val revision = getLong("revision") ?: 1L
        val deliveredToCount = getLong("deliveredToCount")?.toSafeCountOrNull() ?: run {
            if (contains("deliveredToCount")) return null else 0
        }
        val readByCount = getLong("readByCount")?.toSafeCountOrNull() ?: run {
            if (contains("readByCount")) return null else 0
        }
        val reactionCounts = readReactionCounts(get("reactionCounts")) ?: return null
        val attachments = readAttachments(get("attachments"), roomId, RemoteMessageId(id)) ?: return null
        if (
            (deletedAt == null && body.isBlank() && attachments.isEmpty()) ||
            body.length > MESSAGE_BODY_LIMIT ||
            clientMessageId != id ||
            authorKind !in allowedAuthorKinds ||
            revision < 1L
        ) return null
        val serverCreatedAt = getTimestamp("createdAt")?.toInstant()
        val deliveryState = when {
            metadata.hasPendingWrites() || serverCreatedAt == null -> RemoteMessageDeliveryState.PENDING
            senderUid != accountUid.raw -> RemoteMessageDeliveryState.SENT
            readByCount > 0 -> RemoteMessageDeliveryState.READ
            deliveredToCount > 0 -> RemoteMessageDeliveryState.DELIVERED
            else -> RemoteMessageDeliveryState.SENT
        }
        return RemoteCachedMessage(
            accountUid = accountUid,
            roomId = roomId,
            messageId = RemoteMessageId(id),
            idempotencyKey = RemoteIdempotencyKey(clientMessageId),
            senderUid = RemoteProfileUid(senderUid),
            authorKind = authorKind,
            body = body,
            attachments = attachments,
            replyToMessageId = replyToMessageId,
            editedAt = getTimestamp("editedAt")?.toInstant(),
            deletedAt = deletedAt,
            revision = revision,
            reactionCounts = reactionCounts,
            deliveredToCount = deliveredToCount,
            readByCount = readByCount,
            deliveryState = deliveryState,
            clientCreatedAt = clientCreatedAt.toInstant(),
            serverCreatedAt = serverCreatedAt,
            failureReason = null,
        )
    }

    private suspend fun reviseMessage(
        callableName: String,
        operation: String,
        command: ReviseRemoteMessageCommand,
        body: String?,
    ): RemoteMessageRevisionReceipt {
        requireAuthenticatedUid(command.accountUid)
        require(command.mutationId.isNotBlank() && command.expectedRevision >= 1L) {
            "Message revision command is invalid."
        }
        val payload = buildMap<String, Any> {
            put("expectedRevision", command.expectedRevision)
            put("messageId", command.messageId.raw)
            put("mutationId", command.mutationId)
            put("roomId", command.roomId.raw)
            if (body != null) put("body", body)
        }
        try {
            val result = functions.getHttpsCallable(callableName).call(payload).await().data
                .requireCallableMap("message revision")
            return RemoteMessageRevisionReceipt(
                roomId = RemoteRoomId(result.requireString("roomId")),
                messageId = RemoteMessageId(result.requireString("messageId")),
                revision = result.requirePositiveLong("revision"),
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: RemoteChatException) {
            throw exception
        } catch (exception: Exception) {
            throw exception.toRemoteChatFailure(operation)
        }
    }

    private fun requireAuthenticatedUid(accountUid: RemoteAccountUid) {
        sessionController.requireActiveToken(accountUid)
        if (firebaseAuth.currentUser?.uid != accountUid.raw) {
            throw RemoteChatException("The Firebase account session changed. Sign in again.")
        }
    }

    private companion object {
        const val HUMAN_AUTHOR_KIND = "HUMAN"
        const val MEMBERS_COLLECTION = "members"
        const val MAXIMUM_ACKNOWLEDGEMENT_SIZE = 50
        const val MAXIMUM_EMOJI_LENGTH = 16
        const val MAXIMUM_MESSAGE_PAGE_SIZE = 100
        const val MESSAGES_COLLECTION = "messages"
        const val MESSAGE_BODY_LIMIT = 4_000
        const val MESSAGE_PAGE_LIMIT = 100L
        const val ROOMS_COLLECTION = "rooms"
        const val TYPING_COLLECTION = "typing"
        const val TYPING_EXPIRY_SECONDS = 10L
        const val TYPING_REFRESH_MILLIS = 1_000L
        val allowedAuthorKinds = setOf(HUMAN_AUTHOR_KIND, "SYNAPSE_AI")
    }
}

private fun Any?.requireCallableMap(receiptName: String): Map<*, *> =
    this as? Map<*, *> ?: throw RemoteChatException("Firebase returned an invalid $receiptName receipt.")

private fun Map<*, *>.requireString(fieldName: String): String =
    (this[fieldName] as? String)?.takeIf(String::isNotBlank)
        ?: throw RemoteChatException("Firebase returned an invalid $fieldName receipt field.")

private fun Map<*, *>.requirePositiveLong(fieldName: String): Long =
    (this[fieldName] as? Number)?.toLong()?.takeIf { value -> value >= 1L }
        ?: throw RemoteChatException("Firebase returned an invalid $fieldName receipt field.")

private fun Map<*, *>.requireNonNegativeInt(fieldName: String): Int =
    (this[fieldName] as? Number)?.toLong()
        ?.takeIf { value -> value in 0..Int.MAX_VALUE.toLong() }
        ?.toInt()
        ?: throw RemoteChatException("Firebase returned an invalid $fieldName receipt field.")

private fun Long.toSafeCountOrNull(): Int? =
    takeIf { value -> value in 0..Int.MAX_VALUE.toLong() }?.toInt()

private fun readReactionCounts(value: Any?): Map<String, Int>? {
    if (value == null) return emptyMap()
    val rawCounts = value as? Map<*, *> ?: return null
    if (rawCounts.size > 32) return null
    return buildMap {
        rawCounts.forEach { (rawEmoji, rawCount) ->
            val emoji = rawEmoji as? String ?: return null
            val count = (rawCount as? Number)?.toLong()?.toSafeCountOrNull()?.takeIf { it > 0 } ?: return null
            if (emoji.isBlank() || emoji.length > 16) return null
            put(emoji, count)
        }
    }
}

private fun readAttachments(
    value: Any?,
    roomId: RemoteRoomId,
    messageId: RemoteMessageId,
): List<RemoteCachedAttachment>? {
    if (value == null) return emptyList()
    val rawAttachments = value as? List<*> ?: return null
    if (rawAttachments.size > 8) return null
    val attachments = rawAttachments.mapNotNull { rawAttachment ->
        val attachment = rawAttachment as? Map<*, *> ?: return null
        val attachmentId = (attachment["attachmentId"] as? String)?.let { rawId ->
            runCatching { RemoteAttachmentId(rawId) }.getOrNull()
        } ?: return null
        val byteCount = (attachment["byteCount"] as? Number)?.toLong()?.takeIf { count -> count > 0L }
            ?: return null
        val displayName = (attachment["displayName"] as? String)?.takeIf(String::isNotBlank) ?: return null
        val mimeType = (attachment["mimeType"] as? String)?.takeIf(String::isNotBlank) ?: return null
        val kind = (attachment["kind"] as? String)?.let { rawKind ->
            runCatching { RemoteAttachmentKind.valueOf(rawKind) }.getOrNull()
        } ?: return null
        val durationMillis = (attachment["durationMillis"] as? Number)?.toLong()
        val prefix = "roomAttachments/${roomId.raw}/${messageId.raw}/${attachmentId.raw}"
        val contentObjectPath = attachment["contentObjectPath"] as? String ?: return null
        val thumbnailObjectPath = attachment["thumbnailObjectPath"] as? String
        if (
            contentObjectPath != "$prefix/content" ||
            (kind == RemoteAttachmentKind.IMAGE && thumbnailObjectPath != "$prefix/thumbnail") ||
            (kind != RemoteAttachmentKind.IMAGE && thumbnailObjectPath != null)
        ) return null
        RemoteCachedAttachment(
            attachmentId = attachmentId,
            displayName = displayName,
            mimeType = mimeType,
            byteCount = byteCount,
            kind = kind,
            durationMillis = durationMillis,
            contentObjectPath = contentObjectPath,
            thumbnailObjectPath = thumbnailObjectPath,
        )
    }
    return attachments.takeIf { parsed ->
        parsed.size == rawAttachments.size &&
            parsed.distinctBy(RemoteCachedAttachment::attachmentId).size == parsed.size
    }
}
