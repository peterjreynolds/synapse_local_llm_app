package app.synapse.privatechat.data.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Collections
import java.util.zip.ZipFile

internal data class PrivateApkInspection(
    val applicationId: String,
    val versionCode: Long,
    val versionName: String,
    val minimumAndroidApi: Int,
    val packagedAbis: Set<String>,
    val signerSha256Digests: Set<String>,
)

internal fun interface PrivateApkInspector {
    fun inspect(apkFile: File): PrivateApkInspection
}

internal class AndroidPrivateApkInspector(
    context: Context,
) : PrivateApkInspector {
    private val packageManager = context.applicationContext.packageManager

    override fun inspect(apkFile: File): PrivateApkInspection {
        val packageInfo =
            readArchivePackageInfo(apkFile)
                ?: throw IOException("Android could not inspect the downloaded update.")
        val applicationInfo =
            packageInfo.applicationInfo
                ?: throw IOException("The downloaded update has no application information.")
        val signers = readArchiveSigners(packageInfo)
        if (signers.isEmpty()) throw IOException("The downloaded update has no signing certificate.")
        return PrivateApkInspection(
            applicationId = packageInfo.packageName,
            versionCode = readArchiveVersionCode(packageInfo),
            versionName = packageInfo.versionName.orEmpty(),
            minimumAndroidApi = applicationInfo.minSdkVersion,
            packagedAbis = readPackagedAbis(apkFile),
            signerSha256Digests = signers.mapTo(mutableSetOf()) { signer -> signer.toByteArray().sha256Hex() },
        )
    }

    private fun readArchivePackageInfo(apkFile: File): PackageInfo? =
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                packageManager.getPackageArchiveInfo(
                    apkFile.absolutePath,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
                )

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> {
                // Exception scope: Android API 28-32 archive inspection. Owner: Synapse Private updater.
                // Removal condition: remove this branch when the module minimum SDK reaches API 33.
                @Suppress("DEPRECATION")
                packageManager.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            }

            else -> {
                // Android 7.1 verifies the APK before exposing its legacy signer array. Keep exact
                // certificate matching in PrivateDownloadedApkVerifier; never trust metadata alone.
                @Suppress("DEPRECATION")
                packageManager.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNATURES)
            }
        }

    private fun readArchiveVersionCode(packageInfo: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }

    private fun readArchiveSigners(packageInfo: PackageInfo): Array<out Signature> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.signingInfo?.apkContentsSigners.orEmpty()
        } else {
            @Suppress("DEPRECATION")
            packageInfo.signatures.orEmpty()
        }

    private fun readPackagedAbis(apkFile: File): Set<String> =
        ZipFile(apkFile).use { archive ->
            Collections
                .list(archive.entries())
                .asSequence()
                .mapNotNull { entry -> NATIVE_LIBRARY_ENTRY.matchEntire(entry.name)?.groupValues?.get(1) }
                .toSet()
        }

    private companion object {
        val NATIVE_LIBRARY_ENTRY = Regex("^lib/([^/]+)/[^/]+[.]so$")
    }
}

internal fun ByteArray.sha256Hex(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(this)
    val hexCharacters = "0123456789abcdef"
    return buildString(digest.size * 2) {
        digest.forEach { byte ->
            val unsignedByte = byte.toInt() and 0xff
            append(hexCharacters[unsignedByte ushr 4])
            append(hexCharacters[unsignedByte and 0x0f])
        }
    }
}
