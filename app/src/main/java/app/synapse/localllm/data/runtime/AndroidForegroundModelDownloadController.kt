package app.synapse.localllm.data.runtime

import android.content.Context
import androidx.core.content.ContextCompat
import app.synapse.localllm.ModelDownloadForegroundService
import app.synapse.localllm.domain.runtime.DownloadModelCommand
import app.synapse.localllm.domain.runtime.ModelDownloadController
import app.synapse.localllm.domain.runtime.ModelDownloadStage
import app.synapse.localllm.domain.runtime.ModelDownloadStartReceipt
import app.synapse.localllm.domain.runtime.ModelDownloadStartStatus
import app.synapse.localllm.domain.runtime.ModelDownloadState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AndroidForegroundModelDownloadController(
    context: Context,
) : ModelDownloadController {
    private val applicationContext = context.applicationContext
    private val mutableModelDownloadState = MutableStateFlow<ModelDownloadState>(ModelDownloadState.Idle)

    override val modelDownloadState: StateFlow<ModelDownloadState> =
        mutableModelDownloadState.asStateFlow()

    override fun startModelDownload(command: DownloadModelCommand): ModelDownloadStartReceipt {
        val activeState = mutableModelDownloadState.value
        if (activeState is ModelDownloadState.Active) {
            return ModelDownloadStartReceipt(
                entryId = activeState.entry.id,
                displayName = activeState.entry.name,
                status = ModelDownloadStartStatus.ALREADY_RUNNING,
                message = "A model download is already running.",
            )
        }

        mutableModelDownloadState.value =
            ModelDownloadState.Active(
                entry = command.entry,
                stage = ModelDownloadStage.STARTING,
                downloadedBytes = 0L,
                totalBytes = command.entry.sizeBytes,
                powerSaveMode = false,
            )
        try {
            ContextCompat.startForegroundService(
                applicationContext,
                ModelDownloadForegroundService.createStartIntent(applicationContext, command.entry),
            )
        } catch (exception: RuntimeException) {
            val message = exception.message ?: "Android refused to start the model download service."
            mutableModelDownloadState.value =
                ModelDownloadState.Failed(
                    entry = command.entry,
                    message = message,
                    downloadedBytes = 0L,
                    totalBytes = command.entry.sizeBytes,
                    powerSaveMode = false,
                )
            return ModelDownloadStartReceipt(
                entryId = command.entry.id,
                displayName = command.entry.name,
                status = ModelDownloadStartStatus.FAILED_TO_START,
                message = message,
            )
        }
        return ModelDownloadStartReceipt(
            entryId = command.entry.id,
            displayName = command.entry.name,
            status = ModelDownloadStartStatus.STARTED,
            message = "Model download started in the background.",
        )
    }

    internal fun publishModelDownloadState(state: ModelDownloadState) {
        mutableModelDownloadState.value = state
    }
}
