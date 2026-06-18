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
import app.synapse.localllm.domain.memory.MemoryClaimCandidate
import app.synapse.localllm.domain.memory.MemoryKind
import app.synapse.localllm.domain.memory.MemoryRepository
import app.synapse.localllm.domain.memory.MemoryReviewFilter
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
import java.time.Instant

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
        var receiptOutcome = decision.outcome
        var receiptMemoryObjectId: MemoryObjectId? = null
        var receiptMemoryVersionId: MemoryVersionId? = null

        database.withTransaction {
            val acceptedCandidate = decision.candidate
                ?.takeIf { decision.outcome == MemoryWriteOutcome.DURABLE_MEMORY_WRITTEN }
            if (acceptedCandidate != null) {
                val writeResult = persistAcceptedMemoryCandidate(
                    candidate = acceptedCandidate,
                    decidedAt = decidedAt,
                )
                receiptOutcome = writeResult.outcome
                receiptMemoryObjectId = writeResult.memoryObjectId
                receiptMemoryVersionId = writeResult.memoryVersionId
            }
            memoryDao.upsertMemoryWriteReceipt(
                MemoryWriteReceiptEntity(
                    id = receiptId.raw,
                    outcome = receiptOutcome.name,
                    traceEventId = traceEvent.id.raw,
                    memoryObjectId = receiptMemoryObjectId?.raw,
                    memoryVersionId = receiptMemoryVersionId?.raw,
                    decidedAtEpochMillis = decidedAt.toEpochMilli(),
                    reason = buildWriteReceiptReason(decision.reason, receiptOutcome),
                ),
            )
        }

        return MemoryWriteReceipt(
            id = receiptId,
            outcome = receiptOutcome,
            traceEventId = traceEvent.id,
            memoryObjectId = receiptMemoryObjectId,
            memoryVersionId = receiptMemoryVersionId,
            decidedAt = decidedAt,
            reason = buildWriteReceiptReason(decision.reason, receiptOutcome),
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
                    outcome = MemoryWriteOutcome.MEMORY_TOMBSTONED.name,
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
            outcome = MemoryWriteOutcome.MEMORY_TOMBSTONED,
            traceEventId = null,
            memoryObjectId = memoryObjectId,
            memoryVersionId = null,
            decidedAt = decidedAt,
            reason = reason,
        )
    }

    override suspend fun tombstoneMemoriesMatching(
        traceEvent: TraceEventRecord,
        query: String,
        reason: String,
    ): List<MemoryWriteReceipt> {
        val decidedAt = clock.now()
        val activeMemories = memoryDao.listLatestVersionsByStatuses(
            statuses = listOf(MemoryStatus.ACTIVE.name),
            limit = TOMBSTONE_SEARCH_LIMIT,
        )
        val queryTokens = tokenize(query)
        val matchingMemories = activeMemories.filter { memory ->
            matchesTombstoneQuery(
                query = query,
                queryTokens = queryTokens,
                memory = memory,
            )
        }

        if (matchingMemories.isEmpty()) {
            val receiptId = idFactory.createReceiptId()
            database.withTransaction {
                memoryDao.upsertMemoryWriteReceipt(
                    MemoryWriteReceiptEntity(
                        id = receiptId.raw,
                        outcome = MemoryWriteOutcome.REJECTED.name,
                        traceEventId = traceEvent.id.raw,
                        memoryObjectId = null,
                        memoryVersionId = null,
                        decidedAtEpochMillis = decidedAt.toEpochMilli(),
                        reason = "No active memories matched delete request: $query",
                    ),
                )
            }
            return listOf(
                MemoryWriteReceipt(
                    id = receiptId,
                    outcome = MemoryWriteOutcome.REJECTED,
                    traceEventId = traceEvent.id,
                    memoryObjectId = null,
                    memoryVersionId = null,
                    decidedAt = decidedAt,
                    reason = "No active memories matched delete request: $query",
                ),
            )
        }

        val receipts = matchingMemories.map { memory ->
            val receiptId = idFactory.createReceiptId()
            MemoryWriteReceipt(
                id = receiptId,
                outcome = MemoryWriteOutcome.MEMORY_TOMBSTONED,
                traceEventId = traceEvent.id,
                memoryObjectId = MemoryObjectId(memory.version.memoryObjectId),
                memoryVersionId = MemoryVersionId(memory.version.id),
                decidedAt = decidedAt,
                reason = reason,
            )
        }
        database.withTransaction {
            memoryDao.updateMemoryStatuses(
                memoryObjectIds = matchingMemories.map { memory -> memory.version.memoryObjectId },
                status = MemoryStatus.TOMBSTONED.name,
                updatedAtEpochMillis = decidedAt.toEpochMilli(),
            )
            receipts.forEach { receipt ->
                memoryDao.upsertMemoryWriteReceipt(
                    MemoryWriteReceiptEntity(
                        id = receipt.id.raw,
                        outcome = receipt.outcome.name,
                        traceEventId = traceEvent.id.raw,
                        memoryObjectId = receipt.memoryObjectId?.raw,
                        memoryVersionId = receipt.memoryVersionId?.raw,
                        decidedAtEpochMillis = decidedAt.toEpochMilli(),
                        reason = reason,
                    ),
                )
            }
        }
        return receipts
    }

    override suspend fun listPromptVisibleMemories(limit: Int): List<RetrievedMemoryRef> =
        listPromptVisibleMemoryRefs(limit = limit.coerceAtLeast(1)) { versionWithKind ->
            versionWithKind.toRetrievedMemoryRef(
                supportTraceEventIds = memoryDao.listSupportTraceEventIds(versionWithKind.version.id),
                reasonCodes = listOf("review-list"),
            )
        }

    override suspend fun listMemoriesForReview(
        filter: MemoryReviewFilter,
        limit: Int,
    ): List<RetrievedMemoryRef> {
        val statuses = when (filter) {
            MemoryReviewFilter.ACTIVE -> listOf(MemoryStatus.ACTIVE)
            MemoryReviewFilter.INACTIVE -> inactiveReviewStatuses
            MemoryReviewFilter.ALL -> listOf(MemoryStatus.ACTIVE) + inactiveReviewStatuses
        }.map { status -> status.name }

        return memoryDao.listLatestVersionsByStatuses(
            statuses = statuses,
            limit = limit.coerceAtLeast(1),
        ).map { versionWithKind ->
            versionWithKind.toRetrievedMemoryRef(
                supportTraceEventIds = memoryDao.listSupportTraceEventIds(versionWithKind.version.id),
                reasonCodes = listOf("review:${versionWithKind.objectStatus.lowercase()}"),
            )
        }
    }

    override suspend fun retrieveMemories(query: String, limit: Int): RetrievalBundle {
        val retrievedAt = clock.now()
        val queryTokens = tokenize(query)
        val retrievalProfile = MemoryRetrievalProfile.classify(query)
        val candidates = listPromptVisibleMemoryRefs(
            limit = limit.coerceAtLeast(1) * CANDIDATE_MULTIPLIER,
        ) { versionWithKind ->
            val reasonCodes = buildReasonCodes(
                queryTokens = queryTokens,
                retrievalProfile = retrievalProfile,
                versionWithKind = versionWithKind,
            )
            versionWithKind.toRetrievedMemoryRef(
                supportTraceEventIds = memoryDao.listSupportTraceEventIds(versionWithKind.version.id),
                reasonCodes = reasonCodes,
            )
        }
            .filter { retrievedMemory ->
                retrievedMemory.reasonCodes.isNotEmpty() || queryTokens.isEmpty()
            }
            .map { memoryRef ->
                memoryRef.copy(rankScore = scoreRetrievedMemory(memoryRef, retrievalProfile))
            }
            .sortedWith(
                compareByDescending<RetrievedMemoryRef> { memory -> memory.rankScore }
                    .thenByDescending { memory -> memory.createdAt },
            )
            .take(limit.coerceAtLeast(1))

        val promptBlock = buildPromptBlock(candidates)
        persistRetrievalReceipt(
            query = query,
            retrievalIntent = retrievalProfile.intent,
            promptBlock = promptBlock,
            retrievedAt = retrievedAt,
            candidates = candidates,
        )

        return RetrievalBundle(
            retrievedAt = retrievedAt,
            refs = candidates,
            promptBlock = promptBlock,
        )
    }

    private suspend fun persistAcceptedMemoryCandidate(
        candidate: MemoryClaimCandidate,
        decidedAt: Instant,
    ): PersistedMemoryWriteResult {
        val normalizedClaimKey = candidate.claimKey?.trim()?.takeIf { claimKey -> claimKey.isNotBlank() }
        val activeSameClaim = normalizedClaimKey
            ?.let { claimKey ->
                memoryDao.listActiveVersionsByClaimKey(
                    activeStatus = MemoryStatus.ACTIVE.name,
                    claimKey = claimKey,
                )
            }
            .orEmpty()
        val exactActiveMatch = activeSameClaim.firstOrNull { existingMemory ->
            existingMemory.objectKind == candidate.kind.name &&
                normalizeClaimText(existingMemory.version.text) == normalizeClaimText(candidate.text)
        }
        if (exactActiveMatch != null) {
            val refreshedVersionId = idFactory.createMemoryVersionId()
            memoryDao.updateMemoryStatus(
                memoryObjectId = exactActiveMatch.version.memoryObjectId,
                status = MemoryStatus.ACTIVE.name,
                updatedAtEpochMillis = decidedAt.toEpochMilli(),
            )
            upsertMemoryVersionWithSupports(
                memoryObjectId = MemoryObjectId(exactActiveMatch.version.memoryObjectId),
                memoryVersionId = refreshedVersionId,
                candidate = candidate,
                decidedAt = decidedAt,
            )
            return PersistedMemoryWriteResult(
                outcome = MemoryWriteOutcome.MEMORY_UPDATED,
                memoryObjectId = MemoryObjectId(exactActiveMatch.version.memoryObjectId),
                memoryVersionId = refreshedVersionId,
            )
        }

        val supersededMemoryObjectIds = activeSameClaim.map { memory -> memory.version.memoryObjectId }
        if (supersededMemoryObjectIds.isNotEmpty()) {
            memoryDao.updateMemoryStatuses(
                memoryObjectIds = supersededMemoryObjectIds,
                status = MemoryStatus.SUPERSEDED.name,
                updatedAtEpochMillis = decidedAt.toEpochMilli(),
            )
        }

        val memoryObjectId = idFactory.createMemoryObjectId()
        val memoryVersionId = idFactory.createMemoryVersionId()
        memoryDao.upsertMemoryObject(
            MemoryObjectEntity(
                id = memoryObjectId.raw,
                kind = candidate.kind.name,
                status = MemoryStatus.ACTIVE.name,
                claimKey = normalizedClaimKey,
                createdAtEpochMillis = decidedAt.toEpochMilli(),
                updatedAtEpochMillis = decidedAt.toEpochMilli(),
            ),
        )
        upsertMemoryVersionWithSupports(
            memoryObjectId = memoryObjectId,
            memoryVersionId = memoryVersionId,
            candidate = candidate,
            decidedAt = decidedAt,
        )
        return PersistedMemoryWriteResult(
            outcome = if (supersededMemoryObjectIds.isEmpty()) {
                MemoryWriteOutcome.DURABLE_MEMORY_WRITTEN
            } else {
                MemoryWriteOutcome.MEMORY_SUPERSEDED
            },
            memoryObjectId = memoryObjectId,
            memoryVersionId = memoryVersionId,
        )
    }

    private suspend fun upsertMemoryVersionWithSupports(
        memoryObjectId: MemoryObjectId,
        memoryVersionId: MemoryVersionId,
        candidate: MemoryClaimCandidate,
        decidedAt: Instant,
    ) {
        memoryDao.upsertMemoryVersion(
            MemoryVersionEntity(
                id = memoryVersionId.raw,
                memoryObjectId = memoryObjectId.raw,
                text = candidate.text,
                confidence = candidate.confidence,
                surfacePolicy = candidate.surfacePolicy.name,
                scope = candidate.scope.name,
                subject = candidate.subject?.takeIf { subject -> subject.isNotBlank() },
                keywordsCsv = candidate.keywords
                    .map { keyword -> keyword.trim() }
                    .filter { keyword -> keyword.isNotBlank() }
                    .distinct()
                    .joinToString(","),
                createdAtEpochMillis = decidedAt.toEpochMilli(),
            ),
        )
        memoryDao.upsertMemorySupports(
            candidate.sourceTraceEventIds.map { supportTraceEventId ->
                MemorySupportEntity(
                    memoryVersionId = memoryVersionId.raw,
                    traceEventId = supportTraceEventId.raw,
                )
            },
        )
    }

    private suspend fun listPromptVisibleMemoryRefs(
        limit: Int,
        mapMemory: suspend (MemoryVersionWithKind) -> RetrievedMemoryRef,
    ): List<RetrievedMemoryRef> =
        memoryDao
            .listPromptVisibleVersions(
                activeStatus = MemoryStatus.ACTIVE.name,
                surfacePolicy = SurfacePolicy.PROMPT_VISIBLE.name,
                limit = limit,
            )
            .map { versionWithKind -> mapMemory(versionWithKind) }

    private suspend fun persistRetrievalReceipt(
        query: String,
        retrievalIntent: String,
        promptBlock: String,
        retrievedAt: Instant,
        candidates: List<RetrievedMemoryRef>,
    ) {
        val receiptId = idFactory.createReceiptId()
        database.withTransaction {
            memoryDao.upsertRetrievalReceipt(
                RetrievalReceiptEntity(
                    id = receiptId.raw,
                    query = query,
                    retrievalIntent = retrievalIntent,
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
                        rankScore = candidate.rankScore,
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
            createdAt = Instant.ofEpochMilli(version.createdAtEpochMillis),
            scope = MemoryScope.valueOf(version.scope),
            subject = version.subject,
            keywords = parseKeywordsCsv(version.keywordsCsv),
        )

    private fun MemoryVersionWithKind.toRetrievedMemoryRef(
        supportTraceEventIds: List<String>,
        reasonCodes: List<String>,
    ): RetrievedMemoryRef {
        val memoryVersion = toMemoryVersionRecord(supportTraceEventIds)
        return RetrievedMemoryRef(
            memoryObjectId = memoryVersion.memoryObjectId,
            memoryVersionId = memoryVersion.id,
            kind = MemoryKind.valueOf(objectKind),
            status = MemoryStatus.valueOf(objectStatus),
            text = memoryVersion.text,
            confidence = memoryVersion.confidence,
            reasonCodes = reasonCodes,
            scope = memoryVersion.scope,
            subject = memoryVersion.subject,
            keywords = memoryVersion.keywords,
            claimKey = objectClaimKey,
            sourceTraceEventIds = memoryVersion.sourceTraceEventIds,
            createdAt = memoryVersion.createdAt,
        )
    }

    private fun buildReasonCodes(
        queryTokens: Set<String>,
        retrievalProfile: MemoryRetrievalProfile,
        versionWithKind: MemoryVersionWithKind,
    ): List<String> {
        if (queryTokens.isEmpty()) return listOf("recent-memory")

        val memoryKind = MemoryKind.valueOf(versionWithKind.objectKind)
        val searchableText = buildSearchableMemoryText(versionWithKind)
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
        val claimKeyReasonCodes = versionWithKind.objectClaimKey
            ?.let(::tokenize)
            ?.intersect(queryTokens)
            ?.take(MAX_REASON_CODES)
            ?.map { token -> "claim-key:$token" }
            .orEmpty()

        return (lexicalReasonCodes + intentReasonCodes + claimKeyReasonCodes).distinct()
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

    private fun scoreRetrievedMemory(
        memoryRef: RetrievedMemoryRef,
        retrievalProfile: MemoryRetrievalProfile,
    ): Double {
        val reasonScore = memoryRef.reasonCodes.sumOf { reasonCode ->
            when {
                reasonCode == "all-memory-review" -> 2.0
                reasonCode.startsWith("intent:") -> 8.0
                reasonCode.startsWith("scope:") -> 5.0
                reasonCode.startsWith("claim-key:") -> 4.0
                reasonCode.startsWith("token:") -> 3.0
                else -> 1.0
            }
        }
        val kindBoost = if (memoryRef.kind in retrievalProfile.targetKinds) 3.0 else 0.0
        return reasonScore + kindBoost + (memoryRef.confidence * 2.0)
    }

    private fun buildSearchableMemoryText(memory: MemoryVersionWithKind): String =
        listOfNotNull(
            memory.version.text,
            memory.version.subject,
            memory.version.keywordsCsv,
            memory.objectClaimKey,
            memory.objectKind,
        ).joinToString(" ")

    private fun matchesTombstoneQuery(
        query: String,
        queryTokens: Set<String>,
        memory: MemoryVersionWithKind,
    ): Boolean {
        if (queryTokens.isEmpty()) return false
        val searchableText = buildSearchableMemoryText(memory)
        val memoryTokens = tokenize(searchableText)
        val overlap = queryTokens.intersect(memoryTokens)
        if (overlap.isEmpty()) return false
        if (overlap.size >= MINIMUM_TOMBSTONE_TOKEN_OVERLAP) return true
        val normalizedQuery = query.lowercase()
        val claimKey = memory.objectClaimKey.orEmpty().replace('.', ' ')
        return claimKey.isNotBlank() && tokenize(claimKey).any { token -> token in tokenize(normalizedQuery) }
    }

    private fun buildWriteReceiptReason(
        originalReason: String,
        outcome: MemoryWriteOutcome,
    ): String =
        when (outcome) {
            MemoryWriteOutcome.MEMORY_UPDATED -> "$originalReason Existing claim refreshed."
            MemoryWriteOutcome.MEMORY_SUPERSEDED -> "$originalReason Older active claim superseded."
            else -> originalReason
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

    private fun normalizeClaimText(text: String): String =
        text.lowercase()
            .replace(nonWordPattern, " ")
            .trim()
            .replace(Regex("\\s+"), " ")

    private companion object {
        const val CANDIDATE_MULTIPLIER = 4
        const val MAX_REASON_CODES = 4
        const val MINIMUM_TOKEN_LENGTH = 3
        const val TOMBSTONE_SEARCH_LIMIT = 200
        const val MINIMUM_TOMBSTONE_TOKEN_OVERLAP = 2

        val inactiveReviewStatuses = listOf(
            MemoryStatus.ARCHIVED,
            MemoryStatus.SUPERSEDED,
            MemoryStatus.CONFLICTED,
            MemoryStatus.QUARANTINED,
            MemoryStatus.TOMBSTONED,
        )
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
            "memory",
            "memories",
            "remember",
            "forget",
            "delete",
            "remove",
            "from",
        )
    }
}

private data class PersistedMemoryWriteResult(
    val outcome: MemoryWriteOutcome,
    val memoryObjectId: MemoryObjectId,
    val memoryVersionId: MemoryVersionId,
)

private data class MemoryRetrievalProfile(
    val intent: String,
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
            var intent = GENERAL_INTENT

            if (generalMemoryRecallPatterns.any { pattern -> pattern.containsMatchIn(normalizedQuery) }) {
                includeAllPromptVisibleMemories = true
                intent = MEMORY_REVIEW_INTENT
            }
            if (identityRecallPatterns.any { pattern -> pattern.containsMatchIn(normalizedQuery) }) {
                targetKinds += MemoryKind.IDENTITY
                targetKinds += MemoryKind.RELATIONSHIP
                intent = IDENTITY_INTENT
            }
            if (preferenceRecallPatterns.any { pattern -> pattern.containsMatchIn(normalizedQuery) }) {
                targetKinds += MemoryKind.PREFERENCE
                intent = PREFERENCE_INTENT
            }
            if (projectRecallPatterns.any { pattern -> pattern.containsMatchIn(normalizedQuery) }) {
                targetKinds += MemoryKind.PROJECT
                targetKinds += MemoryKind.SUMMARY
                targetScopes += MemoryScope.PROJECT
                intent = PROJECT_INTENT
            }
            if (appointmentRecallPatterns.any { pattern -> pattern.containsMatchIn(normalizedQuery) }) {
                targetKinds += MemoryKind.APPOINTMENT
                targetKinds += MemoryKind.COMMITMENT
                intent = APPOINTMENT_INTENT
            }
            if (instructionRecallPatterns.any { pattern -> pattern.containsMatchIn(normalizedQuery) }) {
                targetKinds += MemoryKind.INSTRUCTION
                targetKinds += MemoryKind.PROCEDURE
                intent = INSTRUCTION_INTENT
            }

            return MemoryRetrievalProfile(
                intent = intent,
                targetKinds = targetKinds,
                targetScopes = targetScopes,
                includeAllPromptVisibleMemories = includeAllPromptVisibleMemories,
            )
        }

        private const val GENERAL_INTENT = "GENERAL"
        private const val MEMORY_REVIEW_INTENT = "MEMORY_REVIEW"
        private const val IDENTITY_INTENT = "IDENTITY"
        private const val PREFERENCE_INTENT = "PREFERENCE"
        private const val PROJECT_INTENT = "PROJECT"
        private const val APPOINTMENT_INTENT = "APPOINTMENT"
        private const val INSTRUCTION_INTENT = "INSTRUCTION"

        private val generalMemoryRecallPatterns = listOf(
            Regex("\\b(remember|memory|memories|saved memories|saved preferences)\\b"),
            Regex("\\bwhat\\s+do\\s+you\\s+(know|remember)\\b"),
            Regex("\\bwhat\\s+have\\s+you\\s+saved\\b"),
        )
        private val identityRecallPatterns = listOf(
            Regex(
                "\\bmy\\s+(full\\s+name|first\\s+name|middle\\s+name|last\\s+name|name|" +
                    "birthday|email|phone|address|location|role|job)\\b",
            ),
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
