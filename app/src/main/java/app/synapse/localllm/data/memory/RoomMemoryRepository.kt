package app.synapse.localllm.data.memory

import androidx.room.withTransaction
import app.synapse.localllm.data.db.MemoryDao
import app.synapse.localllm.data.db.MemoryObjectEntity
import app.synapse.localllm.data.db.MemorySupportEntity
import app.synapse.localllm.data.db.MemoryVersionEntity
import app.synapse.localllm.data.db.MemoryVersionWithKind
import app.synapse.localllm.data.db.MemoryWriteReceiptEntity
import app.synapse.localllm.data.db.RetrievalReceiptEntity
import app.synapse.localllm.data.db.RetrievedMemoryReceiptEntity
import app.synapse.localllm.data.db.SynapseDatabase
import app.synapse.localllm.data.db.TraceEventEntity
import app.synapse.localllm.domain.ids.MemoryObjectId
import app.synapse.localllm.domain.ids.MemoryVersionId
import app.synapse.localllm.domain.ids.SynapseIdFactory
import app.synapse.localllm.domain.ids.TraceEventId
import app.synapse.localllm.domain.memory.MemoryKind
import app.synapse.localllm.domain.memory.MemoryRepository
import app.synapse.localllm.domain.memory.MemoryStatus
import app.synapse.localllm.domain.memory.MemoryVersionRecord
import app.synapse.localllm.domain.memory.MemoryWriteDecision
import app.synapse.localllm.domain.memory.MemoryWriteOutcome
import app.synapse.localllm.domain.memory.MemoryWriteReceipt
import app.synapse.localllm.domain.memory.RetrievalBundle
import app.synapse.localllm.domain.memory.RetrievedMemoryRef
import app.synapse.localllm.domain.memory.SurfacePolicy
import app.synapse.localllm.domain.memory.TraceEventRecord
import app.synapse.localllm.domain.time.SynapseClock

class RoomMemoryRepository(
    private val database: SynapseDatabase,
    private val memoryDao: MemoryDao,
    private val idFactory: SynapseIdFactory,
    private val clock: SynapseClock,
) : MemoryRepository {
    override suspend fun appendTraceEvent(traceEvent: TraceEventRecord): TraceEventId {
        memoryDao.insertTraceEvent(traceEvent.toEntity())
        return traceEvent.id
    }

    override suspend fun persistMemoryDecision(
        traceEvent: TraceEventRecord,
        decision: MemoryWriteDecision,
    ): MemoryWriteReceipt {
        val decidedAt = clock.now()
        val receiptId = idFactory.createReceiptId()
        val acceptedCandidate = decision.candidate
            ?.takeIf { decision.outcome == MemoryWriteOutcome.DURABLE_MEMORY_WRITTEN }
        val memoryObjectId = acceptedCandidate?.let { idFactory.createMemoryObjectId() }
        val memoryVersionId = acceptedCandidate?.let { idFactory.createMemoryVersionId() }

        database.withTransaction {
            if (acceptedCandidate != null && memoryObjectId != null && memoryVersionId != null) {
                val memoryObject = MemoryObjectEntity(
                    id = memoryObjectId.raw,
                    kind = acceptedCandidate.kind.name,
                    status = MemoryStatus.ACTIVE.name,
                    createdAtEpochMillis = decidedAt.toEpochMilli(),
                    updatedAtEpochMillis = decidedAt.toEpochMilli(),
                )
                memoryDao.upsertMemoryObject(memoryObject)
                memoryDao.upsertMemoryVersion(
                    MemoryVersionEntity(
                        id = memoryVersionId.raw,
                        memoryObjectId = memoryObjectId.raw,
                        text = acceptedCandidate.text,
                        confidence = acceptedCandidate.confidence,
                        surfacePolicy = acceptedCandidate.surfacePolicy.name,
                        createdAtEpochMillis = decidedAt.toEpochMilli(),
                    ),
                )
                memoryDao.upsertMemorySupports(
                    acceptedCandidate.sourceTraceEventIds.map { supportTraceEventId ->
                        MemorySupportEntity(
                            memoryVersionId = memoryVersionId.raw,
                            traceEventId = supportTraceEventId.raw,
                        )
                    },
                )
            }
            memoryDao.upsertMemoryWriteReceipt(
                MemoryWriteReceiptEntity(
                    id = receiptId.raw,
                    outcome = decision.outcome.name,
                    traceEventId = traceEvent.id.raw,
                    memoryObjectId = memoryObjectId?.raw,
                    memoryVersionId = memoryVersionId?.raw,
                    decidedAtEpochMillis = decidedAt.toEpochMilli(),
                    reason = decision.reason,
                ),
            )
        }

        return MemoryWriteReceipt(
            id = receiptId,
            outcome = decision.outcome,
            traceEventId = traceEvent.id,
            memoryObjectId = memoryObjectId,
            memoryVersionId = memoryVersionId,
            decidedAt = decidedAt,
            reason = decision.reason,
        )
    }

    override suspend fun tombstoneMemory(
        memoryObjectId: MemoryObjectId,
        reason: String,
    ): MemoryWriteReceipt {
        val decidedAt = clock.now()
        val receiptId = idFactory.createReceiptId()
        database.withTransaction {
            memoryDao.updateMemoryStatus(
                memoryObjectId = memoryObjectId.raw,
                status = MemoryStatus.TOMBSTONED.name,
                updatedAtEpochMillis = decidedAt.toEpochMilli(),
            )
            memoryDao.upsertMemoryWriteReceipt(
                MemoryWriteReceiptEntity(
                    id = receiptId.raw,
                    outcome = MemoryWriteOutcome.TRACE_ONLY.name,
                    traceEventId = null,
                    memoryObjectId = memoryObjectId.raw,
                    memoryVersionId = null,
                    decidedAtEpochMillis = decidedAt.toEpochMilli(),
                    reason = reason,
                ),
            )
        }

        return MemoryWriteReceipt(
            id = receiptId,
            outcome = MemoryWriteOutcome.TRACE_ONLY,
            traceEventId = null,
            memoryObjectId = memoryObjectId,
            memoryVersionId = null,
            decidedAt = decidedAt,
            reason = reason,
        )
    }

    override suspend fun listPromptVisibleMemories(limit: Int): List<RetrievedMemoryRef> =
        listPromptVisibleMemoryRefs(limit = limit.coerceAtLeast(1)) {
            listOf("review-list")
        }

    override suspend fun retrieveMemories(query: String, limit: Int): RetrievalBundle {
        val retrievedAt = clock.now()
        val queryTokens = tokenize(query)
        val candidates = listPromptVisibleMemoryRefs(
            limit = limit * CANDIDATE_MULTIPLIER,
        ) { versionWithKind ->
            buildReasonCodes(queryTokens, versionWithKind.version.text)
        }
            .filter { retrievedMemory ->
                retrievedMemory.reasonCodes.isNotEmpty() || queryTokens.isEmpty()
            }
            .take(limit)

        val promptBlock = buildPromptBlock(candidates)
        persistRetrievalReceipt(query, promptBlock, retrievedAt, candidates)

        return RetrievalBundle(
            retrievedAt = retrievedAt,
            refs = candidates,
            promptBlock = promptBlock,
        )
    }

    private suspend fun listPromptVisibleMemoryRefs(
        limit: Int,
        buildReasonCodes: (MemoryVersionWithKind) -> List<String>,
    ): List<RetrievedMemoryRef> =
        memoryDao
            .listPromptVisibleVersions(
                activeStatus = MemoryStatus.ACTIVE.name,
                surfacePolicy = SurfacePolicy.PROMPT_VISIBLE.name,
                limit = limit,
            )
            .map { versionWithKind ->
                val supportTraceEventIds = memoryDao.listSupportTraceEventIds(versionWithKind.version.id)
                versionWithKind.toMemoryVersionRecord(supportTraceEventIds)
                    .toRetrievedMemoryRef(
                        kind = MemoryKind.valueOf(versionWithKind.objectKind),
                        reasonCodes = buildReasonCodes(versionWithKind),
                    )
            }

    private suspend fun persistRetrievalReceipt(
        query: String,
        promptBlock: String,
        retrievedAt: java.time.Instant,
        candidates: List<RetrievedMemoryRef>,
    ) {
        val receiptId = idFactory.createReceiptId()
        database.withTransaction {
            memoryDao.upsertRetrievalReceipt(
                RetrievalReceiptEntity(
                    id = receiptId.raw,
                    query = query,
                    promptBlock = promptBlock,
                    retrievedAtEpochMillis = retrievedAt.toEpochMilli(),
                ),
            )
            memoryDao.upsertRetrievedMemoryReceipts(
                candidates.map { candidate ->
                    RetrievedMemoryReceiptEntity(
                        retrievalReceiptId = receiptId.raw,
                        memoryVersionId = candidate.memoryVersionId.raw,
                        memoryObjectId = candidate.memoryObjectId.raw,
                        reasonCodes = candidate.reasonCodes.joinToString(separator = ","),
                    )
                },
            )
        }
    }

    private fun TraceEventRecord.toEntity(): TraceEventEntity =
        TraceEventEntity(
            id = id.raw,
            sourceMessageId = sourceMessageId.raw,
            role = role.name,
            text = text,
            observedAtEpochMillis = observedAt.toEpochMilli(),
        )

    private fun MemoryVersionWithKind.toMemoryVersionRecord(
        supportTraceEventIds: List<String>,
    ): MemoryVersionRecord =
        MemoryVersionRecord(
            id = MemoryVersionId(version.id),
            memoryObjectId = MemoryObjectId(version.memoryObjectId),
            text = version.text,
            confidence = version.confidence,
            surfacePolicy = SurfacePolicy.valueOf(version.surfacePolicy),
            sourceTraceEventIds = supportTraceEventIds.map(::TraceEventId),
            createdAt = java.time.Instant.ofEpochMilli(version.createdAtEpochMillis),
        )

    private fun MemoryVersionRecord.toRetrievedMemoryRef(
        kind: MemoryKind,
        reasonCodes: List<String>,
    ): RetrievedMemoryRef =
        RetrievedMemoryRef(
            memoryObjectId = memoryObjectId,
            memoryVersionId = id,
            kind = kind,
            text = text,
            confidence = confidence,
            reasonCodes = reasonCodes,
        )

    private fun buildReasonCodes(queryTokens: Set<String>, memoryText: String): List<String> {
        if (queryTokens.isEmpty()) return listOf("recent-memory")
        val memoryTokens = tokenize(memoryText)
        val overlap = queryTokens.intersect(memoryTokens)
        return overlap.take(MAX_REASON_CODES).map { token -> "token:$token" }
    }

    private fun buildPromptBlock(candidates: List<RetrievedMemoryRef>): String {
        if (candidates.isEmpty()) return ""
        return candidates.joinToString(separator = "\n") { candidate ->
            "- ${candidate.text}"
        }
    }

    private fun tokenize(text: String): Set<String> =
        text.lowercase()
            .split(nonWordPattern)
            .asSequence()
            .map { token -> token.trim() }
            .filter { token -> token.length >= MINIMUM_TOKEN_LENGTH }
            .filterNot { token -> token in stopWords }
            .toSet()

    private companion object {
        const val CANDIDATE_MULTIPLIER = 4
        const val MAX_REASON_CODES = 4
        const val MINIMUM_TOKEN_LENGTH = 3

        val nonWordPattern = Regex("[^a-z0-9']+")
        val stopWords = setOf(
            "the",
            "and",
            "for",
            "that",
            "this",
            "with",
            "you",
            "your",
            "about",
            "what",
            "when",
            "where",
            "need",
            "want",
        )
    }
}
