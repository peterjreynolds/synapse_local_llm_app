package app.synapse.privatechat.data.update

import app.synapse.privatechat.domain.update.PrivateAvailableAppUpdate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant

internal class SynapsePrivateUpdateMetadataParser {
    fun parse(rawMetadata: String): PrivateAvailableAppUpdate {
        require(rawMetadata.toByteArray(Charsets.UTF_8).size <= SynapsePrivateUpdateTrust.MAXIMUM_METADATA_BYTES) {
            "Update metadata exceeds the supported size."
        }
        val metadata = STRICT_JSON.decodeFromString<SynapsePrivateUpdateMetadata>(rawMetadata)
        require(metadata.schemaVersion == SUPPORTED_SCHEMA_VERSION) { "Update metadata schema is unsupported." }
        require(metadata.applicationId == SynapsePrivateUpdateTrust.APPLICATION_ID) {
            "Update metadata targets a different application."
        }
        require(metadata.versionCode in 1..ANDROID_VERSION_CODE_LIMIT) { "Update version code is invalid." }
        require(VERSION_NAME_PATTERN.matches(metadata.versionName)) { "Update version name is invalid." }
        require(metadata.minimumAndroidApi in 1..MAXIMUM_REASONABLE_ANDROID_API) {
            "Update minimum Android API is invalid."
        }
        require(
            metadata.supportedAbis.isNotEmpty() &&
                metadata.supportedAbis.toSet().size == metadata.supportedAbis.size &&
                metadata.supportedAbis.all(SynapsePrivateUpdateTrust.supportedReleaseAbis::contains),
        ) { "Update ABI metadata is invalid." }
        require(metadata.apk.name == SynapsePrivateUpdateTrust.APK_NAME) { "Update APK name is untrusted." }
        require(metadata.apk.downloadUrl == SynapsePrivateUpdateTrust.APK_URL) { "Update APK URL is untrusted." }
        require(metadata.apk.byteCount in 1..SynapsePrivateUpdateTrust.MAXIMUM_APK_BYTES) {
            "Update APK size is invalid."
        }
        require(SHA256_PATTERN.matches(metadata.apk.sha256)) { "Update APK checksum is invalid." }
        require(metadata.apk.signerSha256 == SynapsePrivateUpdateTrust.SIGNER_SHA256) {
            "Update signer metadata is untrusted."
        }
        require(metadata.source.repository == SynapsePrivateUpdateTrust.REPOSITORY) {
            "Update source repository is untrusted."
        }
        require(metadata.source.releaseTag == SynapsePrivateUpdateTrust.RELEASE_TAG) {
            "Update release tag is untrusted."
        }
        require(SOURCE_COMMIT_PATTERN.matches(metadata.source.commit)) { "Update source commit is invalid." }
        require(parsePublishedAt(metadata.publishedAt) != null) { "Update publication time is invalid." }

        return PrivateAvailableAppUpdate(
            versionCode = metadata.versionCode,
            versionName = metadata.versionName,
            minimumAndroidApi = metadata.minimumAndroidApi,
            supportedAbis = metadata.supportedAbis.toSet(),
            apkName = metadata.apk.name,
            apkByteCount = metadata.apk.byteCount,
            apkSha256 = metadata.apk.sha256,
            signerSha256 = metadata.apk.signerSha256,
            apkDownloadUrl = metadata.apk.downloadUrl,
            sourceCommit = metadata.source.commit,
            publishedAt = metadata.publishedAt,
        )
    }

    private fun parsePublishedAt(candidate: String): Instant? {
        if (!candidate.endsWith("Z")) return null
        return runCatching { Instant.parse(candidate) }.getOrNull()
    }

    private companion object {
        const val SUPPORTED_SCHEMA_VERSION = 1
        const val ANDROID_VERSION_CODE_LIMIT = 2_100_000_000
        const val MAXIMUM_REASONABLE_ANDROID_API = 1_000
        val VERSION_NAME_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
        val SHA256_PATTERN = Regex("^[a-f0-9]{64}$")
        val SOURCE_COMMIT_PATTERN = Regex("^[a-f0-9]{40}$")
        val STRICT_JSON = Json { ignoreUnknownKeys = false }
    }
}

@Serializable
private data class SynapsePrivateUpdateMetadata(
    val schemaVersion: Int,
    val applicationId: String,
    val versionCode: Int,
    val versionName: String,
    val minimumAndroidApi: Int,
    val supportedAbis: List<String>,
    val apk: SynapsePrivateUpdateApkMetadata,
    val source: SynapsePrivateUpdateSourceMetadata,
    val publishedAt: String,
)

@Serializable
private data class SynapsePrivateUpdateApkMetadata(
    val name: String,
    @SerialName("byteCount") val byteCount: Long,
    val sha256: String,
    val signerSha256: String,
    val downloadUrl: String,
)

@Serializable
private data class SynapsePrivateUpdateSourceMetadata(
    val repository: String,
    val commit: String,
    val releaseTag: String,
)
