package app.synapse.privatechat.data.update

import android.content.Context
import androidx.core.content.FileProvider
import app.synapse.privatechat.domain.update.PrivateAppUpdateDownloadEvent
import app.synapse.privatechat.domain.update.PrivateAppUpdateDownloadReceipt
import app.synapse.privatechat.domain.update.PrivateAppUpdateDownloader
import app.synapse.privatechat.domain.update.PrivateAvailableAppUpdate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

internal class AndroidPrivateAppUpdateDownloader internal constructor(
    private val updateDirectory: File,
    private val transferSource: PrivateUpdateTransferSource,
    private val apkInspector: PrivateApkInspector,
    private val installerUriFactory: (File) -> String,
    private val installedVersionCode: Int,
    private val deviceAndroidApi: Int,
    private val deviceSupportedAbis: Set<String>,
) : PrivateAppUpdateDownloader {
    constructor(
        context: Context,
        transferSource: PrivateUpdateTransferSource,
        installedVersionCode: Int,
        deviceAndroidApi: Int,
        deviceSupportedAbis: Set<String>,
    ) : this(
        updateDirectory = File(context.applicationContext.cacheDir, SynapsePrivateUpdateTrust.UPDATE_CACHE_DIRECTORY),
        transferSource = transferSource,
        apkInspector = AndroidPrivateApkInspector(context.applicationContext),
        installerUriFactory = { apkFile ->
            FileProvider
                .getUriForFile(
                    context.applicationContext,
                    SynapsePrivateUpdateTrust.APPLICATION_ID + SynapsePrivateUpdateTrust.FILE_PROVIDER_AUTHORITY_SUFFIX,
                    apkFile,
                ).toString()
        },
        installedVersionCode = installedVersionCode,
        deviceAndroidApi = deviceAndroidApi,
        deviceSupportedAbis = deviceSupportedAbis,
    )

    override fun downloadAndVerifyUpdate(update: PrivateAvailableAppUpdate): Flow<PrivateAppUpdateDownloadEvent> =
        flow {
            assertTrustedDownloadCandidate(update)
            if (!updateDirectory.exists() && !updateDirectory.mkdirs()) {
                throw IOException("The update download folder could not be created.")
            }
            deleteStaleUpdateFiles(update.versionCode)
            val verifiedApk = File(updateDirectory, "Synapse-Private-${update.versionCode}.apk")
            val partialDownload = File(updateDirectory, "Synapse-Private-${update.versionCode}.download")
            if (partialDownload.exists() && !partialDownload.delete()) {
                throw IOException("A previous update download could not be replaced.")
            }

            try {
                val digest = MessageDigest.getInstance("SHA-256")
                var downloadedBytes = 0L
                emit(PrivateAppUpdateDownloadEvent.Progress(update, downloadedBytes))
                transferSource.openApk(update.apkDownloadUrl).use { response ->
                    response.contentLength?.let { contentLength ->
                        if (contentLength != update.apkByteCount) {
                            throw IOException("Update APK size does not match metadata.")
                        }
                    }
                    FileOutputStream(partialDownload, false).use { output ->
                        val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                        var bytesSinceProgress = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val bytesRead = response.inputStream.read(buffer)
                            if (bytesRead < 0) break
                            downloadedBytes += bytesRead
                            if (
                                downloadedBytes > update.apkByteCount ||
                                downloadedBytes > SynapsePrivateUpdateTrust.MAXIMUM_APK_BYTES
                            ) {
                                throw IOException("Update APK exceeds the verified size.")
                            }
                            output.write(buffer, 0, bytesRead)
                            digest.update(buffer, 0, bytesRead)
                            bytesSinceProgress += bytesRead
                            if (bytesSinceProgress >= PROGRESS_EMIT_BYTES) {
                                emit(PrivateAppUpdateDownloadEvent.Progress(update, downloadedBytes))
                                bytesSinceProgress = 0L
                            }
                        }
                        output.fd.sync()
                    }
                }
                emit(PrivateAppUpdateDownloadEvent.Progress(update, downloadedBytes))
                emit(PrivateAppUpdateDownloadEvent.Verifying(update))
                val downloadedSha256 = digest.digest().toLowerHex()
                val inspection = apkInspector.inspect(partialDownload)
                verifyDownloadedPrivateApk(
                    update = update,
                    inspection = inspection,
                    downloadedByteCount = downloadedBytes,
                    downloadedSha256 = downloadedSha256,
                    installedVersionCode = installedVersionCode,
                    deviceAndroidApi = deviceAndroidApi,
                    deviceSupportedAbis = deviceSupportedAbis,
                )
                replaceVerifiedApk(partialDownload, verifiedApk)
                emit(
                    PrivateAppUpdateDownloadEvent.Completed(
                        update = update,
                        receipt =
                            PrivateAppUpdateDownloadReceipt(
                                installerUri = installerUriFactory(verifiedApk),
                                displayName = verifiedApk.name,
                                byteCount = verifiedApk.length(),
                                sha256 = downloadedSha256,
                                versionCode = update.versionCode,
                            ),
                    ),
                )
            } catch (error: Throwable) {
                partialDownload.delete()
                throw error
            }
        }.flowOn(Dispatchers.IO)

    private fun assertTrustedDownloadCandidate(update: PrivateAvailableAppUpdate) {
        require(update.apkName == SynapsePrivateUpdateTrust.APK_NAME) { "Update APK name is untrusted." }
        require(update.apkDownloadUrl == SynapsePrivateUpdateTrust.APK_URL) { "Update APK URL is untrusted." }
        require(update.signerSha256 == SynapsePrivateUpdateTrust.SIGNER_SHA256) {
            "Update signer metadata is untrusted."
        }
        require(update.apkByteCount in 1..SynapsePrivateUpdateTrust.MAXIMUM_APK_BYTES) {
            "Update APK size is invalid."
        }
    }

    private fun replaceVerifiedApk(
        partialDownload: File,
        verifiedApk: File,
    ) {
        if (verifiedApk.exists() && !verifiedApk.delete()) {
            throw IOException("The previous verified update could not be replaced.")
        }
        if (!partialDownload.renameTo(verifiedApk)) {
            throw IOException("The verified update could not be finalized.")
        }
    }

    private fun deleteStaleUpdateFiles(activeVersionCode: Int) {
        val activePrefix = "Synapse-Private-$activeVersionCode."
        updateDirectory
            .listFiles()
            .orEmpty()
            .filter(File::isFile)
            .filter { file ->
                PRIVATE_UPDATE_CACHE_FILE.matches(file.name) && !file.name.startsWith(activePrefix)
            }.forEach(File::delete)
    }

    private companion object {
        const val DOWNLOAD_BUFFER_BYTES = 64 * 1_024
        const val PROGRESS_EMIT_BYTES = 256 * 1_024
        val PRIVATE_UPDATE_CACHE_FILE = Regex("^Synapse-Private-[1-9][0-9]*[.](?:apk|download)$")
    }
}

private fun ByteArray.toLowerHex(): String {
    val characters = "0123456789abcdef"
    return buildString(size * 2) {
        this@toLowerHex.forEach { byte ->
            val unsignedByte = byte.toInt() and 0xff
            append(characters[unsignedByte ushr 4])
            append(characters[unsignedByte and 0x0f])
        }
    }
}
