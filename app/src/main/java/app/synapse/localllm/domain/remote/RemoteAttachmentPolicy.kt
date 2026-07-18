package app.synapse.localllm.domain.remote

data class RemoteAttachmentPolicyDecision(
    val displayName: String,
    val mimeType: String,
    val byteCount: Long,
    val kind: RemoteAttachmentKind,
    val durationMillis: Long?,
)

object RemoteAttachmentPolicy {
    fun validate(
        displayName: String,
        mimeType: String,
        byteCount: Long,
        audioDurationMillis: Long? = null,
        isVoiceNote: Boolean = false,
    ): RemoteAttachmentPolicyDecision {
        val normalizedMimeType = canonicalMimeType(mimeType)
        val policy = policies[normalizedMimeType]
            ?: throw IllegalArgumentException("Choose a supported image, document, or audio file.")
        require(byteCount in 1..policy.maximumBytes) {
            "${policy.kind.displayLabel()} attachments must be smaller than ${policy.maximumBytes / MEBIBYTE} MB."
        }
        val kind = if (isVoiceNote) RemoteAttachmentKind.VOICE_NOTE else policy.kind
        if (kind == RemoteAttachmentKind.VOICE_NOTE) {
            require(normalizedMimeType == VOICE_NOTE_MIME_TYPE) { "Voice notes must use AAC audio in an M4A container." }
        }
        val durationMillis = when (kind) {
            RemoteAttachmentKind.AUDIO -> {
                requireNotNull(audioDurationMillis) {
                    "Audio duration must be measured before upload."
                }
            }

            RemoteAttachmentKind.VOICE_NOTE -> requireNotNull(audioDurationMillis)
            else -> null
        }
        durationMillis?.let { duration ->
            require(duration in 1..MAXIMUM_AUDIO_DURATION_MILLIS) {
                "Audio attachments must be no longer than 60 minutes."
            }
        }
        return RemoteAttachmentPolicyDecision(
            displayName = normalizeDisplayName(displayName, policy.canonicalExtension),
            mimeType = normalizedMimeType,
            byteCount = byteCount,
            kind = kind,
            durationMillis = durationMillis,
        )
    }

    fun maximumBytesFor(mimeType: String): Long? = policies[canonicalMimeType(mimeType)]?.maximumBytes

    fun canonicalMimeType(mimeType: String): String = when (val normalized = mimeType.trim().lowercase()) {
        "image/jpg", "image/pjpeg" -> "image/jpeg"
        else -> normalized
    }

    private fun normalizeDisplayName(
        displayName: String,
        canonicalExtension: String,
    ): String {
        val leafName = displayName.substringAfterLast('/').substringAfterLast('\\').trim()
        val stem = leafName
            .substringBeforeLast('.', leafName)
            .replace(CONTROL_CHARACTERS, "")
            .replace(UNSAFE_FILENAME_CHARACTERS, "_")
            .replace(MULTIPLE_WHITESPACE, " ")
            .trim(' ', '.', '_', '-')
            .take(MAXIMUM_DISPLAY_NAME_STEM_LENGTH)
        require(stem.isNotEmpty()) { "The attachment filename is invalid." }
        return "$stem.$canonicalExtension"
    }

    private fun RemoteAttachmentKind.displayLabel(): String = name.lowercase().replaceFirstChar(Char::uppercase)

    private data class Policy(
        val canonicalExtension: String,
        val kind: RemoteAttachmentKind,
        val maximumBytes: Long,
    )

    private val policies = mapOf(
        "application/msword" to Policy("doc", RemoteAttachmentKind.DOCUMENT, 25 * MEBIBYTE),
        "application/pdf" to Policy("pdf", RemoteAttachmentKind.DOCUMENT, 25 * MEBIBYTE),
        "application/rtf" to Policy("rtf", RemoteAttachmentKind.DOCUMENT, 25 * MEBIBYTE),
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to
            Policy("docx", RemoteAttachmentKind.DOCUMENT, 25 * MEBIBYTE),
        "audio/mp4" to Policy("m4a", RemoteAttachmentKind.AUDIO, 25 * MEBIBYTE),
        "audio/mpeg" to Policy("mp3", RemoteAttachmentKind.AUDIO, 25 * MEBIBYTE),
        "audio/ogg" to Policy("ogg", RemoteAttachmentKind.AUDIO, 25 * MEBIBYTE),
        "audio/wav" to Policy("wav", RemoteAttachmentKind.AUDIO, 25 * MEBIBYTE),
        "audio/x-wav" to Policy("wav", RemoteAttachmentKind.AUDIO, 25 * MEBIBYTE),
        "image/gif" to Policy("gif", RemoteAttachmentKind.IMAGE, 15 * MEBIBYTE),
        "image/jpeg" to Policy("jpg", RemoteAttachmentKind.IMAGE, 15 * MEBIBYTE),
        "image/png" to Policy("png", RemoteAttachmentKind.IMAGE, 15 * MEBIBYTE),
        "image/webp" to Policy("webp", RemoteAttachmentKind.IMAGE, 15 * MEBIBYTE),
        "text/csv" to Policy("csv", RemoteAttachmentKind.DOCUMENT, 10 * MEBIBYTE),
        "text/markdown" to Policy("md", RemoteAttachmentKind.DOCUMENT, 10 * MEBIBYTE),
        "text/plain" to Policy("txt", RemoteAttachmentKind.DOCUMENT, 10 * MEBIBYTE),
    )

    private const val MAXIMUM_AUDIO_DURATION_MILLIS = 60 * 60 * 1_000L
    private const val MAXIMUM_DISPLAY_NAME_STEM_LENGTH = 100
    private const val MEBIBYTE = 1024L * 1024L
    private const val VOICE_NOTE_MIME_TYPE = "audio/mp4"
    private val CONTROL_CHARACTERS = Regex("[\\u0000-\\u001f\\u007f]")
    private val MULTIPLE_WHITESPACE = Regex("\\s+")
    private val UNSAFE_FILENAME_CHARACTERS = Regex("[^\\p{L}\\p{N} _.-]+")
}
