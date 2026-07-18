package app.synapse.localllm.data.remote

import app.synapse.localllm.domain.remote.RemoteAccountSessionController
import app.synapse.localllm.domain.remote.RemoteAccountUid
import app.synapse.localllm.domain.remote.RemoteChatException
import app.synapse.localllm.domain.remote.RemoteDirectCallGateway
import app.synapse.localllm.domain.remote.RemoteDirectCallId
import app.synapse.localllm.domain.remote.RemoteDirectCallMediaKind
import app.synapse.localllm.domain.remote.RemoteDirectCallResponse
import app.synapse.localllm.domain.remote.RemoteDirectCallSession
import app.synapse.localllm.domain.remote.RemoteDirectCallSignal
import app.synapse.localllm.domain.remote.RemoteDirectCallSignalId
import app.synapse.localllm.domain.remote.RemoteDirectCallState
import app.synapse.localllm.domain.remote.RemoteRoomId
import app.synapse.localllm.domain.remote.isValidRemoteDirectCallId
import app.synapse.localllm.domain.remote.isValidRemoteDirectCallSignalId
import app.synapse.localllm.domain.remote.isValidRemoteDirectRoomId
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class FirebaseRemoteDirectCallGateway(
    private val firebaseAuth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val functions: FirebaseFunctions,
    private val sessionController: RemoteAccountSessionController,
) : RemoteDirectCallGateway {
    override fun observeActiveCallId(accountUid: RemoteAccountUid): Flow<RemoteDirectCallId?> =
        callbackFlow {
            val token = sessionController.requireActiveToken(accountUid)
            requireAuthenticatedUid(accountUid)
            val registration = firestore.collection(ACTIVE_CALL_POINTERS_COLLECTION)
                .document(accountUid.raw)
                .addSnapshotListener { snapshot, exception ->
                    if (exception != null) {
                        close(exception.toRemoteChatFailure("load the active call"))
                        return@addSnapshotListener
                    }
                    val callId = snapshot?.getString("callId")
                    if (callId == null) {
                        trySend(null)
                    } else if (isValidRemoteDirectCallId(callId)) {
                        trySend(RemoteDirectCallId(callId))
                    } else {
                        close(RemoteChatException("Firebase returned a malformed active call."))
                    }
                }
            val registrationJob = launch {
                runCatching { sessionController.registerListener(token, registration) }.onFailure(::close)
            }
            awaitClose {
                registrationJob.cancel()
                registration.remove()
            }
        }

    override fun observeCall(
        accountUid: RemoteAccountUid,
        callId: RemoteDirectCallId,
    ): Flow<RemoteDirectCallSession?> = callbackFlow {
        require(isValidRemoteDirectCallId(callId.raw)) { "Direct call identifier is invalid." }
        val token = sessionController.requireActiveToken(accountUid)
        requireAuthenticatedUid(accountUid)
        val registration = firestore.collection(CALL_SESSIONS_COLLECTION)
            .document(callId.raw)
            .addSnapshotListener { snapshot, exception ->
                if (exception != null) {
                    close(exception.toRemoteChatFailure("load the direct call"))
                    return@addSnapshotListener
                }
                if (snapshot == null || !snapshot.exists()) {
                    trySend(null)
                } else {
                    runCatching { snapshot.toDirectCallSession(callId) }
                        .onSuccess(::trySend)
                        .onFailure(::close)
                }
            }
        val registrationJob = launch {
            runCatching { sessionController.registerListener(token, registration) }.onFailure(::close)
        }
        awaitClose {
            registrationJob.cancel()
            registration.remove()
        }
    }

    override fun observeSignals(
        accountUid: RemoteAccountUid,
        callId: RemoteDirectCallId,
    ): Flow<List<RemoteDirectCallSignal>> = callbackFlow {
        require(isValidRemoteDirectCallId(callId.raw)) { "Direct call identifier is invalid." }
        val token = sessionController.requireActiveToken(accountUid)
        requireAuthenticatedUid(accountUid)
        val registration = firestore.collection(CALL_SESSIONS_COLLECTION)
            .document(callId.raw)
            .collection(CALL_SIGNALS_COLLECTION)
            .orderBy("createdAt", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, exception ->
                if (exception != null) {
                    close(exception.toRemoteChatFailure("load call signaling"))
                    return@addSnapshotListener
                }
                runCatching { snapshot?.documents.orEmpty().map(DocumentSnapshot::toDirectCallSignal) }
                    .onSuccess(::trySend)
                    .onFailure(::close)
            }
        val registrationJob = launch {
            runCatching { sessionController.registerListener(token, registration) }.onFailure(::close)
        }
        awaitClose {
            registrationJob.cancel()
            registration.remove()
        }
    }

    override suspend fun startCall(
        accountUid: RemoteAccountUid,
        roomId: RemoteRoomId,
        mediaKind: RemoteDirectCallMediaKind,
    ): RemoteDirectCallSession {
        requireAuthenticatedUid(accountUid)
        require(isValidRemoteDirectRoomId(roomId.raw)) { "Voice calls require a direct conversation." }
        return callForSession(
            "startDirectCall",
            mapOf("mediaKind" to mediaKind.name, "roomId" to roomId.raw),
            "start the call",
        )
    }

    override suspend fun respondToCall(
        accountUid: RemoteAccountUid,
        callId: RemoteDirectCallId,
        response: RemoteDirectCallResponse,
    ): RemoteDirectCallSession {
        requireAuthenticatedUid(accountUid)
        return callForSession(
            functionName = "respondDirectCall",
            payload = mapOf("action" to response.name, "callId" to callId.raw),
            operation = if (response == RemoteDirectCallResponse.ACCEPT) "answer the call" else "decline the call",
        )
    }

    override suspend fun endCall(
        accountUid: RemoteAccountUid,
        callId: RemoteDirectCallId,
    ): RemoteDirectCallSession {
        requireAuthenticatedUid(accountUid)
        return callForSession("endDirectCall", mapOf("callId" to callId.raw), "end the call")
    }

    override suspend fun publishSignal(
        accountUid: RemoteAccountUid,
        callId: RemoteDirectCallId,
        signal: RemoteDirectCallSignal,
    ) {
        requireAuthenticatedUid(accountUid)
        require(signal.senderUid == accountUid) { "Call signal sender must match the active account." }
        require(isValidRemoteDirectCallId(callId.raw)) { "Direct call identifier is invalid." }
        val payload = when (signal) {
            is RemoteDirectCallSignal.Offer -> mapOf(
                "callId" to callId.raw,
                "kind" to "OFFER",
                "sdp" to signal.sessionDescription,
                "signalId" to signal.signalId.raw,
            )
            is RemoteDirectCallSignal.Answer -> mapOf(
                "callId" to callId.raw,
                "kind" to "ANSWER",
                "sdp" to signal.sessionDescription,
                "signalId" to signal.signalId.raw,
            )
            is RemoteDirectCallSignal.IceCandidate -> mapOf(
                "callId" to callId.raw,
                "candidate" to signal.candidate,
                "kind" to "ICE",
                "sdpMid" to signal.mediaStreamIdentification,
                "sdpMLineIndex" to signal.mediaLineIndex,
                "signalId" to signal.signalId.raw,
            )
        }
        try {
            functions.getHttpsCallable("publishDirectCallSignal").call(payload).await()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            throw exception.toRemoteChatFailure("exchange call signaling")
        }
    }

    private suspend fun callForSession(
        functionName: String,
        payload: Map<String, Any?>,
        operation: String,
    ): RemoteDirectCallSession = try {
        val result = functions.getHttpsCallable(functionName).call(payload).await()
        result.data.toDirectCallSessionReceipt()
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: RemoteChatException) {
        throw exception
    } catch (exception: Exception) {
        throw exception.toRemoteChatFailure(operation)
    }

    private fun requireAuthenticatedUid(accountUid: RemoteAccountUid) {
        val currentUid = firebaseAuth.currentUser?.uid
        if (currentUid != accountUid.raw) {
            throw RemoteChatException("The Firebase account session changed. Sign in again.")
        }
    }

    private companion object {
        const val ACTIVE_CALL_POINTERS_COLLECTION = "activeCallPointers"
        const val CALL_SESSIONS_COLLECTION = "callSessions"
        const val CALL_SIGNALS_COLLECTION = "signals"
    }
}

internal fun Any?.toDirectCallSessionReceipt(): RemoteDirectCallSession {
    val value = this as? Map<*, *> ?: throw RemoteChatException("Firebase returned an invalid call receipt.")
    val callId = value.stringField("callId")
    val callerUid = value.stringField("callerUid")
    val calleeUid = value.stringField("calleeUid")
    val roomId = value.stringField("roomId")
    val mediaKind = value.optionalStringField("mediaKind", "AUDIO").toDirectCallMediaKind()
    val state = value.stringField("state").toDirectCallState()
    val expiresAtMillis = (value["expiresAtMillis"] as? Number)?.toLong()
        ?: throw RemoteChatException("Firebase returned an invalid call expiry.")
    if (!isValidRemoteDirectCallId(callId) || !isValidRemoteDirectRoomId(roomId) || expiresAtMillis < 0) {
        throw RemoteChatException("Firebase returned a malformed call receipt.")
    }
    return RemoteDirectCallSession(
        callId = RemoteDirectCallId(callId),
        callerUid = RemoteAccountUid(callerUid),
        calleeUid = RemoteAccountUid(calleeUid),
        roomId = RemoteRoomId(roomId),
        mediaKind = mediaKind,
        state = state,
        expiresAtMillis = expiresAtMillis,
    )
}

private fun DocumentSnapshot.toDirectCallSession(callId: RemoteDirectCallId): RemoteDirectCallSession {
    val callerUid = getString("callerUid") ?: malformedCall()
    val calleeUid = getString("calleeUid") ?: malformedCall()
    val roomId = getString("roomId") ?: malformedCall()
    val mediaKind = (getString("mediaKind") ?: "AUDIO").toDirectCallMediaKind()
    val state = getString("state")?.toDirectCallState() ?: malformedCall()
    val expiresAt = getTimestamp("expiresAt") ?: malformedCall()
    if (!isValidRemoteDirectRoomId(roomId)) malformedCall()
    return RemoteDirectCallSession(
        callId = callId,
        callerUid = RemoteAccountUid(callerUid),
        calleeUid = RemoteAccountUid(calleeUid),
        roomId = RemoteRoomId(roomId),
        mediaKind = mediaKind,
        state = state,
        expiresAtMillis = expiresAt.toDate().time,
    )
}

private fun DocumentSnapshot.toDirectCallSignal(): RemoteDirectCallSignal {
    if (!isValidRemoteDirectCallSignalId(id)) malformedSignal()
    val senderUid = getString("senderUid")?.let(::RemoteAccountUid) ?: malformedSignal()
    val signalId = RemoteDirectCallSignalId(id)
    return when (getString("kind")) {
        "OFFER" -> RemoteDirectCallSignal.Offer(
            signalId = signalId,
            senderUid = senderUid,
            sessionDescription = getString("sdp") ?: malformedSignal(),
        )
        "ANSWER" -> RemoteDirectCallSignal.Answer(
            signalId = signalId,
            senderUid = senderUid,
            sessionDescription = getString("sdp") ?: malformedSignal(),
        )
        "ICE" -> RemoteDirectCallSignal.IceCandidate(
            signalId = signalId,
            senderUid = senderUid,
            candidate = getString("candidate") ?: malformedSignal(),
            mediaStreamIdentification = getString("sdpMid"),
            mediaLineIndex = getLong("sdpMLineIndex")?.toInt() ?: malformedSignal(),
        )
        else -> malformedSignal()
    }
}

private fun String.toDirectCallState(): RemoteDirectCallState =
    runCatching { RemoteDirectCallState.valueOf(this) }
        .getOrElse { throw RemoteChatException("Firebase returned an invalid call state.") }

private fun String.toDirectCallMediaKind(): RemoteDirectCallMediaKind =
    runCatching { RemoteDirectCallMediaKind.valueOf(this) }
        .getOrElse { throw RemoteChatException("Firebase returned an invalid call media kind.") }

private fun Map<*, *>.stringField(name: String): String =
    this[name] as? String ?: throw RemoteChatException("Firebase returned an invalid $name.")

private fun Map<*, *>.optionalStringField(name: String, defaultValue: String): String =
    if (containsKey(name)) stringField(name) else defaultValue

private fun malformedCall(): Nothing = throw RemoteChatException("Firebase returned a malformed call record.")

private fun malformedSignal(): Nothing = throw RemoteChatException("Firebase returned malformed call signaling.")
