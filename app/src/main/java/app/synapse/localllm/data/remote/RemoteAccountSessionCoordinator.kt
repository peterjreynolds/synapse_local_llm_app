package app.synapse.localllm.data.remote

import app.synapse.localllm.domain.remote.RemoteAccountSessionController
import app.synapse.localllm.domain.remote.RemoteAccountSessionResource
import app.synapse.localllm.domain.remote.RemoteAccountSessionToken
import app.synapse.localllm.domain.remote.RemoteAccountUid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RemoteAccountSessionCoordinator : RemoteAccountSessionController {
    private val sessionMutex = Mutex()
    private val mutableActiveSession = MutableStateFlow<RemoteAccountSessionToken?>(null)
    private val registeredResources = linkedSetOf<RemoteAccountSessionResource>()
    private var nextGeneration = 1L

    override val activeSession: StateFlow<RemoteAccountSessionToken?> = mutableActiveSession.asStateFlow()

    override suspend fun beginSession(accountUid: RemoteAccountUid): RemoteAccountSessionToken =
        sessionMutex.withLock {
            clearSessionLocked()
            RemoteAccountSessionToken(
                accountUid = accountUid,
                generation = nextGeneration++,
            ).also { token ->
                mutableActiveSession.value = token
            }
        }

    override suspend fun registerResource(
        token: RemoteAccountSessionToken,
        resource: RemoteAccountSessionResource,
    ) {
        sessionMutex.withLock {
            if (token != mutableActiveSession.value) {
                resource.cancel()
                error("Remote account session ${token.generation} is no longer active.")
            }
            registeredResources += resource
        }
    }

    override suspend fun endSession() {
        sessionMutex.withLock {
            clearSessionLocked()
        }
    }

    private fun clearSessionLocked() {
        mutableActiveSession.value = null
        val cancellationFailures = buildList {
            registeredResources.forEach { resource ->
                runCatching(resource::cancel).exceptionOrNull()?.let(::add)
            }
        }
        registeredResources.clear()
        check(cancellationFailures.isEmpty()) {
            "Failed to cancel ${cancellationFailures.size} remote session resource(s)."
        }
    }
}
