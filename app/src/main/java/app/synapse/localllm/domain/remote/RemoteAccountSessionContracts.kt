package app.synapse.localllm.domain.remote

import kotlinx.coroutines.flow.StateFlow

data class RemoteAccountSessionToken(
    val accountUid: RemoteAccountUid,
    val generation: Long,
)

fun interface RemoteAccountSessionResource {
    fun cancel()
}

interface RemoteAccountSessionController {
    val activeSession: StateFlow<RemoteAccountSessionToken?>

    suspend fun beginSession(accountUid: RemoteAccountUid): RemoteAccountSessionToken

    suspend fun registerResource(
        token: RemoteAccountSessionToken,
        resource: RemoteAccountSessionResource,
    )

    suspend fun endSession()
}
