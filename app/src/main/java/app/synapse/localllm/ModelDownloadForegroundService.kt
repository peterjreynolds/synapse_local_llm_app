package app.synapse.localllm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.synapse.localllm.domain.runtime.DownloadModelCommand
import app.synapse.localllm.domain.runtime.ModelCatalogEntry
import app.synapse.localllm.domain.runtime.ModelDownloadEvent
import app.synapse.localllm.domain.runtime.ModelDownloadStage
import app.synapse.localllm.domain.runtime.ModelDownloadState
import app.synapse.localllm.domain.runtime.ModelPromptProfile
import app.synapse.localllm.domain.runtime.formatModelDownloadProgressText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ModelDownloadForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeDownloadJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val entry = parseStartIntent(intent)
        if (entry == null) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        ensureNotificationChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildProgressNotification(
                entry = entry,
                stage = ModelDownloadStage.STARTING,
                downloadedBytes = 0L,
                totalBytes = entry.sizeBytes,
            ),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )

        activeDownloadJob?.cancel()
        activeDownloadJob = serviceScope.launch {
            runModelDownload(entry = entry, startId = startId)
        }

        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private suspend fun runModelDownload(entry: ModelCatalogEntry, startId: Int) {
        val graph = requireSynapseApplication().graph
        val controller = graph.modelDownloadController
        var downloadedBytes = 0L
        var totalBytes = entry.sizeBytes
        try {
            graph.modelDownloader.downloadModel(DownloadModelCommand(entry)).collect { event ->
                when (event) {
                    is ModelDownloadEvent.Progress -> {
                        downloadedBytes = event.downloadedBytes
                        totalBytes = event.totalBytes
                        controller.publishModelDownloadState(
                            ModelDownloadState.Active(
                                entry = event.entry,
                                stage = ModelDownloadStage.DOWNLOADING,
                                downloadedBytes = downloadedBytes,
                                totalBytes = totalBytes,
                                powerSaveMode = isPowerSaveMode(),
                            ),
                        )
                        notifyProgress(
                            entry = event.entry,
                            stage = ModelDownloadStage.DOWNLOADING,
                            downloadedBytes = downloadedBytes,
                            totalBytes = totalBytes,
                        )
                    }

                    is ModelDownloadEvent.Verifying -> {
                        downloadedBytes = event.downloadedBytes
                        totalBytes = event.totalBytes
                        controller.publishModelDownloadState(
                            ModelDownloadState.Active(
                                entry = event.entry,
                                stage = ModelDownloadStage.VERIFYING,
                                downloadedBytes = downloadedBytes,
                                totalBytes = totalBytes,
                                powerSaveMode = isPowerSaveMode(),
                            ),
                        )
                        notifyProgress(
                            entry = event.entry,
                            stage = ModelDownloadStage.VERIFYING,
                            downloadedBytes = downloadedBytes,
                            totalBytes = totalBytes,
                        )
                    }

                    is ModelDownloadEvent.Completed -> {
                        graph.settingsStore.updateEmbeddedModel(
                            modelPath = event.receipt.modelPath,
                            displayName = event.receipt.displayName,
                            byteCount = event.receipt.byteCount,
                            modelPromptProfile = event.entry.promptProfile,
                        )
                        controller.publishModelDownloadState(
                            ModelDownloadState.Completed(
                                entry = event.entry,
                                receipt = event.receipt,
                            ),
                        )
                        notifyCompleted(event.entry)
                        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
                        stopSelfResult(startId)
                    }
                }
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            controller.publishModelDownloadState(
                ModelDownloadState.Failed(
                    entry = entry,
                    message = exception.message ?: "Model download failed.",
                    downloadedBytes = downloadedBytes,
                    totalBytes = totalBytes,
                    powerSaveMode = isPowerSaveMode(),
                ),
            )
            notifyFailed(entry = entry, message = exception.message ?: "Model download failed.")
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
            stopSelfResult(startId)
        }
    }

    private fun notifyProgress(
        entry: ModelCatalogEntry,
        stage: ModelDownloadStage,
        downloadedBytes: Long,
        totalBytes: Long,
    ) {
        notificationManager().notify(
            NOTIFICATION_ID,
            buildProgressNotification(entry, stage, downloadedBytes, totalBytes),
        )
    }

    private fun notifyCompleted(entry: ModelCatalogEntry) {
        notificationManager().notify(
            NOTIFICATION_ID,
            buildTerminalNotification(
                title = "Model ready",
                text = "${entry.name} downloaded and selected.",
                icon = android.R.drawable.stat_sys_download_done,
            ),
        )
    }

    private fun notifyFailed(entry: ModelCatalogEntry, message: String) {
        notificationManager().notify(
            NOTIFICATION_ID,
            buildTerminalNotification(
                title = "Model download failed",
                text = "${entry.name}: $message",
                icon = android.R.drawable.stat_notify_error,
            ),
        )
    }

    private fun buildProgressNotification(
        entry: ModelCatalogEntry,
        stage: ModelDownloadStage,
        downloadedBytes: Long,
        totalBytes: Long,
    ): Notification {
        val progressMax = NOTIFICATION_PROGRESS_MAX
        val progress = if (totalBytes > 0L) {
            ((downloadedBytes.toDouble() / totalBytes.toDouble()).coerceIn(0.0, 1.0) * progressMax)
                .toInt()
        } else {
            0
        }
        val stageText = when (stage) {
            ModelDownloadStage.STARTING -> "Starting"
            ModelDownloadStage.DOWNLOADING -> "Downloading"
            ModelDownloadStage.VERIFYING -> "Verifying GGUF"
            ModelDownloadStage.COMPLETED -> "Completed"
            ModelDownloadStage.FAILED -> "Failed"
        }
        val powerText = if (isPowerSaveMode()) {
            " Battery saver may pause this."
        } else {
            ""
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("$stageText ${entry.name}")
            .setContentText(formatModelDownloadProgressText(downloadedBytes, totalBytes) + powerText)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(formatModelDownloadProgressText(downloadedBytes, totalBytes) + powerText),
            )
            .setContentIntent(openAppPendingIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(progressMax, progress, stage == ModelDownloadStage.VERIFYING)
            .build()
    }

    private fun buildTerminalNotification(
        title: String,
        text: String,
        icon: Int,
    ): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppPendingIntent())
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun openAppPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Model downloads",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows active local GGUF model downloads."
        }
        notificationManager().createNotificationChannel(channel)
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(NotificationManager::class.java)

    private fun isPowerSaveMode(): Boolean =
        getSystemService(PowerManager::class.java).isPowerSaveMode

    private fun requireSynapseApplication(): SynapseApplication {
        val currentApplication = application
        check(currentApplication is SynapseApplication) {
            "SynapseApplication is required for model downloads."
        }
        return currentApplication
    }

    companion object {
        private const val ACTION_DOWNLOAD_MODEL = "app.synapse.localllm.action.DOWNLOAD_MODEL"
        private const val CHANNEL_ID = "synapse_model_downloads"
        private const val NOTIFICATION_ID = 4101
        private const val NOTIFICATION_PROGRESS_MAX = 1000

        private const val EXTRA_ID = "entry_id"
        private const val EXTRA_NAME = "entry_name"
        private const val EXTRA_FILE_NAME = "entry_file_name"
        private const val EXTRA_SIZE_BYTES = "entry_size_bytes"
        private const val EXTRA_DOWNLOAD_URL = "entry_download_url"
        private const val EXTRA_SHA256 = "entry_sha256"
        private const val EXTRA_PROMPT_PROFILE = "entry_prompt_profile"
        private const val EXTRA_COMPATIBILITY_NOTES = "entry_compatibility_notes"
        private const val EXTRA_SOURCE_LABEL = "entry_source_label"
        private const val EXTRA_RECOMMENDED = "entry_recommended"

        fun createStartIntent(context: Context, entry: ModelCatalogEntry): Intent =
            Intent(context, ModelDownloadForegroundService::class.java)
                .setAction(ACTION_DOWNLOAD_MODEL)
                .putExtra(EXTRA_ID, entry.id)
                .putExtra(EXTRA_NAME, entry.name)
                .putExtra(EXTRA_FILE_NAME, entry.fileName)
                .putExtra(EXTRA_SIZE_BYTES, entry.sizeBytes)
                .putExtra(EXTRA_DOWNLOAD_URL, entry.downloadUrl)
                .putExtra(EXTRA_SHA256, entry.sha256)
                .putExtra(EXTRA_PROMPT_PROFILE, entry.promptProfile.name)
                .putExtra(EXTRA_COMPATIBILITY_NOTES, entry.compatibilityNotes)
                .putExtra(EXTRA_SOURCE_LABEL, entry.sourceLabel)
                .putExtra(EXTRA_RECOMMENDED, entry.recommended)

        fun parseStartIntent(intent: Intent?): ModelCatalogEntry? {
            if (intent?.action != ACTION_DOWNLOAD_MODEL) return null
            val promptProfile = intent.getStringExtra(EXTRA_PROMPT_PROFILE)
                ?.let { rawProfile -> runCatching { ModelPromptProfile.valueOf(rawProfile) }.getOrNull() }
                ?: return null
            val id = intent.getNonBlankStringExtra(EXTRA_ID) ?: return null
            val name = intent.getNonBlankStringExtra(EXTRA_NAME) ?: return null
            val fileName = intent.getNonBlankStringExtra(EXTRA_FILE_NAME) ?: return null
            val downloadUrl = intent.getNonBlankStringExtra(EXTRA_DOWNLOAD_URL) ?: return null
            val sha256 = intent.getNonBlankStringExtra(EXTRA_SHA256) ?: return null
            val compatibilityNotes = intent.getNonBlankStringExtra(EXTRA_COMPATIBILITY_NOTES) ?: return null
            val sourceLabel = intent.getNonBlankStringExtra(EXTRA_SOURCE_LABEL) ?: return null
            val sizeBytes = intent.getLongExtra(EXTRA_SIZE_BYTES, -1L)
            if (sizeBytes <= 0L) return null

            return ModelCatalogEntry(
                id = id,
                name = name,
                fileName = fileName,
                sizeBytes = sizeBytes,
                downloadUrl = downloadUrl,
                sha256 = sha256,
                promptProfile = promptProfile,
                compatibilityNotes = compatibilityNotes,
                sourceLabel = sourceLabel,
                recommended = intent.getBooleanExtra(EXTRA_RECOMMENDED, false),
            )
        }

        private fun Intent.getNonBlankStringExtra(key: String): String? =
            getStringExtra(key)?.trim()?.takeIf { value -> value.isNotBlank() }
    }
}
