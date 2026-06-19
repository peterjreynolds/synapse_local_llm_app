package app.synapse.localllm.domain.update

import kotlinx.coroutines.flow.Flow

data class AvailableAppUpdate(
    val versionCode: Int,
    val releaseName: String,
    val releaseUrl: String,
    val apkUrl: String,
    val apkSha256: String?,
    val byteCount: Long?,
)

sealed interface AppUpdateCheckResult {
    data object UpToDate : AppUpdateCheckResult

    data class Available(
        val update: AvailableAppUpdate,
    ) : AppUpdateCheckResult

    data class Unavailable(
        val reason: String,
    ) : AppUpdateCheckResult
}

data class DownloadAppUpdateCommand(
    val update: AvailableAppUpdate,
)

sealed interface AppUpdateDownloadEvent {
    data class Progress(
        val update: AvailableAppUpdate,
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : AppUpdateDownloadEvent

    data class Completed(
        val update: AvailableAppUpdate,
        val receipt: AppUpdateDownloadReceipt,
    ) : AppUpdateDownloadEvent
}

data class AppUpdateDownloadReceipt(
    val uri: String,
    val displayName: String,
    val byteCount: Long,
    val sha256: String?,
)

interface AppUpdateRepository {
    suspend fun checkForAppUpdate(): AppUpdateCheckResult
}

interface AppUpdateDownloader {
    fun downloadAppUpdate(command: DownloadAppUpdateCommand): Flow<AppUpdateDownloadEvent>
}
