package app.synapse.localllm.data.runtime

import android.content.Context
import app.synapse.localllm.domain.runtime.DownloadModelCommand
import app.synapse.localllm.domain.runtime.ImportEmbeddedModelReceipt
import app.synapse.localllm.domain.runtime.ModelDownloadEvent
import app.synapse.localllm.domain.runtime.ModelDownloader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import okhttp3.Request

class AndroidModelDownloader(
    context: Context,
    private val httpClient: OkHttpClient,
) : ModelDownloader {
    private val applicationContext = context.applicationContext
    private val modelDirectory = EmbeddedModelFiles.modelDirectory(applicationContext)

    override fun downloadModel(command: DownloadModelCommand): Flow<ModelDownloadEvent> =
        flow {
            val entry = command.entry
            require(entry.fileName.endsWith(".gguf", ignoreCase = true)) {
                "Model catalog entry is not a GGUF file."
            }
            require(sha256Pattern.matches(entry.sha256)) {
                "Model catalog entry does not include a valid SHA-256 checksum."
            }

            modelDirectory.mkdirs()
            val targetFileName = EmbeddedModelFiles.sanitizeModelFileName(entry.fileName)
            val targetFile = File(modelDirectory, targetFileName)
            if (targetFile.isVerifiedModel(entry.sizeBytes, entry.sha256)) {
                emit(
                    ModelDownloadEvent.Completed(
                        entry = entry,
                        receipt = targetFile.toImportReceipt(targetFileName),
                    ),
                )
                return@flow
            }

            val partialFile = File(modelDirectory, "$targetFileName.part")
            val resumeBytes = partialFile.resumeByteCount(entry.sizeBytes)
            emit(
                ModelDownloadEvent.Progress(
                    entry = entry,
                    downloadedBytes = resumeBytes,
                    totalBytes = entry.sizeBytes,
                ),
            )

            val response = httpClient.newCall(buildRequest(entry.downloadUrl, resumeBytes)).execute()
            response.use { activeResponse ->
                if (resumeBytes > 0L && activeResponse.code == HTTP_OK) {
                    partialFile.delete()
                }
                if (activeResponse.code !in setOf(HTTP_OK, HTTP_PARTIAL_CONTENT)) {
                    throw IOException("Model download failed with HTTP ${activeResponse.code}.")
                }

                val append = resumeBytes > 0L && activeResponse.code == HTTP_PARTIAL_CONTENT
                var downloadedBytes = if (append) resumeBytes else 0L
                activeResponse.body.byteStream().use { input ->
                    FileOutputStream(partialFile, append).use { output ->
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
                                emit(
                                    ModelDownloadEvent.Progress(
                                        entry = entry,
                                        downloadedBytes = downloadedBytes.coerceAtMost(entry.sizeBytes),
                                        totalBytes = entry.sizeBytes,
                                    ),
                                )
                                bytesSinceProgress = 0L
                            }
                        }
                    }
                }
                emit(
                    ModelDownloadEvent.Progress(
                        entry = entry,
                        downloadedBytes = downloadedBytes.coerceAtMost(entry.sizeBytes),
                        totalBytes = entry.sizeBytes,
                    ),
                )
            }

            emit(
                ModelDownloadEvent.Verifying(
                    entry = entry,
                    downloadedBytes = entry.sizeBytes,
                    totalBytes = entry.sizeBytes,
                ),
            )
            verifyCompletedPartialFile(partialFile, entry.sizeBytes, entry.sha256)
            if (targetFile.exists() && !targetFile.delete()) {
                throw IOException("Could not replace existing model file.")
            }
            if (!partialFile.renameTo(targetFile)) {
                partialFile.copyTo(targetFile, overwrite = true)
                partialFile.delete()
            }

            emit(
                ModelDownloadEvent.Completed(
                    entry = entry,
                    receipt = targetFile.toImportReceipt(targetFileName),
                ),
            )
        }.flowOn(Dispatchers.IO)

    private fun buildRequest(downloadUrl: String, resumeBytes: Long): Request {
        val requestBuilder = Request.Builder()
            .url(downloadUrl)
            .header("Accept", "application/octet-stream")
        if (resumeBytes > 0L) {
            requestBuilder.header("Range", "bytes=$resumeBytes-")
        }
        return requestBuilder.build()
    }

    private fun File.resumeByteCount(expectedBytes: Long): Long {
        if (!isFile) return 0L
        val partialBytes = length()
        return if (partialBytes in 1 until expectedBytes) {
            partialBytes
        } else {
            delete()
            0L
        }
    }

    private fun File.isVerifiedModel(expectedBytes: Long, expectedSha256: String): Boolean =
        isFile &&
            length() == expectedBytes &&
            EmbeddedModelFiles.hasGgufMagic(this) &&
            sha256Hex() == expectedSha256

    private fun verifyCompletedPartialFile(
        partialFile: File,
        expectedBytes: Long,
        expectedSha256: String,
    ) {
        if (!partialFile.isFile || partialFile.length() != expectedBytes) {
            throw IOException("Downloaded GGUF is incomplete.")
        }
        if (!EmbeddedModelFiles.hasGgufMagic(partialFile)) {
            partialFile.delete()
            throw IOException("Downloaded file is not a GGUF model.")
        }
        val actualSha256 = partialFile.sha256Hex()
        if (actualSha256 != expectedSha256) {
            partialFile.delete()
            throw IOException("Downloaded GGUF checksum mismatch.")
        }
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

    private fun File.toImportReceipt(displayName: String): ImportEmbeddedModelReceipt =
        ImportEmbeddedModelReceipt(
            modelPath = absolutePath,
            displayName = displayName,
            byteCount = length(),
        )

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_PARTIAL_CONTENT = 206
        const val END_OF_STREAM = -1
        const val DOWNLOAD_BUFFER_BYTES = 64 * 1024
        const val PROGRESS_EMIT_BYTES = 1024 * 1024
        val sha256Pattern = Regex("^[a-fA-F0-9]{64}$")
    }
}
