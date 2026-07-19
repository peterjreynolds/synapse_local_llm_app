package app.synapse.localllm.ui

import app.synapse.localllm.domain.calling.DirectCallAlertGateway
import app.synapse.localllm.domain.calling.DirectCallRingtoneMutationReceipt
import app.synapse.localllm.domain.calling.DirectCallRingtoneRepository
import app.synapse.localllm.domain.calling.DirectCallRingtoneSelection
import app.synapse.localllm.domain.calling.DirectCallRingtoneSource
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DirectCallRingtoneViewModelTest {
    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun savesASelectedAudioFileAndSurfacesTheDurableSelection() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repository = RecordingRingtoneRepository()
        val viewModel = DirectCallRingtoneViewModel(repository, RecordingAlertGateway())

        viewModel.selectAudioFile(CUSTOM_AUDIO_URI)
        runCurrent()

        assertEquals(DirectCallRingtoneSource.AUDIO_FILE, viewModel.uiState.value.selection.source)
        assertEquals(CUSTOM_AUDIO_URI, viewModel.uiState.value.selection.uri)
        assertEquals("Personal ring.ogg", viewModel.uiState.value.selection.displayName)
        assertFalse(viewModel.uiState.value.isSaving)
        assertEquals("Incoming ringtone saved on this phone.", viewModel.uiState.value.notice)
    }

    @Test
    fun previewUsesTheIncomingRingtonePathAndStopsAfterEightSeconds() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val alertGateway = RecordingAlertGateway()
        val viewModel = DirectCallRingtoneViewModel(
            repository = RecordingRingtoneRepository(),
            alertGateway = alertGateway,
            nowEpochMillis = { 1_000L },
        )

        viewModel.togglePreview()

        assertTrue(alertGateway.incomingRingtonePlaying)
        assertEquals(9_000L, alertGateway.expiresAtMillis)
        assertTrue(viewModel.uiState.value.isPreviewing)

        advanceTimeBy(8_000L)
        runCurrent()

        assertFalse(alertGateway.incomingRingtonePlaying)
        assertFalse(viewModel.uiState.value.isPreviewing)
    }

    private class RecordingRingtoneRepository : DirectCallRingtoneRepository {
        private var selection = DirectCallRingtoneSelection()

        override fun currentSelection(): DirectCallRingtoneSelection = selection

        override suspend fun usePhoneDefaultRingtone(): DirectCallRingtoneMutationReceipt =
            persist(DirectCallRingtoneSelection())

        override suspend fun selectPhoneRingtone(uri: String): DirectCallRingtoneMutationReceipt =
            persist(
                DirectCallRingtoneSelection(
                    source = DirectCallRingtoneSource.PHONE_RINGTONE,
                    uri = uri,
                    displayName = "Phone tone",
                ),
            )

        override suspend fun selectAudioFile(uri: String): DirectCallRingtoneMutationReceipt =
            persist(
                DirectCallRingtoneSelection(
                    source = DirectCallRingtoneSource.AUDIO_FILE,
                    uri = uri,
                    displayName = "Personal ring.ogg",
                ),
            )

        private fun persist(updatedSelection: DirectCallRingtoneSelection): DirectCallRingtoneMutationReceipt {
            selection = updatedSelection
            return DirectCallRingtoneMutationReceipt(updatedSelection, Instant.EPOCH)
        }
    }

    private class RecordingAlertGateway : DirectCallAlertGateway {
        var expiresAtMillis: Long? = null
        var incomingRingtonePlaying = false

        override fun startOutgoingRingback(expiresAtMillis: Long) = Unit

        override fun startIncomingRingtone(expiresAtMillis: Long) {
            this.expiresAtMillis = expiresAtMillis
            incomingRingtonePlaying = true
        }

        override fun stop() {
            incomingRingtonePlaying = false
        }
    }

    private companion object {
        const val CUSTOM_AUDIO_URI = "content://ringtone.test/audio/personal-ring"
    }
}
