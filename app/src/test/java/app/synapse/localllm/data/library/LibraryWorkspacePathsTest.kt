package app.synapse.localllm.data.library

import app.synapse.localllm.domain.ids.LibraryArtifactId
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryWorkspacePathsTest {
    @Test
    fun markdownArtifactPlanSanitizesDangerousTitlesInsideWorkspaceRoot() {
        val filesDirectory = Files.createTempDirectory("synapse-files").toFile()
        val cacheDirectory = Files.createTempDirectory("synapse-cache").toFile()
        val paths = LibraryWorkspacePaths(filesDirectory, cacheDirectory)

        val filePlan = paths.planMarkdownArtifact(
            artifactId = LibraryArtifactId("artifact-123"),
            title = "../../Peter's Plan: Draft?.md",
        )

        assertEquals("peter-s-plan-draft.md", filePlan.displayName)
        assertTrue(filePlan.relativePath.startsWith("library/workspace/artifact-123/"))
        assertFalse(filePlan.relativePath.contains(".."))
        assertTrue(
            filePlan.file.canonicalPath.startsWith(
                filesDirectory.resolve("library/workspace").canonicalPath,
            ),
        )
    }

    @Test
    fun tagsAreNormalizedForCatalogLookup() {
        val tags = LibraryWorkspacePaths.normalizeTags(
            listOf(" AI Readiness ", "AI Readiness", "Speaker Diarization!", "", "../bad"),
        )

        assertEquals(listOf("ai-readiness", "speaker-diarization", "bad"), tags)
    }
}
