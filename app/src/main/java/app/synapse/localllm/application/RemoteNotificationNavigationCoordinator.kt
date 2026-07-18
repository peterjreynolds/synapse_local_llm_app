package app.synapse.localllm.application

import app.synapse.localllm.domain.remote.RemoteRoomId
import app.synapse.localllm.domain.remote.RemoteDirectCallId
import app.synapse.localllm.domain.remote.isValidRemoteDirectCallId
import app.synapse.localllm.domain.remote.isValidRemoteConversationRoomId
import java.util.concurrent.atomic.AtomicReference

class RemoteNotificationNavigationCoordinator {
    private val pendingRoom = AtomicReference<RemoteRoomId?>(null)
    private val pendingCall = AtomicReference<RemoteDirectCallId?>(null)

    fun queueRoom(rawRoomId: String?): Boolean {
        val roomId = parseRemoteNotificationRoomId(rawRoomId) ?: return false
        pendingRoom.set(roomId)
        return true
    }

    fun consumeRoom(): RemoteRoomId? = pendingRoom.getAndSet(null)

    fun queueCall(rawCallId: String?): Boolean {
        val callId = parseRemoteNotificationCallId(rawCallId) ?: return false
        pendingCall.set(callId)
        return true
    }

    fun consumeCall(): RemoteDirectCallId? = pendingCall.getAndSet(null)
}

internal fun parseRemoteNotificationRoomId(rawRoomId: String?): RemoteRoomId? =
    rawRoomId
        ?.takeIf(::isValidRemoteConversationRoomId)
        ?.let(::RemoteRoomId)

internal fun parseRemoteNotificationCallId(rawCallId: String?): RemoteDirectCallId? =
    rawCallId
        ?.takeIf(::isValidRemoteDirectCallId)
        ?.let(::RemoteDirectCallId)
