package app.synapse.localllm.domain.time

import java.time.Instant

interface SynapseClock {
    fun now(): Instant
}

class SystemSynapseClock : SynapseClock {
    override fun now(): Instant = Instant.now()
}
