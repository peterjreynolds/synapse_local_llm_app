package app.synapse.localllm.data.memory

import app.synapse.localllm.domain.ids.TraceEventId
import app.synapse.localllm.domain.memory.MemoryClaimCandidate
import app.synapse.localllm.domain.memory.MemoryKind
import app.synapse.localllm.domain.memory.MemorySensitivity
import app.synapse.localllm.domain.memory.MemoryWriteIntent
import app.synapse.localllm.domain.memory.MemoryWriteOutcome
import app.synapse.localllm.domain.memory.SurfacePolicy
import app.synapse.localllm.domain.storage.StorageHealthSnapshot
import app.synapse.localllm.domain.storage.StorageHealthState
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class EvidenceBackedMemoryAdmissionGateTest {
    private val admissionGate = EvidenceBackedMemoryAdmissionGate()

    @Test
    fun pausesDurableWritesWhenStorageIsUnsafe() {
        val decision = admissionGate.decideMemoryWrite(
            candidate = supportedCandidate(),
            storageHealthSnapshot = storageSnapshot(StorageHealthState.PAUSED_WRITES),
        )

        assertEquals(MemoryWriteOutcome.PAUSED_FOR_STORAGE, decision.outcome)
    }

    @Test
    fun rejectsCandidateWithoutEvidence() {
        val decision = admissionGate.decideMemoryWrite(
            candidate = supportedCandidate().copy(sourceTraceEventIds = emptyList()),
            storageHealthSnapshot = storageSnapshot(StorageHealthState.HEALTHY),
        )

        assertEquals(MemoryWriteOutcome.REJECTED, decision.outcome)
    }

    @Test
    fun acceptsPromptVisibleEvidenceBackedCandidate() {
        val decision = admissionGate.decideMemoryWrite(
            candidate = supportedCandidate(),
            storageHealthSnapshot = storageSnapshot(StorageHealthState.HEALTHY),
        )

        assertEquals(MemoryWriteOutcome.DURABLE_MEMORY_WRITTEN, decision.outcome)
    }

    @Test
    fun rejectsCandidateWithoutSourceQuote() {
        val decision = admissionGate.decideMemoryWrite(
            candidate = supportedCandidate().copy(sourceQuote = null),
            storageHealthSnapshot = storageSnapshot(StorageHealthState.HEALTHY),
        )

        assertEquals(MemoryWriteOutcome.REJECTED, decision.outcome)
    }

    @Test
    fun quarantinesMediumScoreImplicitCandidate() {
        val decision = admissionGate.decideMemoryWrite(
            candidate = supportedCandidate().copy(
                writeIntent = MemoryWriteIntent.IMPLICIT_CANDIDATE,
                confidence = 0.72,
                durabilityScore = 0.68,
                futureUsefulnessScore = 0.68,
            ),
            storageHealthSnapshot = storageSnapshot(StorageHealthState.HEALTHY),
        )

        assertEquals(MemoryWriteOutcome.QUARANTINED, decision.outcome)
    }

    @Test
    fun requiresConfirmationForHighSensitivityCandidate() {
        val decision = admissionGate.decideMemoryWrite(
            candidate = supportedCandidate().copy(sensitivity = MemorySensitivity.HIGH),
            storageHealthSnapshot = storageSnapshot(StorageHealthState.HEALTHY),
        )

        assertEquals(MemoryWriteOutcome.REQUIRES_CONFIRMATION, decision.outcome)
    }

    private fun supportedCandidate(): MemoryClaimCandidate =
        MemoryClaimCandidate(
            kind = MemoryKind.PREFERENCE,
            text = "User prefers concise Kotlin code.",
            confidence = 0.86,
            sourceTraceEventIds = listOf(TraceEventId("trace-1")),
            surfacePolicy = SurfacePolicy.PROMPT_VISIBLE,
            reasonCodes = listOf("explicit-user-preference"),
            sourceQuote = "I prefer concise Kotlin code.",
        )

    private fun storageSnapshot(state: StorageHealthState): StorageHealthSnapshot =
        StorageHealthSnapshot(
            state = state,
            checkedAt = Instant.parse("2026-06-14T16:00:00Z"),
            availableBytes = if (state == StorageHealthState.PAUSED_WRITES) 100L else 4_000_000_000L,
            memoryDatabaseBytes = 1024L,
            attachmentCacheBytes = 1024L,
            reason = state.name,
        )
}
