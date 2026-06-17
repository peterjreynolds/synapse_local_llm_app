package app.synapse.localllm.data.library

import android.content.Context
import androidx.room.withTransaction
import app.synapse.localllm.data.db.LibraryArtifactEntity
import app.synapse.localllm.data.db.LibraryArtifactWriteReceiptEntity
import app.synapse.localllm.data.db.LibraryDao
import app.synapse.localllm.data.db.SynapseDatabase
import app.synapse.localllm.domain.ids.LibraryArtifactId
import app.synapse.localllm.domain.ids.SynapseIdFactory
import app.synapse.localllm.domain.library.CreateMarkdownArtifactCommand
import app.synapse.localllm.domain.library.LibraryArtifactKind
import app.synapse.localllm.domain.library.LibraryArtifactRecord
import app.synapse.localllm.domain.library.LibraryArtifactSourceKind
import app.synapse.localllm.domain.library.LibraryArtifactWriteMutation
import app.synapse.localllm.domain.library.LibraryWorkspaceRepository
import app.synapse.localllm.domain.library.MarkdownArtifactCreationReceipt
import app.synapse.localllm.domain.time.SynapseClock
import java.io.File
import java.security.MessageDigest
import java.time.Instant

class RoomLibraryWorkspaceRepository(
    context: Context,
    private val database: SynapseDatabase,
    private val libraryDao: LibraryDao,
    private val idFactory: SynapseIdFactory,
    private val clock: SynapseClock,
) : LibraryWorkspaceRepository {
    private val workspacePaths = LibraryWorkspacePaths(
        filesDirectory = context.applicationContext.filesDir,
        cacheDirectory = context.applicationContext.cacheDir,
    )

    override suspend fun createMarkdownArtifact(
        command: CreateMarkdownArtifactCommand,
    ): MarkdownArtifactCreationReceipt {
        val title = LibraryWorkspacePaths.normalizeArtifactTitle(command.title)
        val markdownBody = command.markdown.trim()
        require(markdownBody.isNotBlank()) { "Markdown artifact body cannot be blank." }

        val createdAt = clock.now()
        val artifactId = idFactory.createLibraryArtifactId()
        val receiptId = idFactory.createReceiptId()
        val filePlan = workspacePaths.planMarkdownArtifact(artifactId, title)
        val fileBody = buildMarkdownFileBody(title, markdownBody)
        writeUtf8Atomically(filePlan.file, fileBody)

        val byteCount = filePlan.file.length()
        val sha256 = filePlan.file.sha256()
        val artifact = LibraryArtifactEntity(
            id = artifactId.raw,
            title = title,
            displayName = filePlan.displayName,
            relativePath = filePlan.relativePath,
            mimeType = MARKDOWN_MIME_TYPE,
            artifactKind = LibraryArtifactKind.MARKDOWN.name,
            sourceKind = LibraryArtifactSourceKind.WORKSPACE_CREATED.name,
            sha256 = sha256,
            byteCount = byteCount,
            catalogSummary = LibraryWorkspacePaths.normalizeCatalogSummary(command.catalogSummary),
            tagsCsv = LibraryWorkspacePaths.normalizeTags(command.tags).joinToString(","),
            createdAtEpochMillis = createdAt.toEpochMilli(),
            updatedAtEpochMillis = createdAt.toEpochMilli(),
        )
        val receipt = LibraryArtifactWriteReceiptEntity(
            id = receiptId.raw,
            artifactId = artifactId.raw,
            mutation = LibraryArtifactWriteMutation.CREATED.name,
            writtenAtEpochMillis = createdAt.toEpochMilli(),
            reason = "Created Markdown artifact in the app-private Synapse workspace.",
            byteCount = byteCount,
            sha256 = sha256,
        )

        try {
            database.withTransaction {
                libraryDao.upsertLibraryArtifact(artifact)
                libraryDao.upsertLibraryArtifactWriteReceipt(receipt)
            }
        } catch (exception: RuntimeException) {
            filePlan.file.delete()
            throw exception
        }

        return MarkdownArtifactCreationReceipt(
            receiptId = receiptId,
            artifact = artifact.toDomain(),
            mutation = LibraryArtifactWriteMutation.CREATED,
            writtenAt = createdAt,
            reason = receipt.reason,
        )
    }

    override suspend fun findArtifact(artifactId: LibraryArtifactId): LibraryArtifactRecord? =
        libraryDao.findLibraryArtifact(artifactId.raw)?.toDomain()

    override suspend fun listCatalogArtifacts(limit: Int): List<LibraryArtifactRecord> {
        require(limit in 1..MAX_CATALOG_LIMIT) { "Library artifact catalog limit must be 1..$MAX_CATALOG_LIMIT." }
        return libraryDao.listLibraryArtifacts(limit).map { artifact -> artifact.toDomain() }
    }

    private fun buildMarkdownFileBody(
        title: String,
        markdownBody: String,
    ): String =
        if (markdownBody.startsWith("#")) {
            markdownBody.trimEnd() + "\n"
        } else {
            "# $title\n\n${markdownBody.trimEnd()}\n"
        }

    private fun writeUtf8Atomically(
        targetFile: File,
        body: String,
    ) {
        targetFile.parentFile?.mkdirs()
        val temporaryFile = File(targetFile.parentFile, "${targetFile.name}.tmp")
        temporaryFile.writeText(body, Charsets.UTF_8)
        if (targetFile.exists()) {
            targetFile.delete()
        }
        require(temporaryFile.renameTo(targetFile)) {
            "Could not move Markdown artifact into the Synapse workspace."
        }
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun LibraryArtifactEntity.toDomain(): LibraryArtifactRecord =
        LibraryArtifactRecord(
            id = LibraryArtifactId(id),
            title = title,
            displayName = displayName,
            relativePath = relativePath,
            mimeType = mimeType,
            kind = LibraryArtifactKind.valueOf(artifactKind),
            sourceKind = LibraryArtifactSourceKind.valueOf(sourceKind),
            sha256 = sha256,
            byteCount = byteCount,
            catalogSummary = catalogSummary,
            tags = tagsCsv.split(",").filter { tag -> tag.isNotBlank() },
            createdAt = Instant.ofEpochMilli(createdAtEpochMillis),
            updatedAt = Instant.ofEpochMilli(updatedAtEpochMillis),
        )

    private companion object {
        const val MARKDOWN_MIME_TYPE = "text/markdown"
        const val MAX_CATALOG_LIMIT = 500
    }
}
