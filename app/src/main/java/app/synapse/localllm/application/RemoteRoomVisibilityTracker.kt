package app.synapse.localllm.application

import app.synapse.localllm.domain.remote.RemoteRoomId

class RemoteRoomVisibilityTracker {
    @Volatile
    private var isAppForegrounded = false

    @Volatile
    private var selectedRoomId: RemoteRoomId? = null

    fun setAppForegrounded(isForegrounded: Boolean) {
        isAppForegrounded = isForegrounded
    }

    fun setSelectedRoom(roomId: RemoteRoomId?) {
        selectedRoomId = roomId
    }

    fun shouldSuppressNotification(roomId: RemoteRoomId): Boolean =
        isAppForegrounded && selectedRoomId == roomId
}
