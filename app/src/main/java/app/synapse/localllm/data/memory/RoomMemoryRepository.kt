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
import app.synapse.localllm.domain.memory.MemoryScope
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
                        scope = acceptedCandidate.scope.name,
                        subject = acceptedCandidate.subject?.takeIf { subject -> subject.isNotBlank() },
                        keywordsCsv = acceptedCandidate.keywords
                            .map { keyword -> keyword.trim() }
                            .filter { keyword -> keyword.isNotBlank() }
                            .distinct()
                            .joinToString(","),
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
        val retrievalProfile = MemoryRetrievalProfile.classify(query)
        val candidates = listPromptVisibleMemoryRefs(
            limit = limit * CANDIDATE_MULTIPLIER,
        ) { versionWithKind ->
            buildReasonCodes(
                queryTokens = queryTokens,
                retrievalProfile = retrievalProfile,
                versionWithKind = versionWithKind,
            )
        }
            .filter { retrievedMemory ->
                retrievedMemory.reasonCodes.isNotEmpty() || queryTokens.isEmpty()
            }
            .sortedByDescending(::scoreRetrievedMemory)
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
            scope = MemoryScope.valueOf(version.scope),
            subject = version.subject,
            keywords = parseKeywordsCsv(version.keywordsCsv),
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
            scope = scope,
            subject = subject,
            keywords = keywords,
        )

    private fun buildReasonCodes(
        queryTokens: Set<String>,
        retrievalProfile: MemoryRetrievalProfile,
        versionWithKind: MemoryVersionWithKind,
    ): List<String> {
        if (queryTokens.isEmpty()) return listOf("recent-memory")

        val memoryKind = MemoryKind.valueOf(versionWithKind.objectKind)
        val searchableText = listOfNotNull(
            versionWithKind.version.text,
            versionWithKind.version.subject,
            versionWithKind.version.keywordsCsv,
        ).joinToString(" ")
        val memoryTokens = tokenize(searchableText)
        val overlap = queryTokens.intersect(memoryTokens)
        val lexicalReasonCodes = overlap
            .take(MAX_REASON_CODES)
            .map { token -> "token:$token" }

        val intentReasonCodes = buildIntentReasonCodes(
            retrievalProfile = retrievalProfile,
            memoryKind = memoryKind,
            memoryScope = MemoryScope.valueOf(versionWithKind.version.scope),
        )

        return (lexicalReasonCodes + intentReasonCodes).distinct()
    }

    private fun buildPromptBlock(candidates: List<RetrievedMemoryRef>): String {
        if (candidates.isEmpty()) return ""
        return candidates.joinToString(separator = "\n") { candidate ->
            val subjectLabel = candidate.subject
                ?.takeIf { subject -> subject.isNotBlank() }
                ?.let { subject -> " / $subject" }
                .orEmpty()
            "- [${candidate.kind.name.lowercase()} / ${candidate.scope.name.lowercase()}$subjectLabel] ${candidate.text}"
        }
    }

    private fun buildIntentReasonCodes(
        retrievalProfile: MemoryRetrievalProfile,
        memoryKind: MemoryKind,
        memoryScope: MemoryScope,
    ): List<String> {
        if (retrievalProfile.includeAllPromptVisibleMemories) {
            return listOf("all-memory-review")
        }

        val reasonCodes = mutableListOf<String>()
        if (memoryKind in retrievalProfile.targetKinds) {
            reasonCodes += "intent:${memoryKind.name.lowercase()}"
        }
        if (memoryScope in retrievalProfile.targetScopes) {
            reasonCodes += "scope:${memoryScope.name.lowercase()}"
        }
        return reasonCodes
    }

    private fun scoreRetrievedMemory(memoryRef: RetrievedMemoryRef): Int =
        memoryRef.reasonCodes.sumOf { reasonCode ->
            when {
                reasonCode == "all-memory-review" -> 2
                reasonCode.startsWith("intent:") -> 8
                reasonCode.startsWith("scope:") -> 5
                reasonCode.startsWith("token:") -> 3
                else -> 1
            }
        }

    private fun parseKeywordsCsv(keywordsCsv: String): List<String> =
        keywordsCsv
            .split(",")
            .map { keyword -> keyword.trim() }
            .filter { keyword -> keyword.isNotBlank() }

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

private data class MemoryRetrievalProfile(
    val targetKinds: Set<MemoryKind>,
    val targetScopes: Set<MemoryScope>,
    val includeAllPromptVisibleMemories: Boolean,
) {
    companion object {
        fun classify(query: String): MemoryRetrievalProfile {
            val normalizedQuery = query.lowercase()
            val targetKinds = mutableSetOf<MemoryKind>()
            val targetScopes = mutableSetOf<MemoryScope>()
            var includeAllPromptVisibleMemories = false

            if (generalMemoryRecallPatterns.any { pattern -> pattern.containsMatchIn(normalizedQuery) }) {
                includeAllPromptVisibleMemories = true
            }
            if (identityRecallPatterns.any { pattern -> pattern.containsMatchIn(normalizedQuery) }) {
                targetKinds += MemoryKind.IDENTITY
                targetKinds += MemoryKind.RELATIONSHIP
            }
            if (preferenceRecallPatterns.any { pattern -> pattern.containsMatchIn(normalizedQuery) }) {
                targetKinds += MemoryKind.PREFERENCE
            }
            if (projectRecallPatterns.any { pattern -> pattern.containsMatchIn(normalizedQuery) }) {
                targetKinds += MemoryKind.PROJECT
                targetKinds += MemoryKind.SUMMARY
                targetScopes += MemoryScope.PROJECT
            }
            if (appointmentRecallPatterns.any { pattern -> pattern.containsMatchIn(normalizedQuery) }) {
                targetKinds += MemoryKind.APPOINTMENT
                targetKinds += MemoryKind.COMMITMENT
            }
            if (instructionRecallPatterns.any { pattern -> pattern.containsMatchIn(normalizedQuery) }) {
                targetKinds += MemoryKind.INSTRUCTION
                targetKinds += MemoryKind.PROCEDURE
            }

            return MemoryRetrievalProfile(
                targetKinds = targetKinds,
                targetScopes = targetScopes,
                includeAllPromptVisibleMemories = includeAllPromptVisibleMemories,
            )
        }

        private val generalMemoryRecallPatterns = listOf(
            Regex("\\b(remember|memory|memories|saved memories|saved preferences)\\b"),
            Regex("\\bwhat\\s+do\\s+you\\s+(know|remember)\\b"),
            Regex("\\bwhat\\s+have\\s+you\\s+saved\\b"),
        )
        private val identityRecallPatterns = listOf(
            Regex("\\bmy\\s+(full\\s+name|first\\s+name|middle\\s+name|last\\s+name|name|birthday|email|phone|address|location|role|job)\\b"),
            Regex("\\bwho\\s+am\\s+i\\b"),
            Regex("\\bwhat\\s+is\\s+my\\s+name\\b"),
        )
        private val preferenceRecallPatterns = listOf(
            Regex("\\bmy\\s+favou?rite\\b"),
            Regex("\\bwhat\\s+do\\s+i\\s+(like|love|prefer|hate|dislike|want|need)\\b"),
            Regex("\\bmy\\s+preferences?\\b"),
        )
        private val projectRecallPatterns = listOf(
            Regex("\\b(project|repo|app|website|workspace)\\b"),
            Regex("\\bwhere\\s+were\\s+we\\b"),
            Regex("\\bwhat\\s+are\\s+we\\s+(working\\s+on|building|planning)\\b"),
        )
        private val appointmentRecallPatterns = listOf(
            Regex("\\b(appointments?|meetings?|calls?|deadlines?|due|schedule|calendar)\\b"),
            Regex("\\bwhat\\s+do\\s+i\\s+have\\s+(today|tomorrow|this\\s+week)\\b"),
        )
        private val instructionRecallPatterns = listOf(
            Regex("\\b(how\\s+do\\s+i\\s+like|how\\s+should\\s+you|instructions?|style|workflow)\\b"),
        )
    }
}
