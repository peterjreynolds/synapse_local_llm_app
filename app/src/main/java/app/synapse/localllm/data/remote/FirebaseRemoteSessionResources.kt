package app.synapse.localllm.data.remote

import app.synapse.localllm.domain.remote.RemoteAccountSessionController
import app.synapse.localllm.domain.remote.RemoteAccountSessionResource
import app.synapse.localllm.domain.remote.RemoteAccountSessionToken
import app.synapse.localllm.domain.remote.RemoteAccountUid
import app.synapse.localllm.domain.remote.RemoteChatException
import com.google.firebase.firestore.ListenerRegistration

internal fun RemoteAccountSessionController.requireActiveToken(
    accountUid: RemoteAccountUid,
): RemoteAccountSessionToken {
    val token = activeSession.value
        ?: throw RemoteChatException("No remote account session is active.")
    if (token.accountUid != accountUid) {
        throw RemoteChatException("The remote account session changed. Try again.")
    }
    return token
}

internal suspend fun RemoteAccountSessionController.registerListener(
    token: RemoteAccountSessionToken,
    registration: ListenerRegistration,
) {
    registerResource(token, RemoteAccountSessionResource(registration::remove))
}
