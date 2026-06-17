package app.synapse.localllm.ui

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import app.synapse.localllm.di.SynapseApplicationGraph
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SynapseViewModelLibraryTest {
    private lateinit var context: Context
    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        context = ApplicationProvider.getApplicationContext()
        clearPersistentLibraryState()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        clearPersistentLibraryState()
    }

    @Test
    fun createMarkdownArtifactUpdatesLibraryPanelState() = runTest {
        val graph = SynapseApplicationGraph.create(context)
        val viewModelStore = ViewModelStore()
        try {
            val viewModel = ViewModelProvider(
                viewModelStore,
                SynapseViewModelFactory(graph, mainDispatcher),
            )[SynapseViewModel::class.java]

            waitForInitialThreadBinding(viewModel)
            viewModel.selectPanel(SynapsePanel.LIBRARY)
            viewModel.updateLibraryDraftTitle("Project Note")
            viewModel.updateLibraryDraftMarkdown("This is saved as a workspace artifact.")
            viewModel.createLibraryMarkdownArtifact()

            val state = waitForSavedLibraryArtifact(viewModel)

            assertEquals(SynapsePanel.LIBRARY, state.activePanel)
            assertEquals("", state.libraryDraftTitle)
            assertEquals("", state.libraryDraftMarkdown)
            assertEquals(1, state.libraryArtifacts.size)
            assertEquals("Project Note", state.libraryArtifacts.single().title)
            assertTrue(state.lastNotice.orEmpty().contains("Saved Markdown note"))
        } finally {
            viewModelStore.clear()
            graph.database.close()
        }
    }

    private fun clearPersistentLibraryState() {
        context.deleteDatabase("synapse.db")
        File(context.filesDir, "library").deleteRecursively()
        File(context.cacheDir, "library-exports").deleteRecursively()
    }

    private fun waitForSavedLibraryArtifact(viewModel: SynapseViewModel): SynapseUiState {
        repeat(100) {
            mainDispatcher.scheduler.advanceUntilIdle()
            val currentState = viewModel.uiState.value
            if (
                currentState.libraryArtifacts.size == 1 &&
                currentState.libraryDraftTitle.isEmpty() &&
                !currentState.isCreatingLibraryArtifact
            ) {
                return currentState
            }
            Thread.sleep(20)
        }
        fail("Timed out waiting for library artifact state. Last state: ${viewModel.uiState.value}")
        throw AssertionError("Unreachable after fail.")
    }

    private fun waitForInitialThreadBinding(viewModel: SynapseViewModel) {
        repeat(100) {
            mainDispatcher.scheduler.advanceUntilIdle()
            if (viewModel.uiState.value.currentThread != null) return
            Thread.sleep(20)
        }
        fail("Timed out waiting for default thread binding. Last state: ${viewModel.uiState.value}")
    }
}
