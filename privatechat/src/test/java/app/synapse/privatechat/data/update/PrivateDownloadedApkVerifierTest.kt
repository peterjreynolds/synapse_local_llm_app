package app.synapse.privatechat.data.update

import app.synapse.privatechat.domain.update.PrivateAvailableAppUpdate
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class PrivateDownloadedApkVerifierTest {
    @Test
    fun `accepts a newer APK only when every metadata and package fact matches`() {
        verifyDownloadedPrivateApk(
            update = testUpdate(),
            inspection = testInspection(),
            downloadedByteCount = 8_192L,
            downloadedSha256 = "a".repeat(64),
            installedVersionCode = 2037,
            deviceAndroidApi = 35,
            deviceSupportedAbis = setOf("arm64-v8a"),
        )
    }

    @Test
    fun `rejects a different package version API ABI or signer`() {
        val update = testUpdate()
        val invalidInspections =
            listOf(
                testInspection().copy(applicationId = "example.other.app"),
                testInspection().copy(versionCode = 2039),
                testInspection().copy(versionName = "wrong"),
                testInspection().copy(minimumAndroidApi = 29),
                testInspection().copy(packagedAbis = setOf("arm64-v8a")),
                testInspection().copy(signerSha256Digests = setOf("f".repeat(64))),
            )

        invalidInspections.forEach { inspection ->
            assertThrows(IOException::class.java) {
                verifyDownloadedPrivateApk(
                    update = update,
                    inspection = inspection,
                    downloadedByteCount = update.apkByteCount,
                    downloadedSha256 = update.apkSha256,
                    installedVersionCode = 2037,
                    deviceAndroidApi = 35,
                    deviceSupportedAbis = setOf("arm64-v8a"),
                )
            }
        }
    }
}

internal fun testUpdate(
    apkByteCount: Long = 8_192L,
    apkSha256: String = "a".repeat(64),
): PrivateAvailableAppUpdate =
    PrivateAvailableAppUpdate(
        versionCode = 2038,
        versionName = "0.1.2038",
        minimumAndroidApi = 28,
        supportedAbis = setOf("arm64-v8a", "armeabi-v7a", "x86_64"),
        apkName = SynapsePrivateUpdateTrust.APK_NAME,
        apkByteCount = apkByteCount,
        apkSha256 = apkSha256,
        signerSha256 = SynapsePrivateUpdateTrust.SIGNER_SHA256,
        apkDownloadUrl = SynapsePrivateUpdateTrust.APK_URL,
        sourceCommit = "c".repeat(40),
        publishedAt = "2026-08-27T20:15:30Z",
    )

internal fun testInspection(): PrivateApkInspection =
    PrivateApkInspection(
        applicationId = SynapsePrivateUpdateTrust.APPLICATION_ID,
        versionCode = 2038,
        versionName = "0.1.2038",
        minimumAndroidApi = 28,
        packagedAbis = setOf("arm64-v8a", "armeabi-v7a", "x86_64"),
        signerSha256Digests = setOf(SynapsePrivateUpdateTrust.SIGNER_SHA256),
    )
