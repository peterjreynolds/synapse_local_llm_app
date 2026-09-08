package app.synapse.privatechat.data.update

import app.synapse.privatechat.domain.update.PrivateAvailableAppUpdate
import java.io.IOException

internal fun verifyDownloadedPrivateApk(
    update: PrivateAvailableAppUpdate,
    inspection: PrivateApkInspection,
    downloadedByteCount: Long,
    downloadedSha256: String,
    installedVersionCode: Int,
    deviceAndroidApi: Int,
    deviceSupportedAbis: Set<String>,
) {
    require(update.apkName == SynapsePrivateUpdateTrust.APK_NAME) { "Update APK name is untrusted." }
    require(update.apkDownloadUrl == SynapsePrivateUpdateTrust.APK_URL) { "Update APK URL is untrusted." }
    require(update.signerSha256 == SynapsePrivateUpdateTrust.SIGNER_SHA256) {
        "Update signer metadata is untrusted."
    }
    require(update.apkByteCount in 1..SynapsePrivateUpdateTrust.MAXIMUM_APK_BYTES) {
        "Update APK size is invalid."
    }
    require(update.versionCode > installedVersionCode) { "Update version is not newer than the installed app." }
    require(update.minimumAndroidApi <= deviceAndroidApi) { "Update requires a newer Android version." }
    require(update.supportedAbis.intersect(deviceSupportedAbis).isNotEmpty()) {
        "Update does not support this device."
    }

    if (downloadedByteCount != update.apkByteCount) throw IOException("Update APK size does not match metadata.")
    if (downloadedSha256 != update.apkSha256) throw IOException("Update APK checksum does not match metadata.")
    if (inspection.applicationId != SynapsePrivateUpdateTrust.APPLICATION_ID) {
        throw IOException("Update APK targets a different application.")
    }
    if (inspection.versionCode != update.versionCode.toLong()) {
        throw IOException("Update APK version code does not match metadata.")
    }
    if (inspection.versionName != update.versionName) {
        throw IOException("Update APK version name does not match metadata.")
    }
    if (inspection.minimumAndroidApi != update.minimumAndroidApi) {
        throw IOException("Update APK minimum Android API does not match metadata.")
    }
    if (inspection.packagedAbis != update.supportedAbis) {
        throw IOException("Update APK ABIs do not match metadata.")
    }
    if (inspection.signerSha256Digests != setOf(update.signerSha256)) {
        throw IOException("Update APK signing certificate is untrusted.")
    }
}
