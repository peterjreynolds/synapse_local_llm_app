package app.synapse.localllm.data.library

import app.synapse.localllm.domain.ids.LibraryArtifactId
import java.io.File
import java.text.Normalizer
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

class LibraryWorkspacePaths(
    filesDirectory: File,
    cacheDirectory: File,
) {
    private val filesRoot = filesDirectory.canonicalFile
    private val libraryRoot = File(filesRoot, LIBRARY_DIRECTORY_NAME).canonicalFile
    private val workspaceRoot = File(libraryRoot, WORKSPACE_DIRECTORY_NAME).canonicalFile
    private val cacheRoot = cacheDirectory.canonicalFile
    private val pdfExportRoot = File(cacheRoot, PDF_EXPORT_DIRECTORY_NAME).canonicalFile

    fun planMarkdownArtifact(
        artifactId: LibraryArtifactId,
        title: String,
    ): LibraryFilePlan {
        val artifactDirectoryName = sanitizePathToken(
            rawToken = artifactId.raw,
            fallback = "artifact",
            maxLength = ID_DIRECTORY_LIMIT,
        )
        val displayName = "${buildSafeFileStem(title)}.md"
        val artifactFile = File(File(workspaceRoot, artifactDirectoryName), displayName).canonicalFile
        requireOwnedFile(workspaceRoot, artifactFile)
        return LibraryFilePlan(
            file = artifactFile,
            displayName = displayName,
            relativePath = artifactFile.toUnixRelativePathFrom(filesRoot),
        )
    }

    fun planPdfExport(
        title: String,
        createdAt: Instant,
    ): LibraryFilePlan {
        val createdAtStamp = DateTimeFormatter.ISO_INSTANT
            .format(createdAt)
            .replace(":", "-")
        val displayName = "${buildSafeFileStem(title)}-$createdAtStamp.pdf"
        val exportFile = File(pdfExportRoot, displayName).canonicalFile
        requireOwnedFile(pdfExportRoot, exportFile)
        return LibraryFilePlan(
            file = exportFile,
            displayName = displayName,
            relativePath = exportFile.toUnixRelativePathFrom(cacheRoot),
        )
    }

    fun resolveWorkspaceArtifactFile(relativePath: String): File {
        require(relativePath.isNotBlank()) { "Library artifact path cannot be blank." }
        val artifactFile = File(filesRoot, relativePath).canonicalFile
        requireOwnedFile(workspaceRoot, artifactFile)
        return artifactFile
    }

    private fun File.toUnixRelativePathFrom(root: File): String {
        val rootPath = root.canonicalFile.toPath()
        val filePath = canonicalFile.toPath()
        require(filePath.startsWith(rootPath)) {
            "Planned Synapse artifact path escaped its owner directory."
        }
        return rootPath.relativize(filePath).joinToString("/") { pathPart ->
            pathPart.toString()
        }
    }

    private fun requireOwnedFile(
        ownerDirectory: File,
        plannedFile: File,
    ) {
        val ownerPath = ownerDirectory.canonicalFile.toPath()
        val plannedPath = plannedFile.canonicalFile.toPath()
        require(plannedPath.startsWith(ownerPath)) {
            "Planned Synapse artifact path escaped its owner directory."
        }
    }

    companion object {
        private const val LIBRARY_DIRECTORY_NAME = "library"
        private const val WORKSPACE_DIRECTORY_NAME = "workspace"
        private const val PDF_EXPORT_DIRECTORY_NAME = "library-exports/pdf"
        private const val FILE_STEM_LIMIT = 72
        private const val ID_DIRECTORY_LIMIT = 96
        private val whitespace = Regex("\\s+")
        private val pathSeparators = Regex("[/\\\\]+")
        private val pathTraversalDots = Regex("\\.{2,}")
        private val combiningMarks = Regex("\\p{M}+")
        private val unsafeTokenCharacters = Regex("[^a-z0-9_-]+")
        private val repeatedSeparators = Regex("[-_]{2,}")

        fun normalizeArtifactTitle(rawTitle: String): String {
            val normalizedTitle = stripMarkdownExtension(
                rawTitle
                    .trim()
                    .replace(pathSeparators, " ")
                    .replace(pathTraversalDots, " ")
                    .replace(whitespace, " "),
            ).trim()
            require(normalizedTitle.isNotBlank()) { "Library artifact title cannot be blank." }
            return normalizedTitle.take(160).trimEnd()
        }

        fun normalizeCatalogSummary(rawSummary: String?): String? =
            rawSummary
                ?.trim()
                ?.replace(whitespace, " ")
                ?.takeIf { summary -> summary.isNotBlank() }
                ?.take(320)
                ?.trimEnd()

        fun normalizeTags(rawTags: List<String>): List<String> =
            rawTags
                .mapNotNull { rawTag ->
                    sanitizePathToken(
                        rawToken = rawTag,
                        fallback = "",
                        maxLength = 48,
                    ).takeIf { tag -> tag.isNotBlank() }
                }
                .distinct()
                .take(24)

        fun buildSafeFileStem(rawTitle: String): String =
            sanitizePathToken(
                rawToken = stripMarkdownExtension(normalizeArtifactTitle(rawTitle)),
                fallback = "untitled",
                maxLength = FILE_STEM_LIMIT,
            )

        private fun stripMarkdownExtension(title: String): String =
            when {
                title.endsWith(".markdown", ignoreCase = true) -> title.dropLast(".markdown".length)
                title.endsWith(".md", ignoreCase = true) -> title.dropLast(".md".length)
                else -> title
            }

        private fun sanitizePathToken(
            rawToken: String,
            fallback: String,
            maxLength: Int,
        ): String {
            val asciiToken = Normalizer.normalize(rawToken, Normalizer.Form.NFKD)
                .replace(combiningMarks, "")
                .lowercase(Locale.US)
                .replace(whitespace, "-")
                .replace(unsafeTokenCharacters, "-")
                .replace(repeatedSeparators, "-")
                .trim('-', '.', '_')
                .take(maxLength)
                .trim('-', '.', '_')
            return when (asciiToken) {
                "", ".", ".." -> fallback
                else -> asciiToken
            }
        }
    }
}

data class LibraryFilePlan(
    val file: File,
    val displayName: String,
    val relativePath: String,
)
