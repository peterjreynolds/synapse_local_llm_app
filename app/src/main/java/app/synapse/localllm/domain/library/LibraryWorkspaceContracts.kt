package app.synapse.localllm.domain.library

import app.synapse.localllm.domain.ids.LibraryArtifactId
import app.synapse.localllm.domain.ids.ReceiptId
import java.time.Instant

enum class LibraryArtifactKind {
    MARKDOWN,
}

enum class LibraryArtifactSourceKind {
    WORKSPACE_CREATED,
}

enum class LibraryArtifactWriteMutation {
    CREATED,
}

data class CreateMarkdownArtifactCommand(
    val title: String,
    val markdown: String,
    val catalogSummary: String? = null,
    val tags: List<String> = emptyList(),
)

data class LibraryArtifactRecord(
    val id: LibraryArtifactId,
    val title: String,
    val displayName: String,
    val relativePath: String,
    val mimeType: String,
    val kind: LibraryArtifactKind,
    val sourceKind: LibraryArtifactSourceKind,
    val sha256: String,
    val byteCount: Long,
    val catalogSummary: String?,
    val tags: List<String>,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class MarkdownArtifactContent(
    val artifact: LibraryArtifactRecord,
    val markdown: String,
)

data class MarkdownArtifactCreationReceipt(
    val receiptId: ReceiptId,
    val artifact: LibraryArtifactRecord,
    val mutation: LibraryArtifactWriteMutation,
    val writtenAt: Instant,
    val reason: String,
)

interface LibraryWorkspaceRepository {
    suspend fun createMarkdownArtifact(
        command: CreateMarkdownArtifactCommand,
    ): MarkdownArtifactCreationReceipt

    suspend fun findArtifact(artifactId: LibraryArtifactId): LibraryArtifactRecord?

    suspend fun readMarkdownArtifactContent(artifactId: LibraryArtifactId): MarkdownArtifactContent?

    suspend fun listCatalogArtifacts(limit: Int): List<LibraryArtifactRecord>
}
