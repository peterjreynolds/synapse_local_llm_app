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
    private val modelDirectory = EmbeddedModelFiles.modelDirectory(applicationContext)

    override suspend fun importModel(command: ImportEmbeddedModelCommand): ImportEmbeddedModelReceipt =
        withContext(Dispatchers.IO) {
            val sourceUri = command.sourceUri.toUri()
            val targetName = EmbeddedModelFiles.sanitizeModelFileName(command.displayName)
            val targetFile = File(modelDirectory, targetName)
            modelDirectory.mkdirs()

            applicationContext.contentResolver.openInputStream(sourceUri).use { input ->
                requireNotNull(input) { "Model file could not be opened." }
                val magic = ByteArray(EmbeddedModelFiles.GGUF_MAGIC.size)
                val bytesRead = input.read(magic)
                require(bytesRead == EmbeddedModelFiles.GGUF_MAGIC.size && magic.contentEquals(EmbeddedModelFiles.GGUF_MAGIC)) {
                    "Selected file is not a GGUF model."
                }

                targetFile.outputStream().use { output ->
                    output.write(magic)
                    input.copyTo(output)
                }
            }

            if (!targetFile.isFile || targetFile.length() < EmbeddedModelFiles.MINIMUM_IMPORTED_GGUF_BYTES) {
                targetFile.delete()
                throw IOException("Imported model file is incomplete.")
            }

            ImportEmbeddedModelReceipt(
                modelPath = targetFile.absolutePath,
                displayName = targetName,
                byteCount = targetFile.length(),
            )
        }
}
