package app.synapse.privatechat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.zip.ZipFile

class PrivateChatModuleBoundaryTest {
    private val moduleDirectory =
        File(requireNotNull(System.getProperty("privatechat.moduleDirectory")))

    @Test
    fun `module keeps a separate application identity and no app dependency`() {
        val buildScript = moduleDirectory.resolve("build.gradle.kts").readText()

        assertTrue(buildScript.contains("namespace = \"app.synapse.privatechat\""))
        assertTrue(buildScript.contains("applicationId = \"app.synapse.privatechat\""))
        assertFalse(buildScript.contains("project(\":app\")"))
        assertFalse(buildScript.contains("externalNativeBuild"))
    }

    @Test
    fun `module owns no native build sources or bundled libraries`() {
        val forbiddenNativeExtensions = setOf("c", "cc", "cmake", "cpp", "h", "hpp", "mk", "so")
        val forbiddenNativeArtifacts =
            moduleDirectory
                .resolve("src")
                .walkTopDown()
                .filter(File::isFile)
                .filter { sourceFile ->
                    sourceFile.name == "CMakeLists.txt" ||
                        sourceFile.extension.lowercase() in forbiddenNativeExtensions
                }.map { sourceFile -> sourceFile.relativeTo(moduleDirectory).path }
                .toList()

        assertTrue("Forbidden native source artifacts: $forbiddenNativeArtifacts", forbiddenNativeArtifacts.isEmpty())
    }

    @Test
    fun `resolved runtime dependencies exclude forbidden product systems`() {
        val runtimeDependencies =
            requireNotNull(System.getProperty("privatechat.runtimeDependencies"))
                .lineSequence()
                .filter(String::isNotBlank)
                .toList()
        val forbiddenCoordinates =
            listOf(
                "com.aallam.openai",
                "com.google.firebase",
                "com.google.ai",
                "com.google.mlkit",
                "ai.koog",
                "llama",
                "onnxruntime",
                "pytorch",
                "tensorflow",
                "termux",
            )

        val violations =
            runtimeDependencies.filter { coordinate ->
                forbiddenCoordinates.any { forbidden -> coordinate.contains(forbidden, ignoreCase = true) }
            }
        assertTrue("Forbidden runtime dependencies: $violations", violations.isEmpty())
    }

    @Test
    fun `main sources exclude forbidden participant and model systems`() {
        val sourceFiles =
            moduleDirectory
                .resolve("src/main")
                .walkTopDown()
                .filter(File::isFile)
                .filter { sourceFile -> sourceFile.extension in setOf("kt", "java", "xml") }
                .toList()
        val forbiddenSourcePatterns =
            listOf(
                Regex("\\bCinder\\b", RegexOption.IGNORE_CASE),
                Regex("\\bllama(?:\\.cpp)?\\b", RegexOption.IGNORE_CASE),
                Regex("\\bTermux\\b", RegexOption.IGNORE_CASE),
                Regex("\\bGGML\\b", RegexOption.IGNORE_CASE),
                Regex("\\bGGUF\\b", RegexOption.IGNORE_CASE),
                Regex("\\bSMS[ _-]*auto[ _-]*reply\\b", RegexOption.IGNORE_CASE),
                Regex("\\bAI\\b"),
                Regex("\\bAi[A-Z][A-Za-z0-9_]*\\b"),
                Regex("\\b(?:local|remote)[ _-]*AI\\b", RegexOption.IGNORE_CASE),
                Regex("\\bAI[A-Z][A-Za-z0-9_]*\\b"),
                Regex("\\bAssistant[A-Za-z0-9_]*\\b"),
                Regex("\\bOpenAI[A-Za-z0-9_]*\\b"),
                Regex("\\b(?:model|inference)[ _-]*runtime\\b", RegexOption.IGNORE_CASE),
                Regex("\\b(?:ModelDownload|ModelStore|InferenceEngine|MemoryRepository)[A-Za-z0-9_]*\\b"),
                Regex("app\\.synapse\\.localllm"),
            )

        val violations =
            sourceFiles.flatMap { sourceFile ->
                val sourceText = sourceFile.readText()
                forbiddenSourcePatterns
                    .filter { pattern -> pattern.containsMatchIn(sourceText) }
                    .map { pattern -> "${sourceFile.relativeTo(moduleDirectory)} matched ${pattern.pattern}" }
            }
        assertTrue("Forbidden product source references: $violations", violations.isEmpty())
    }

    @Test
    fun `Android sources never contain a Supabase server credential`() {
        val inspectedFiles =
            sequenceOf(
                moduleDirectory.resolve("build.gradle.kts"),
                moduleDirectory.resolve("src/main/AndroidManifest.xml"),
            ) +
                moduleDirectory
                    .resolve("src/main")
                    .walkTopDown()
                    .filter(File::isFile)
                    .filter { sourceFile -> sourceFile.extension in setOf("kt", "java", "xml") }
        val forbiddenCredentialPatterns =
            listOf(
                Regex("sb" + "_secret_", RegexOption.IGNORE_CASE),
                Regex("service" + "[_-]?role", RegexOption.IGNORE_CASE),
            )
        val violations =
            inspectedFiles
                .flatMap { sourceFile ->
                    val sourceText = sourceFile.readText()
                    forbiddenCredentialPatterns
                        .filter { pattern -> pattern.containsMatchIn(sourceText) }
                        .map { pattern -> "${sourceFile.relativeTo(moduleDirectory)} matched ${pattern.pattern}" }
                }.toList()

        assertTrue("Supabase server credentials must not ship in Android: $violations", violations.isEmpty())
    }

    @Test
    fun `manifest excludes SMS runtime and package installation permissions`() {
        val sourceManifest = moduleDirectory.resolve("src/main/AndroidManifest.xml").readText()
        val mergedManifestFile =
            moduleDirectory.resolve(
                "build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml",
            )
        assertTrue("Merged debug manifest is missing", mergedManifestFile.isFile)
        val mergedManifest = mergedManifestFile.readText()
        val declaredPermissions =
            extractDeclaredPermissions(sourceManifest) + extractDeclaredPermissions(mergedManifest)
        val forbiddenPermissions =
            setOf(
                "android.permission.READ_SMS",
                "android.permission.RECEIVE_MMS",
                "android.permission.RECEIVE_SMS",
                "android.permission.REQUEST_INSTALL_PACKAGES",
                "android.permission.SEND_SMS",
                "com.termux.permission.RUN_COMMAND",
            )

        assertTrue(
            "Forbidden manifest permissions: ${declaredPermissions intersect forbiddenPermissions}",
            (declaredPermissions intersect forbiddenPermissions).isEmpty(),
        )
        assertFalse(mergedManifest.contains("SmsAutoReply"))
        assertFalse(mergedManifest.contains("com.termux"))
    }

    @Test
    fun `debug APK contains only production Signal Android native libraries`() {
        val debugApk = File(requireNotNull(System.getProperty("privatechat.debugApk")))
        assertTrue("Debug APK was not assembled at ${debugApk.path}", debugApk.isFile)

        val nativeArtifacts =
            ZipFile(debugApk).use { archive ->
                Collections
                    .list(archive.entries())
                    .map { entry -> entry.name }
                    .filter { entryName ->
                        entryName.endsWith(".so", ignoreCase = true) ||
                            entryName.endsWith(".dylib", ignoreCase = true) ||
                            entryName.endsWith(".dll", ignoreCase = true)
                    }
            }
        val expectedNativeArtifacts =
            setOf(
                "lib/arm64-v8a/libandroidx.graphics.path.so",
                "lib/arm64-v8a/libsignal_jni.so",
                "lib/armeabi-v7a/libandroidx.graphics.path.so",
                "lib/armeabi-v7a/libsignal_jni.so",
                "lib/x86_64/libandroidx.graphics.path.so",
                "lib/x86_64/libsignal_jni.so",
            )

        assertEquals(
            "Unexpected APK native artifacts",
            expectedNativeArtifacts,
            nativeArtifacts.toSet(),
        )
    }

    private fun extractDeclaredPermissions(manifest: String): Set<String> =
        Regex("""<uses-permission\s+android:name="([^"]+)"""")
            .findAll(manifest)
            .map { match -> match.groupValues[1] }
            .toSet()
}
