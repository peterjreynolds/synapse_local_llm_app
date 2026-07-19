package app.synapse.localllm.domain.remote

import kotlinx.coroutines.flow.Flow

@JvmInline
value class RemoteDirectCallId(val raw: String)

@JvmInline
value class RemoteDirectCallSignalId(val raw: String)

enum class RemoteDirectCallState {
    RINGING,
    ACTIVE,
    CANCELED,
    DECLINED,
    ENDED,
    MISSED,
}

enum class RemoteDirectCallResponse {
    ACCEPT,
    DECLINE,
}

enum class RemoteDirectCallRole {
    CALLER,
    CALLEE,
}

enum class RemoteDirectCallMediaKind {
    AUDIO,
    VIDEO,
}

data class RemoteDirectCallSession(
    val callId: RemoteDirectCallId,
    val callerUid: RemoteAccountUid,
    val calleeUid: RemoteAccountUid,
    val roomId: RemoteRoomId,
    val mediaKind: RemoteDirectCallMediaKind,
    val state: RemoteDirectCallState,
    val expiresAtMillis: Long,
)

sealed interface RemoteDirectCallSignal {
    val signalId: RemoteDirectCallSignalId
    val senderUid: RemoteAccountUid

    data class Offer(
        override val signalId: RemoteDirectCallSignalId,
        override val senderUid: RemoteAccountUid,
        val sessionDescription: String,
    ) : RemoteDirectCallSignal

    data class Answer(
        override val signalId: RemoteDirectCallSignalId,
        override val senderUid: RemoteAccountUid,
        val sessionDescription: String,
    ) : RemoteDirectCallSignal

    data class IceCandidate(
        override val signalId: RemoteDirectCallSignalId,
        override val senderUid: RemoteAccountUid,
        val candidate: String,
        val mediaStreamIdentification: String?,
        val mediaLineIndex: Int,
    ) : RemoteDirectCallSignal
}

interface RemoteDirectCallGateway {
    fun observeActiveCallId(accountUid: RemoteAccountUid): Flow<RemoteDirectCallId?>

    fun observeCall(
        accountUid: RemoteAccountUid,
        callId: RemoteDirectCallId,
    ): Flow<RemoteDirectCallSession?>

    fun observeSignals(
        accountUid: RemoteAccountUid,
        callId: RemoteDirectCallId,
    ): Flow<List<RemoteDirectCallSignal>>

    suspend fun startCall(
        accountUid: RemoteAccountUid,
        roomId: RemoteRoomId,
        mediaKind: RemoteDirectCallMediaKind,
    ): RemoteDirectCallSession

    suspend fun respondToCall(
        accountUid: RemoteAccountUid,
        callId: RemoteDirectCallId,
        response: RemoteDirectCallResponse,
    ): RemoteDirectCallSession

    suspend fun endCall(
        accountUid: RemoteAccountUid,
        callId: RemoteDirectCallId,
    ): RemoteDirectCallSession

    suspend fun expireCall(
        accountUid: RemoteAccountUid,
        callId: RemoteDirectCallId,
    ): RemoteDirectCallSession

    suspend fun publishSignal(
        accountUid: RemoteAccountUid,
        callId: RemoteDirectCallId,
        signal: RemoteDirectCallSignal,
    )
}

fun isValidRemoteDirectCallId(rawValue: String): Boolean =
    REMOTE_DIRECT_CALL_ID_PATTERN.matches(rawValue)

fun isValidRemoteDirectCallSignalId(rawValue: String): Boolean =
    REMOTE_DIRECT_CALL_SIGNAL_ID_PATTERN.matches(rawValue)

private val REMOTE_DIRECT_CALL_ID_PATTERN = Regex("^call_[a-f0-9]{32}$")
private val REMOTE_DIRECT_CALL_SIGNAL_ID_PATTERN = Regex("^signal_[a-f0-9]{32}$")
