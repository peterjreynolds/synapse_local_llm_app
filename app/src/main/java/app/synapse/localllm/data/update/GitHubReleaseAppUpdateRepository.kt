package app.synapse.localllm.data.update

import app.synapse.localllm.domain.update.AppUpdateCheckResult
import app.synapse.localllm.domain.update.AppUpdateRepository
import app.synapse.localllm.domain.update.AvailableAppUpdate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

class GitHubReleaseAppUpdateRepository(
    private val httpClient: OkHttpClient,
    private val currentVersionCode: Int,
    private val deviceAndroidApiLevel: Int,
    private val deviceSupportedAbis: Set<String>,
    private val releaseApiUrl: String = RELEASE_API_URL,
) : AppUpdateRepository {
    override suspend fun checkForAppUpdate(): AppUpdateCheckResult {
        val request = Request.Builder()
            .url(releaseApiUrl)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Synapse-AI-Android")
            .build()

        return runCatching {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return AppUpdateCheckResult.Unavailable(
                        "Update check failed with HTTP ${response.code}. " +
                            "The GitHub release must be public for in-app checks.",
                    )
                }
                val release = json.decodeFromString(GitHubRelease.serializer(), response.body.string())
                val apkAsset = release.assets.firstOrNull { asset -> asset.name == APK_ASSET_NAME }
                    ?: return AppUpdateCheckResult.Unavailable("Release does not include $APK_ASSET_NAME.")
                val releaseVersionCode = parseReleaseVersionCode(release.body)
                    ?: return AppUpdateCheckResult.Unavailable("Release notes do not include a version code.")
                if (releaseVersionCode <= currentVersionCode) {
                    return AppUpdateCheckResult.UpToDate
                }
                val minimumAndroidApi = parseMinimumAndroidApi(release.body)
                    ?: return AppUpdateCheckResult.Unavailable(
                        "Release notes do not include the minimum Android API.",
                    )
                if (deviceAndroidApiLevel < minimumAndroidApi) {
                    return AppUpdateCheckResult.Unavailable(
                        "This update requires Android API $minimumAndroidApi or newer.",
                    )
                }
                val releaseSupportedAbis = parseSupportedAbis(release.body)
                    ?: return AppUpdateCheckResult.Unavailable(
                        "Release notes do not include the supported APK ABIs.",
                    )
                if (releaseSupportedAbis.none(deviceSupportedAbis::contains)) {
                    return AppUpdateCheckResult.Unavailable(
                        "This update does not support this device's ABIs: " +
                            deviceSupportedAbis.sorted().joinToString(", ") + ".",
                    )
                }

                AppUpdateCheckResult.Available(
                    AvailableAppUpdate(
                        versionCode = releaseVersionCode,
                        releaseName = release.name.ifBlank { "Synapse AI APK" },
                        releaseUrl = release.htmlUrl,
                        apkUrl = apkAsset.browserDownloadUrl,
                        apkSha256 = parseAssetSha256(apkAsset.digest) ?: parseReleaseSha256(release.body),
                        byteCount = apkAsset.size.takeIf { size -> size > 0L },
                        minimumAndroidApi = minimumAndroidApi,
                        supportedAbis = releaseSupportedAbis,
                    ),
                )
            }
        }.getOrElse { exception ->
            AppUpdateCheckResult.Unavailable(exception.message ?: "Update check failed.")
        }
    }

    private fun parseReleaseVersionCode(body: String): Int? =
        versionCodePattern.find(body)?.groupValues?.get(1)?.toIntOrNull()

    private fun parseAssetSha256(digest: String?): String? =
        digest
            ?.removePrefix("sha256:")
            ?.takeIf { candidate -> sha256Pattern.matches(candidate) }

    private fun parseReleaseSha256(body: String): String? =
        releaseSha256Pattern.find(body)
            ?.groupValues
            ?.get(1)
            ?.takeIf { candidate -> sha256Pattern.matches(candidate) }

    private fun parseMinimumAndroidApi(body: String): Int? =
        minimumAndroidApiPattern.find(body)?.groupValues?.get(1)?.toIntOrNull()

    private fun parseSupportedAbis(body: String): List<String>? =
        apkAbisPattern.find(body)
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?.trimEnd('.')
            ?.split(',')
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.distinct()
            ?.takeIf(List<String>::isNotEmpty)

    @Serializable
    private data class GitHubRelease(
        val name: String = "",
        val body: String = "",
        val assets: List<GitHubReleaseAsset> = emptyList(),
        @SerialName("html_url") val htmlUrl: String = "",
    )

    @Serializable
    private data class GitHubReleaseAsset(
        val name: String,
        val size: Long = 0L,
        val digest: String? = null,
        @SerialName("browser_download_url") val browserDownloadUrl: String,
    )

    private companion object {
        const val RELEASE_API_URL =
            "https://api.github.com/repos/peterjreynolds/synapse_local_llm_app/releases/tags/synapse-ai"
        const val APK_ASSET_NAME = "Synapse-AI.apk"
        val json = Json { ignoreUnknownKeys = true }
        val versionCodePattern = Regex("Version code:\\s*(\\d+)")
        val minimumAndroidApiPattern = Regex("Minimum Android API:\\s*(\\d+)")
        val apkAbisPattern = Regex("^APK ABIs:\\s*([^\\r\\n]+)$", RegexOption.MULTILINE)
        val releaseSha256Pattern = Regex("SHA-256:\\s*([a-fA-F0-9]{64})")
        val sha256Pattern = Regex("^[a-fA-F0-9]{64}$")
    }
}
