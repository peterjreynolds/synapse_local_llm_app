package app.synapse.localllm.data.remote

import app.synapse.localllm.domain.remote.OpenRemoteDirectRoomCommand
import app.synapse.localllm.domain.remote.OpenRemoteDirectRoomReceipt
import app.synapse.localllm.domain.remote.RemoteAccountSessionController
import app.synapse.localllm.domain.remote.RemoteAccountUid
import app.synapse.localllm.domain.remote.RemoteCachedDirectRoom
import app.synapse.localllm.domain.remote.RemoteCachedMembership
import app.synapse.localllm.domain.remote.RemoteCachedMessage
import app.synapse.localllm.domain.remote.RemoteChatException
import app.synapse.localllm.domain.remote.RemoteConversationGateway
import app.synapse.localllm.domain.remote.RemoteDirectRoomSnapshot
import app.synapse.localllm.domain.remote.RemoteIdempotencyKey
import app.synapse.localllm.domain.remote.RemoteMessageDeliveryState
import app.synapse.localllm.domain.remote.RemoteMessageId
import app.synapse.localllm.domain.remote.RemoteMessageSendReceipt
import app.synapse.localllm.domain.remote.RemoteProfileUid
import app.synapse.localllm.domain.remote.RemoteRoomId
import app.synapse.localllm.domain.remote.SendRemoteMessageCommand
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.channels.awaitClose
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
    override fun observeDirectRooms(accountUid: RemoteAccountUid): Flow<List<RemoteDirectRoomSnapshot>> =
        callbackFlow {
            val token = sessionController.requireActiveToken(accountUid)
            requireAuthenticatedUid(accountUid)
            val listenerLock = Any()
            var roomDocuments = emptyList<DocumentSnapshot>()
            val membershipDocuments = mutableMapOf<String, DocumentSnapshot>()
            val membershipRegistrations = mutableMapOf<String, ListenerRegistration>()

            fun emitRoomSnapshots() {
                val rooms = synchronized(listenerLock) {
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
                .whereArrayContains("memberIds", accountUid.raw)
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
        require(normalizedBody.isNotEmpty() && normalizedBody.length <= MESSAGE_BODY_LIMIT) {
            "Message must contain 1-$MESSAGE_BODY_LIMIT characters."
        }
        val messageReference = firestore.collection(ROOMS_COLLECTION)
            .document(message.roomId.raw)
            .collection(MESSAGES_COLLECTION)
            .document(message.messageId.raw)
        val payload = mapOf(
            "authorKind" to HUMAN_AUTHOR_KIND,
            "body" to normalizedBody,
            "clientCreatedAt" to Timestamp(
                message.clientCreatedAt.epochSecond,
                message.clientCreatedAt.nano,
            ),
            "clientMessageId" to message.messageId.raw,
            "createdAt" to FieldValue.serverTimestamp(),
            "deletedAt" to null,
            "editedAt" to null,
            "replyToMessageId" to null,
            "senderUid" to message.senderUid.raw,
        )
        try {
            messageReference.set(payload).await()
        } catch (exception: Exception) {
            val existingMessage = runCatching { messageReference.get().await() }.getOrNull()
            if (!existingMessage.matchesIdempotentMessage(message)) {
                throw exception.toRemoteChatFailure("send the message")
            }
        }
        return RemoteMessageSendReceipt(message.accountUid, message.roomId, message.messageId)
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
    ): RemoteDirectRoomSnapshot? {
        if (getString("kind") != DIRECT_ROOM_KIND) return null
        val memberIds = (get("memberIds") as? List<*>)
            ?.filterIsInstance<String>()
            ?.distinct()
            ?: return null
        val peerUid = memberIds.singleOrNull { uid -> uid != accountUid.raw } ?: return null
        val directKey = getString("directKey") ?: return null
        val title = getString("title") ?: return null
        val updatedAt = getTimestamp("updatedAt") ?: return null
        if (!membershipDocument.exists() || membershipDocument.getBoolean("active") != true) return null
        val joinedAt = membershipDocument.getTimestamp("joinedAt") ?: return null
        val latestMessage = get("latestMessage") as? Map<*, *>
        val latestBody = latestMessage?.get("body") as? String
        val latestSenderUid = latestMessage?.get("senderUid") as? String
        val unreadCount = membershipDocument.getLong("unreadCount")
            ?.coerceIn(0L, Int.MAX_VALUE.toLong())
            ?.toInt()
            ?: 0
        return RemoteDirectRoomSnapshot(
            room = RemoteCachedDirectRoom(
                accountUid = accountUid,
                roomId = RemoteRoomId(id),
                directKey = directKey,
                peerUid = RemoteProfileUid(peerUid),
                title = title,
                unreadCount = unreadCount,
                latestMessagePreview = latestBody,
                latestMessageSenderUid = latestSenderUid?.let(::RemoteProfileUid),
                remoteUpdatedAt = updatedAt.toInstant(),
            ),
            currentMembership = RemoteCachedMembership(
                accountUid = accountUid,
                roomId = RemoteRoomId(id),
                memberUid = RemoteProfileUid(accountUid.raw),
                role = membershipDocument.getString("role") ?: MEMBER_ROLE,
                isActive = true,
                joinedAt = joinedAt.toInstant(),
                lastReadAt = membershipDocument.getTimestamp("lastReadAt")?.toInstant(),
            ),
        )
    }

    private fun QuerySnapshot?.toMessages(
        accountUid: RemoteAccountUid,
        roomId: RemoteRoomId,
    ): List<RemoteCachedMessage> =
        this?.documents.orEmpty().mapNotNull { document ->
            val body = document.getString("body") ?: return@mapNotNull null
            val senderUid = document.getString("senderUid") ?: return@mapNotNull null
            val authorKind = document.getString("authorKind") ?: return@mapNotNull null
            val clientMessageId = document.getString("clientMessageId") ?: return@mapNotNull null
            val clientCreatedAt = document.getTimestamp("clientCreatedAt") ?: return@mapNotNull null
            if (
                body.isBlank() ||
                body.length > MESSAGE_BODY_LIMIT ||
                clientMessageId != document.id ||
                authorKind !in allowedAuthorKinds
            ) {
                return@mapNotNull null
            }
            val serverCreatedAt = document.getTimestamp("createdAt")?.toInstant()
            RemoteCachedMessage(
                accountUid = accountUid,
                roomId = roomId,
                messageId = RemoteMessageId(document.id),
                idempotencyKey = RemoteIdempotencyKey(clientMessageId),
                senderUid = RemoteProfileUid(senderUid),
                authorKind = authorKind,
                body = body,
                deliveryState = if (document.metadata.hasPendingWrites() || serverCreatedAt == null) {
                    RemoteMessageDeliveryState.PENDING
                } else {
                    RemoteMessageDeliveryState.SENT
                },
                clientCreatedAt = clientCreatedAt.toInstant(),
                serverCreatedAt = serverCreatedAt,
                failureReason = null,
            )
        }.reversed()

    private fun DocumentSnapshot?.matchesIdempotentMessage(message: RemoteCachedMessage): Boolean {
        val snapshot = this ?: return false
        return snapshot.exists() &&
            snapshot.getString("clientMessageId") == message.messageId.raw &&
            snapshot.getString("senderUid") == message.senderUid.raw &&
            snapshot.getString("authorKind") == message.authorKind &&
            snapshot.getString("body") == message.body.trim()
    }

    private fun requireAuthenticatedUid(accountUid: RemoteAccountUid) {
        sessionController.requireActiveToken(accountUid)
        if (firebaseAuth.currentUser?.uid != accountUid.raw) {
            throw RemoteChatException("The Firebase account session changed. Sign in again.")
        }
    }

    private companion object {
        const val DIRECT_ROOM_KIND = "DIRECT"
        const val HUMAN_AUTHOR_KIND = "HUMAN"
        const val MEMBER_ROLE = "MEMBER"
        const val MEMBERS_COLLECTION = "members"
        const val MESSAGES_COLLECTION = "messages"
        const val MESSAGE_BODY_LIMIT = 4_000
        const val MESSAGE_PAGE_LIMIT = 100L
        const val ROOMS_COLLECTION = "rooms"
        val allowedAuthorKinds = setOf(HUMAN_AUTHOR_KIND, "SYNAPSE_AI")
    }
}
