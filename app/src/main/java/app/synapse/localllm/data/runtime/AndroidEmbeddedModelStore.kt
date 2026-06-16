package app.synapse.localllm.data.runtime

import android.content.Context
import androidx.core.net.toUri
import app.synapse.localllm.domain.runtime.EmbeddedModelStore
import app.synapse.localllm.domain.runtime.ImportEmbeddedModelCommand
import app.synapse.localllm.domain.runtime.ImportEmbeddedModelReceipt
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidEmbeddedModelStore(
    context: Context,
) : EmbeddedModelStore {
    private val applicationContext = context.applicationContext
    private val modelDirectory = File(applicationContext.filesDir, MODEL_DIRECTORY_NAME)

    override suspend fun importModel(command: ImportEmbeddedModelCommand): ImportEmbeddedModelReceipt =
        withContext(Dispatchers.IO) {
            val sourceUri = command.sourceUri.toUri()
            val targetName = sanitizeModelFileName(command.displayName)
            val targetFile = File(modelDirectory, targetName)
            modelDirectory.mkdirs()

            applicationContext.contentResolver.openInputStream(sourceUri).use { input ->
                requireNotNull(input) { "Model file could not be opened." }
                val magic = ByteArray(GGUF_MAGIC.size)
                val bytesRead = input.read(magic)
                require(bytesRead == GGUF_MAGIC.size && magic.contentEquals(GGUF_MAGIC)) {
                    "Selected file is not a GGUF model."
                }

                targetFile.outputStream().use { output ->
                    output.write(magic)
                    input.copyTo(output)
                }
            }

            if (!targetFile.isFile || targetFile.length() < MINIMUM_GGUF_BYTES) {
                targetFile.delete()
                throw IOException("Imported model file is incomplete.")
            }

            ImportEmbeddedModelReceipt(
                modelPath = targetFile.absolutePath,
                displayName = targetName,
                byteCount = targetFile.length(),
            )
        }

    private fun sanitizeModelFileName(displayName: String): String {
        val sanitized = displayName
            .substringAfterLast('/')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifBlank { DEFAULT_MODEL_FILE_NAME }
        return if (sanitized.endsWith(".gguf", ignoreCase = true)) {
            sanitized
        } else {
            "$sanitized.gguf"
        }
    }

    private companion object {
        const val MODEL_DIRECTORY_NAME = "models"
        const val DEFAULT_MODEL_FILE_NAME = "synapse-model.gguf"
        const val MINIMUM_GGUF_BYTES = 1024L * 1024L
        val GGUF_MAGIC = byteArrayOf(0x47, 0x47, 0x55, 0x46)
    }
}
