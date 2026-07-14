package app.synapse.localllm.application

import app.synapse.localllm.domain.remote.RemoteRoomId
import app.synapse.localllm.domain.remote.isValidRemoteConversationRoomId
import java.util.concurrent.atomic.AtomicReference

class RemoteNotificationNavigationCoordinator {
    private val pendingRoom = AtomicReference<RemoteRoomId?>(null)

    fun queueRoom(rawRoomId: String?): Boolean {
        val roomId = parseRemoteNotificationRoomId(rawRoomId) ?: return false
        pendingRoom.set(roomId)
        return true
    }

    fun consumeRoom(): RemoteRoomId? = pendingRoom.getAndSet(null)
}

internal fun parseRemoteNotificationRoomId(rawRoomId: String?): RemoteRoomId? =
    rawRoomId
        ?.takeIf(::isValidRemoteConversationRoomId)
        ?.let(::RemoteRoomId)
