package app.synapse.localllm.data.update

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import app.synapse.localllm.BuildConfig
import app.synapse.localllm.domain.update.AppUpdateDownloadEvent
import app.synapse.localllm.domain.update.AppUpdateDownloadReceipt
import app.synapse.localllm.domain.update.AppUpdateDownloader
import app.synapse.localllm.domain.update.DownloadAppUpdateCommand
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request

class AndroidAppUpdateDownloader(
    context: Context,
    private val httpClient: OkHttpClient,
    private val trustedApkUrlPrefix: String = TRUSTED_APK_URL_PREFIX,
    private val updateUriFactory: (File) -> Uri = { targetFile ->
        FileProvider.getUriForFile(
            context.applicationContext,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            targetFile,
        )
    },
) : AppUpdateDownloader {
    private val applicationContext = context.applicationContext
    private val updateDirectory = File(applicationContext.cacheDir, UPDATE_CACHE_DIRECTORY)

    override fun downloadAppUpdate(command: DownloadAppUpdateCommand): Flow<AppUpdateDownloadEvent> =
        flow {
            val update = command.update
            require(update.apkUrl.startsWith(trustedApkUrlPrefix)) {
                "Update APK URL is not a trusted Synapse release URL."
            }
            updateDirectory.mkdirs()
            deleteOldUpdateApks(update.versionCode)
            val targetFile = File(updateDirectory, "Synapse-AI-${update.versionCode}.apk")
            val request = Request.Builder()
                .url(update.apkUrl)
                .header("Accept", "application/vnd.android.package-archive")
                .header("User-Agent", "Synapse-AI-Android")
                .build()

            val response = httpClient.newCall(request).execute()
            response.use { activeResponse ->
                if (!activeResponse.isSuccessful) {
                    throw IOException("Update download failed with HTTP ${activeResponse.code}.")
                }
                val totalBytes = update.byteCount
                    ?: activeResponse.body.contentLength().takeIf { contentLength -> contentLength > 0L }
                    ?: 0L
                var downloadedBytes = 0L
                emit(AppUpdateDownloadEvent.Progress(update, downloadedBytes, totalBytes))
                activeResponse.body.byteStream().use { input ->
                    FileOutputStream(targetFile, false).use { output ->
                        val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                        var bytesSinceProgress = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val bytesRead = input.read(buffer)
                            if (bytesRead == END_OF_STREAM) break
                            output.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead
                            bytesSinceProgress += bytesRead
                            if (bytesSinceProgress >= PROGRESS_EMIT_BYTES) {
                                emit(AppUpdateDownloadEvent.Progress(update, downloadedBytes, totalBytes))
                                bytesSinceProgress = 0L
                            }
                        }
                    }
                }
                emit(AppUpdateDownloadEvent.Progress(update, downloadedBytes, totalBytes))
            }

            val actualSha256 = targetFile.sha256Hex()
            val expectedSha256 = update.apkSha256
            if (expectedSha256 != null && !expectedSha256.equals(actualSha256, ignoreCase = true)) {
                targetFile.delete()
                throw IOException("Update APK checksum mismatch.")
            }

            val updateUri = updateUriFactory(targetFile)
            emit(
                AppUpdateDownloadEvent.Completed(
                    update = update,
                    receipt = AppUpdateDownloadReceipt(
                        uri = updateUri.toString(),
                        displayName = targetFile.name,
                        byteCount = targetFile.length(),
                        sha256 = actualSha256,
                    ),
                ),
            )
        }.flowOn(Dispatchers.IO)

    private fun deleteOldUpdateApks(activeVersionCode: Int) {
        updateDirectory
            .listFiles()
            .orEmpty()
            .filter { file -> file.isFile && file.name != "Synapse-AI-$activeVersionCode.apk" }
            .filter { file -> file.extension.equals("apk", ignoreCase = true) }
            .forEach { file -> file.delete() }
    }

    private fun File.sha256Hex(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
            while (true) {
                val bytesRead = input.read(buffer)
                if (bytesRead == END_OF_STREAM) break
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private companion object {
        const val UPDATE_CACHE_DIRECTORY = "app-updates"
        const val TRUSTED_APK_URL_PREFIX =
            "https://github.com/peterjreynolds/synapse_local_llm_app/releases/download/"
        const val DOWNLOAD_BUFFER_BYTES = 64 * 1024
        const val PROGRESS_EMIT_BYTES = 256 * 1024
        const val END_OF_STREAM = -1
    }
}
