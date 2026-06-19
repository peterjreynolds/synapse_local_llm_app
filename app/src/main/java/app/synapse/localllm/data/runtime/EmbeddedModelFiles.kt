package app.synapse.localllm.data.runtime

import android.content.Context
import java.io.File

internal object EmbeddedModelFiles {
    const val MODEL_DIRECTORY_NAME = "models"
    const val DEFAULT_MODEL_FILE_NAME = "synapse-model.gguf"
    const val MINIMUM_IMPORTED_GGUF_BYTES = 1024L * 1024L
    val GGUF_MAGIC = byteArrayOf(0x47, 0x47, 0x55, 0x46)

    fun modelDirectory(context: Context): File =
        File(context.applicationContext.filesDir, MODEL_DIRECTORY_NAME)

    fun sanitizeModelFileName(displayName: String): String {
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

    fun hasGgufMagic(file: File): Boolean {
        if (!file.isFile || file.length() < GGUF_MAGIC.size) return false
        return file.inputStream().use { input ->
            val magic = ByteArray(GGUF_MAGIC.size)
            val bytesRead = input.read(magic)
            bytesRead == GGUF_MAGIC.size && magic.contentEquals(GGUF_MAGIC)
        }
    }
}
