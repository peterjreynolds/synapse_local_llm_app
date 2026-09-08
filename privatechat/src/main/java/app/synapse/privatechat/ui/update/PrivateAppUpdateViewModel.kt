package app.synapse.privatechat.ui.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.synapse.privatechat.domain.update.PrivateAppInstallerLaunchOutcome
import app.synapse.privatechat.domain.update.PrivateAppUpdateCheckOutcome
import app.synapse.privatechat.domain.update.PrivateAppUpdateDownloadEvent
import app.synapse.privatechat.domain.update.PrivateAppUpdateDownloadReceipt
import app.synapse.privatechat.domain.update.PrivateAppUpdateDownloader
import app.synapse.privatechat.domain.update.PrivateAppUpdateRepository
import app.synapse.privatechat.domain.update.PrivateAvailableAppUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

sealed interface PrivateAppUpdateUiState {
    data object Idle : PrivateAppUpdateUiState

    data object Checking : PrivateAppUpdateUiState

    data class Available(
        val update: PrivateAvailableAppUpdate,
    ) : PrivateAppUpdateUiState

    data class Downloading(
        val update: PrivateAvailableAppUpdate,
        val downloadedBytes: Long,
        val verifying: Boolean,
    ) : PrivateAppUpdateUiState

    data class ReadyToInstall(
        val update: PrivateAvailableAppUpdate,
        val receipt: PrivateAppUpdateDownloadReceipt,
        val installerRequestId: Long,
        val installerLaunchPending: Boolean,
        val userMessage: String,
    ) : PrivateAppUpdateUiState

    data class Failed(
        val update: PrivateAvailableAppUpdate,
        val userMessage: String,
    ) : PrivateAppUpdateUiState
}

class PrivateAppUpdateViewModel(
    private val updateRepository: PrivateAppUpdateRepository,
    private val updateDownloader: PrivateAppUpdateDownloader,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow<PrivateAppUpdateUiState>(PrivateAppUpdateUiState.Idle)
    private val automaticCheckStarted = AtomicBoolean(false)
    private var downloadJob: Job? = null

    val uiState: StateFlow<PrivateAppUpdateUiState> = mutableUiState.asStateFlow()

    fun checkOnceOnAppOpen() {
        if (!automaticCheckStarted.compareAndSet(false, true)) return
        mutableUiState.value = PrivateAppUpdateUiState.Checking
        viewModelScope.launch {
            mutableUiState.value =
                when (val outcome = updateRepository.checkForNewerCompatibleUpdate()) {
                    is PrivateAppUpdateCheckOutcome.Available -> PrivateAppUpdateUiState.Available(outcome.update)
                    PrivateAppUpdateCheckOutcome.NoCompatibleUpdate,
                    is PrivateAppUpdateCheckOutcome.Failed,
                    -> PrivateAppUpdateUiState.Idle
                }
        }
    }

    fun downloadUpdate() {
        if (downloadJob?.isActive == true) return
        val update =
            when (val state = mutableUiState.value) {
                is PrivateAppUpdateUiState.Available -> state.update
                is PrivateAppUpdateUiState.Failed -> state.update
                else -> return
            }
        mutableUiState.value = PrivateAppUpdateUiState.Downloading(update, downloadedBytes = 0L, verifying = false)
        downloadJob =
            viewModelScope.launch {
                try {
                    updateDownloader.downloadAndVerifyUpdate(update).collect { event ->
                        mutableUiState.value =
                            when (event) {
                                is PrivateAppUpdateDownloadEvent.Progress ->
                                    PrivateAppUpdateUiState.Downloading(
                                        update = event.update,
                                        downloadedBytes = event.downloadedBytes,
                                        verifying = false,
                                    )

                                is PrivateAppUpdateDownloadEvent.Verifying ->
                                    PrivateAppUpdateUiState.Downloading(
                                        update = event.update,
                                        downloadedBytes = event.update.apkByteCount,
                                        verifying = true,
                                    )

                                is PrivateAppUpdateDownloadEvent.Completed ->
                                    PrivateAppUpdateUiState.ReadyToInstall(
                                        update = event.update,
                                        receipt = event.receipt,
                                        installerRequestId = 1L,
                                        installerLaunchPending = true,
                                        userMessage = "Download verified. Opening Android installer…",
                                    )
                            }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    mutableUiState.value =
                        PrivateAppUpdateUiState.Failed(
                            update = update,
                            userMessage = error.message ?: "The update download could not be verified.",
                        )
                } finally {
                    downloadJob = null
                }
            }
    }

    fun dismissUpdate() {
        downloadJob?.cancel()
        downloadJob = null
        mutableUiState.value = PrivateAppUpdateUiState.Idle
    }

    fun requestInstallerLaunch() {
        val ready = mutableUiState.value as? PrivateAppUpdateUiState.ReadyToInstall ?: return
        mutableUiState.value =
            ready.copy(
                installerRequestId = ready.installerRequestId + 1L,
                installerLaunchPending = true,
                userMessage = "Opening Android installer…",
            )
    }

    fun markInstallerLaunchStarted(requestId: Long) {
        val ready = mutableUiState.value as? PrivateAppUpdateUiState.ReadyToInstall ?: return
        if (ready.installerRequestId != requestId || !ready.installerLaunchPending) return
        mutableUiState.value = ready.copy(installerLaunchPending = false)
    }

    fun recordInstallerLaunchOutcome(outcome: PrivateAppInstallerLaunchOutcome) {
        val ready = mutableUiState.value as? PrivateAppUpdateUiState.ReadyToInstall ?: return
        mutableUiState.value =
            when (outcome) {
                PrivateAppInstallerLaunchOutcome.Opened ->
                    ready.copy(userMessage = "Android installer opened. Approve the update to finish.")

                PrivateAppInstallerLaunchOutcome.PermissionRequired ->
                    ready.copy(
                        userMessage =
                            "Allow Synapse Private to install verified updates, return here, then tap Install.",
                    )

                is PrivateAppInstallerLaunchOutcome.Failed -> ready.copy(userMessage = outcome.userMessage)
            }
    }
}
