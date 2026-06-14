package app.synapse.localllm.data.memory

import app.synapse.localllm.domain.memory.MemoryAdmissionGate
import app.synapse.localllm.domain.memory.MemoryClaimCandidate
import app.synapse.localllm.domain.memory.MemoryWriteDecision
import app.synapse.localllm.domain.memory.MemoryWriteOutcome
import app.synapse.localllm.domain.memory.SurfacePolicy
import app.synapse.localllm.domain.storage.StorageHealthSnapshot
import app.synapse.localllm.domain.storage.StorageHealthState

class EvidenceBackedMemoryAdmissionGate : MemoryAdmissionGate {
    override fun decideMemoryWrite(
        candidate: MemoryClaimCandidate,
        storageHealthSnapshot: StorageHealthSnapshot,
    ): MemoryWriteDecision {
        if (storageHealthSnapshot.state in writeBlockingStorageStates) {
            return MemoryWriteDecision(
                outcome = MemoryWriteOutcome.PAUSED_FOR_STORAGE,
                candidate = candidate,
                reason = storageHealthSnapshot.reason,
                storageHealthSnapshot = storageHealthSnapshot,
            )
        }

        if (candidate.sourceTraceEventIds.isEmpty()) {
            return MemoryWriteDecision(
                outcome = MemoryWriteOutcome.REJECTED,
                candidate = candidate,
                reason = "Memory candidate has no supporting trace event.",
                storageHealthSnapshot = storageHealthSnapshot,
            )
        }

        if (candidate.confidence < MINIMUM_DURABLE_CONFIDENCE) {
            return MemoryWriteDecision(
                outcome = MemoryWriteOutcome.REJECTED,
                candidate = candidate,
                reason = "Memory candidate confidence is below durable-write threshold.",
                storageHealthSnapshot = storageHealthSnapshot,
            )
        }

        if (candidate.surfacePolicy != SurfacePolicy.PROMPT_VISIBLE) {
            return MemoryWriteDecision(
                outcome = MemoryWriteOutcome.TRACE_ONLY,
                candidate = candidate,
                reason = "Candidate is not prompt-visible.",
                storageHealthSnapshot = storageHealthSnapshot,
            )
        }

        return MemoryWriteDecision(
            outcome = MemoryWriteOutcome.DURABLE_MEMORY_WRITTEN,
            candidate = candidate,
            reason = "Evidence-backed memory accepted.",
            storageHealthSnapshot = storageHealthSnapshot,
        )
    }

    private companion object {
        const val MINIMUM_DURABLE_CONFIDENCE = 0.62

        val writeBlockingStorageStates =
            setOf(
                StorageHealthState.PAUSED_WRITES,
                StorageHealthState.READ_ONLY_RECOVERY,
                StorageHealthState.CORRUPT_SUSPECTED,
            )
    }
}
