package app.synapse.localllm.di

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.synapse.localllm.domain.library.CreateMarkdownArtifactCommand
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SynapseApplicationGraphLibraryTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        clearPersistentLibraryState()
    }

    @After
    fun tearDown() {
        clearPersistentLibraryState()
    }

    @Test
    fun graphExposesLibraryWorkspaceRepositoryForCatalogOperations() = runTest {
        val graph = SynapseApplicationGraph.create(context)
        try {
            val receipt = graph.libraryWorkspaceRepository.createMarkdownArtifact(
                CreateMarkdownArtifactCommand(
                    title = "Graph Wiring Note",
                    markdown = "The app graph owns the production repository wiring.",
                ),
            )

            val artifacts = graph.libraryWorkspaceRepository.listCatalogArtifacts(limit = 10)
            val markdownContent = graph.libraryWorkspaceRepository
                .readMarkdownArtifactContent(receipt.artifact.id)

            assertEquals(listOf(receipt.artifact.id), artifacts.map { artifact -> artifact.id })
            assertNotNull(graph.markdownPdfExporter)
            requireNotNull(markdownContent)
            assertEquals(receipt.artifact.id, markdownContent.artifact.id)
        } finally {
            graph.database.close()
        }
    }

    private fun clearPersistentLibraryState() {
        context.deleteDatabase("synapse.db")
        File(context.filesDir, "library").deleteRecursively()
        File(context.cacheDir, "library-exports").deleteRecursively()
    }
}
