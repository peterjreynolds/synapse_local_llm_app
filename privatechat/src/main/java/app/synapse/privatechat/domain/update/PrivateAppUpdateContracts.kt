package app.synapse.privatechat.domain.update

import kotlinx.coroutines.flow.Flow

data class PrivateAvailableAppUpdate(
    val versionCode: Int,
    val versionName: String,
    val minimumAndroidApi: Int,
    val supportedAbis: Set<String>,
    val apkName: String,
    val apkByteCount: Long,
    val apkSha256: String,
    val signerSha256: String,
    val apkDownloadUrl: String,
    val sourceCommit: String,
    val publishedAt: String,
)

sealed interface PrivateAppUpdateCheckOutcome {
    data object NoCompatibleUpdate : PrivateAppUpdateCheckOutcome

    data class Available(
        val update: PrivateAvailableAppUpdate,
    ) : PrivateAppUpdateCheckOutcome

    data class Failed(
        val userMessage: String,
    ) : PrivateAppUpdateCheckOutcome
}

sealed interface PrivateAppUpdateDownloadEvent {
    data class Progress(
        val update: PrivateAvailableAppUpdate,
        val downloadedBytes: Long,
    ) : PrivateAppUpdateDownloadEvent

    data class Verifying(
        val update: PrivateAvailableAppUpdate,
    ) : PrivateAppUpdateDownloadEvent

    data class Completed(
        val update: PrivateAvailableAppUpdate,
        val receipt: PrivateAppUpdateDownloadReceipt,
    ) : PrivateAppUpdateDownloadEvent
}

data class PrivateAppUpdateDownloadReceipt(
    val installerUri: String,
    val displayName: String,
    val byteCount: Long,
    val sha256: String,
    val versionCode: Int,
)

interface PrivateAppUpdateRepository {
    suspend fun checkForNewerCompatibleUpdate(): PrivateAppUpdateCheckOutcome
}

interface PrivateAppUpdateDownloader {
    fun downloadAndVerifyUpdate(update: PrivateAvailableAppUpdate): Flow<PrivateAppUpdateDownloadEvent>
}

sealed interface PrivateAppInstallerLaunchOutcome {
    data object Opened : PrivateAppInstallerLaunchOutcome

    data object PermissionRequired : PrivateAppInstallerLaunchOutcome

    data class Failed(
        val userMessage: String,
    ) : PrivateAppInstallerLaunchOutcome
}
