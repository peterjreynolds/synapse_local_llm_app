package app.synapse.localllm.data.library

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.synapse.localllm.data.db.SynapseDatabase
import app.synapse.localllm.domain.ids.SynapseIdFactory
import app.synapse.localllm.domain.library.CreateMarkdownArtifactCommand
import app.synapse.localllm.domain.library.LibraryArtifactKind
import app.synapse.localllm.domain.library.LibraryArtifactSourceKind
import app.synapse.localllm.domain.library.LibraryArtifactWriteMutation
import app.synapse.localllm.domain.time.SynapseClock
import java.io.File
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomLibraryWorkspaceRepositoryTest {
    private lateinit var context: Context
    private lateinit var database: SynapseDatabase
    private lateinit var repository: RoomLibraryWorkspaceRepository
    private lateinit var clock: IncrementingSynapseClock

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "library").deleteRecursively()
        database = Room.inMemoryDatabaseBuilder(context, SynapseDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        clock = IncrementingSynapseClock()
        repository = RoomLibraryWorkspaceRepository(
            context = context,
            database = database,
            libraryDao = database.libraryDao(),
            idFactory = SynapseIdFactory(),
            clock = clock,
        )
    }

    @After
    fun tearDown() {
        database.close()
        File(context.filesDir, "library").deleteRecursively()
    }

    @Test
    fun createMarkdownArtifactWritesAppPrivateFileAndCatalogMetadata() = runTest {
        val receipt = repository.createMarkdownArtifact(
            CreateMarkdownArtifactCommand(
                title = "../../AI Readiness: Notes.md",
                markdown = "Small businesses need a clean readiness map.",
                catalogSummary = " Notes for AI readiness planning. ",
                tags = listOf("AI Readiness", "Small Business"),
            ),
        )

        val artifact = receipt.artifact
        val artifactFile = File(context.filesDir, artifact.relativePath)
        val catalogArtifacts = repository.listCatalogArtifacts(limit = 10)

        assertEquals(LibraryArtifactWriteMutation.CREATED, receipt.mutation)
        assertEquals("ai-readiness-notes.md", artifact.displayName)
        assertEquals(LibraryArtifactKind.MARKDOWN, artifact.kind)
        assertEquals(LibraryArtifactSourceKind.WORKSPACE_CREATED, artifact.sourceKind)
        assertEquals("text/markdown", artifact.mimeType)
        assertEquals(listOf("ai-readiness", "small-business"), artifact.tags)
        assertEquals("Notes for AI readiness planning.", artifact.catalogSummary)
        assertTrue(artifact.relativePath.startsWith("library/workspace/artifact-"))
        assertTrue(artifact.byteCount > 0)
        assertEquals(64, artifact.sha256.length)
        assertTrue(artifactFile.isFile)
        assertTrue(artifactFile.readText().startsWith("# AI Readiness: Notes"))
        assertEquals(listOf(artifact.id), catalogArtifacts.map { catalogArtifact -> catalogArtifact.id })
    }

    @Test
    fun findArtifactReturnsCatalogRecordWithoutReadingBodyIntoMemory() = runTest {
        val receipt = repository.createMarkdownArtifact(
            CreateMarkdownArtifactCommand(
                title = "Speaker Diarization Notes",
                markdown = "# Existing heading\n\nKeep source documents separate from memory.",
                tags = listOf("diarization"),
            ),
        )

        val foundArtifact = repository.findArtifact(receipt.artifact.id)

        requireNotNull(foundArtifact)
        assertEquals("Speaker Diarization Notes", foundArtifact.title)
        assertEquals("speaker-diarization-notes.md", foundArtifact.displayName)
    }

    @Test
    fun readMarkdownArtifactContentReadsOnlyCatalogOwnedWorkspaceFile() = runTest {
        val receipt = repository.createMarkdownArtifact(
            CreateMarkdownArtifactCommand(
                title = "Speaker Diarization Notes",
                markdown = "# Existing heading\n\nKeep source documents separate from memory.",
                tags = listOf("diarization"),
            ),
        )

        val markdownContent = repository.readMarkdownArtifactContent(receipt.artifact.id)

        requireNotNull(markdownContent)
        assertEquals(receipt.artifact.id, markdownContent.artifact.id)
        assertEquals(
            "# Existing heading\n\nKeep source documents separate from memory.\n",
            markdownContent.markdown,
        )
    }

    private class IncrementingSynapseClock : SynapseClock {
        private var tickMillis = 0L

        override fun now(): Instant {
            val instant = Instant.parse("2026-06-17T15:00:00Z").plusMillis(tickMillis)
            tickMillis += 10
            return instant
        }
    }
}
