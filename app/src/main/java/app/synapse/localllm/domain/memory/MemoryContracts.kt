package app.synapse.localllm.domain.memory

import app.synapse.localllm.domain.chat.ChatMessageRecord
import app.synapse.localllm.domain.chat.ConversationRole
import app.synapse.localllm.domain.ids.ChatMessageId
import app.synapse.localllm.domain.ids.MemoryObjectId
import app.synapse.localllm.domain.ids.MemoryVersionId
import app.synapse.localllm.domain.ids.ReceiptId
import app.synapse.localllm.domain.ids.TraceEventId
import app.synapse.localllm.domain.runtime.ModelChatMessage
import app.synapse.localllm.domain.storage.StorageHealthSnapshot
import java.time.Instant

enum class MemoryKind {
    TRACE,
    GIST,
    IDENTITY,
    PREFERENCE,
    COMMITMENT,
    PROCEDURE,
    RELATIONSHIP,
    PROJECT,
    APPOINTMENT,
    INSTRUCTION,
    CORRECTION,
    SUMMARY,
    ARCHIVE,
}

enum class MemoryScope {
    GLOBAL,
    PROJECT,
    THREAD,
}

enum class MemoryClaimDomain {
    IDENTITY,
    PREFERENCE,
    RELATIONSHIP,
    PROJECT,
    TASK,
    APPOINTMENT,
    ROUTINE,
    INSTRUCTION,
    CORRECTION,
    SUMMARY,
    ALIAS,
    CONSTRAINT,
    WORKSPACE,
    GIST,
    TRACE,
    ARCHIVE;

    companion object {
        fun fromKind(kind: MemoryKind): MemoryClaimDomain =
            when (kind) {
                MemoryKind.TRACE -> TRACE
                MemoryKind.GIST -> GIST
                MemoryKind.IDENTITY -> IDENTITY
                MemoryKind.PREFERENCE -> PREFERENCE
                MemoryKind.COMMITMENT -> TASK
                MemoryKind.PROCEDURE -> INSTRUCTION
                MemoryKind.RELATIONSHIP -> RELATIONSHIP
                MemoryKind.PROJECT -> PROJECT
                MemoryKind.APPOINTMENT -> APPOINTMENT
                MemoryKind.INSTRUCTION -> INSTRUCTION
                MemoryKind.CORRECTION -> CORRECTION
                MemoryKind.SUMMARY -> SUMMARY
                MemoryKind.ARCHIVE -> ARCHIVE
            }
    }
}

enum class MemoryWriteIntent {
    EXPLICIT_SAVE,
    EXPLICIT_CORRECTION,
    IMPLICIT_CANDIDATE,
    SUMMARY,
    IMPORTED,
}

enum class MemorySensitivity {
    LOW,
    MEDIUM,
    HIGH,
}

enum class MemoryStatus {
    ACTIVE,
    ARCHIVED,
    SUPERSEDED,
    CONFLICTED,
    QUARANTINED,
    TOMBSTONED,
}

enum class SurfacePolicy {
    PROMPT_VISIBLE,
    USER_REVIEW_ONLY,
    INTERNAL_ONLY,
}

enum class MemoryWriteOutcome {
    TRACE_ONLY,
    DURABLE_MEMORY_WRITTEN,
    MEMORY_UPDATED,
    MEMORY_SUPERSEDED,
    MEMORY_TOMBSTONED,
    MEMORY_ACTIVATED,
    REQUIRES_CONFIRMATION,
    QUARANTINED,
    REJECTED,
    PAUSED_FOR_STORAGE,
}

enum class MemoryReviewFilter {
    ACTIVE,
    REVIEW_NEEDED,
    INACTIVE,
    ALL,
}

data class TraceEventRecord(
    val id: TraceEventId,
    val sourceMessageId: ChatMessageId,
    val role: ConversationRole,
    val text: String,
    val observedAt: Instant,
)

data class MemoryClaimCandidate(
    val kind: MemoryKind,
    val text: String,
    val confidence: Double,
    val sourceTraceEventIds: List<TraceEventId>,
    val surfacePolicy: SurfacePolicy,
    val reasonCodes: List<String>,
    val scope: MemoryScope = MemoryScope.GLOBAL,
    val domain: MemoryClaimDomain = MemoryClaimDomain.fromKind(kind),
    val subject: String? = null,
    val predicate: String? = null,
    val value: String? = null,
    val sourceQuote: String? = null,
    val writeIntent: MemoryWriteIntent = MemoryWriteIntent.EXPLICIT_SAVE,
    val durabilityScore: Double = 1.0,
    val futureUsefulnessScore: Double = 1.0,
    val sensitivity: MemorySensitivity = MemorySensitivity.LOW,
    val keywords: List<String> = emptyList(),
    val claimKey: String? = null,
)

data class MemoryWriteDecision(
    val outcome: MemoryWriteOutcome,
    val candidate: MemoryClaimCandidate?,
    val reason: String,
    val storageHealthSnapshot: StorageHealthSnapshot?,
)

data class MemoryWriteReceipt(
    val id: ReceiptId,
    val outcome: MemoryWriteOutcome,
    val traceEventId: TraceEventId?,
    val memoryObjectId: MemoryObjectId?,
    val memoryVersionId: MemoryVersionId?,
    val decidedAt: Instant,
    val reason: String,
)

data class MemoryObjectRecord(
    val id: MemoryObjectId,
    val kind: MemoryKind,
    val status: MemoryStatus,
    val claimKey: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class MemoryVersionRecord(
    val id: MemoryVersionId,
    val memoryObjectId: MemoryObjectId,
    val text: String,
    val confidence: Double,
    val surfacePolicy: SurfacePolicy,
    val sourceTraceEventIds: List<TraceEventId>,
    val createdAt: Instant,
    val scope: MemoryScope = MemoryScope.GLOBAL,
    val domain: MemoryClaimDomain = MemoryClaimDomain.GIST,
    val subject: String? = null,
    val predicate: String? = null,
    val value: String? = null,
    val sourceQuote: String? = null,
    val writeIntent: MemoryWriteIntent = MemoryWriteIntent.EXPLICIT_SAVE,
    val durabilityScore: Double = 1.0,
    val futureUsefulnessScore: Double = 1.0,
    val sensitivity: MemorySensitivity = MemorySensitivity.LOW,
    val keywords: List<String> = emptyList(),
)

data class RetrievedMemoryRef(
    val memoryObjectId: MemoryObjectId,
    val memoryVersionId: MemoryVersionId,
    val kind: MemoryKind,
    val status: MemoryStatus = MemoryStatus.ACTIVE,
    val text: String,
    val confidence: Double,
    val reasonCodes: List<String>,
    val scope: MemoryScope = MemoryScope.GLOBAL,
    val domain: MemoryClaimDomain = MemoryClaimDomain.fromKind(kind),
    val subject: String? = null,
    val predicate: String? = null,
    val value: String? = null,
    val sourceQuote: String? = null,
    val writeIntent: MemoryWriteIntent = MemoryWriteIntent.EXPLICIT_SAVE,
    val durabilityScore: Double = 1.0,
    val futureUsefulnessScore: Double = 1.0,
    val sensitivity: MemorySensitivity = MemorySensitivity.LOW,
    val keywords: List<String> = emptyList(),
    val claimKey: String? = null,
    val sourceTraceEventIds: List<TraceEventId> = emptyList(),
    val createdAt: Instant? = null,
    val rankScore: Double = 0.0,
)

sealed interface MemoryCommand {
    data object ContinueExtraction : MemoryCommand

    data class TombstoneMatchingMemories(
        val query: String,
        val reason: String,
    ) : MemoryCommand
}

interface MemoryCommandInterpreter {
    fun interpretMemoryCommand(traceEvent: TraceEventRecord): MemoryCommand
}

data class RetrievalBundle(
    val retrievedAt: Instant,
    val refs: List<RetrievedMemoryRef>,
    val promptBlock: String,
)

interface MemoryProjector {
    fun extractMemoryCandidates(traceEvent: TraceEventRecord): List<MemoryClaimCandidate>
}

data class MemoryCandidateParseReceipt(
    val candidates: List<MemoryClaimCandidate>,
    val rejectionReason: String?,
)

interface MemoryCandidateProposalParser {
    fun parseCandidateProposal(
        rawJson: String,
        traceEvent: TraceEventRecord,
    ): MemoryCandidateParseReceipt
}

interface MemoryCandidateNormalizer {
    fun normalizeMemoryCandidate(
        candidate: MemoryClaimCandidate,
        traceEvent: TraceEventRecord,
    ): MemoryClaimCandidate
}

interface MemoryCandidateProposer {
    fun proposeMemoryCandidates(traceEvent: TraceEventRecord): List<MemoryClaimCandidate>
}

data class MemoryImplicitScore(
    val score: Double,
    val outcome: MemoryWriteOutcome,
    val reason: String,
)

interface MemoryImplicitScorer {
    fun scoreImplicitMemoryCandidate(candidate: MemoryClaimCandidate): MemoryImplicitScore
}

interface MemoryAdmissionGate {
    fun decideMemoryWrite(
        candidate: MemoryClaimCandidate,
        storageHealthSnapshot: StorageHealthSnapshot,
    ): MemoryWriteDecision
}

interface MemoryRepository {
    suspend fun appendTraceEvent(traceEvent: TraceEventRecord): TraceEventId

    suspend fun persistMemoryDecision(
        traceEvent: TraceEventRecord,
        decision: MemoryWriteDecision,
    ): MemoryWriteReceipt

    suspend fun tombstoneMemory(memoryObjectId: MemoryObjectId, reason: String): MemoryWriteReceipt

    suspend fun activateMemory(memoryObjectId: MemoryObjectId, reason: String): MemoryWriteReceipt

    suspend fun tombstoneMemoriesMatching(
        traceEvent: TraceEventRecord,
        query: String,
        reason: String,
    ): List<MemoryWriteReceipt>

    suspend fun listPromptVisibleMemories(limit: Int): List<RetrievedMemoryRef>

    suspend fun listMemoriesForReview(filter: MemoryReviewFilter, limit: Int): List<RetrievedMemoryRef>

    suspend fun retrieveMemories(query: String, limit: Int): RetrievalBundle
}

interface PromptContextAssembler {
    suspend fun assemblePromptMessages(
        userMessage: String,
        priorMessages: List<ChatMessageRecord>,
        retrievalBundle: RetrievalBundle,
        systemPrompt: String,
    ): List<ModelChatMessage>
}
