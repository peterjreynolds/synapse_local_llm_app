package app.synapse.privatechat.data.update

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.net.toUri
import app.synapse.privatechat.domain.update.PrivateAppInstallerLaunchOutcome
import app.synapse.privatechat.domain.update.PrivateAppUpdateDownloadReceipt

internal class AndroidPrivateAppInstaller(
    private val context: Context,
) {
    fun openInstaller(receipt: PrivateAppUpdateDownloadReceipt): PrivateAppInstallerLaunchOutcome {
        val installerUri =
            runCatching { receipt.installerUri.toUri() }.getOrNull()
                ?: return PrivateAppInstallerLaunchOutcome.Failed("The verified update file is unavailable.")
        if (
            installerUri.scheme != "content" ||
            installerUri.authority !=
            SynapsePrivateUpdateTrust.APPLICATION_ID + SynapsePrivateUpdateTrust.FILE_PROVIDER_AUTHORITY_SUFFIX ||
            !VERIFIED_UPDATE_URI_PATH.matches(installerUri.path.orEmpty())
        ) {
            return PrivateAppInstallerLaunchOutcome.Failed("The verified update file location is untrusted.")
        }
        if (!context.packageManager.canRequestPackageInstalls()) {
            return openInstallPermissionSettings()
        }
        val installIntent =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(installerUri, APK_MIME_TYPE)
                clipData = ClipData.newRawUri("Synapse Private update", installerUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        return try {
            context.startActivity(installIntent)
            PrivateAppInstallerLaunchOutcome.Opened
        } catch (_: ActivityNotFoundException) {
            PrivateAppInstallerLaunchOutcome.Failed("Android could not open the package installer.")
        } catch (_: SecurityException) {
            PrivateAppInstallerLaunchOutcome.Failed("Android refused access to the verified update file.")
        }
    }

    private fun openInstallPermissionSettings(): PrivateAppInstallerLaunchOutcome {
        val permissionIntent =
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                "package:${context.packageName}".toUri(),
            ).apply {
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        return try {
            context.startActivity(permissionIntent)
            PrivateAppInstallerLaunchOutcome.PermissionRequired
        } catch (_: ActivityNotFoundException) {
            PrivateAppInstallerLaunchOutcome.Failed(
                "Android could not open the permission needed to install this update.",
            )
        }
    }

    private companion object {
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        val VERIFIED_UPDATE_URI_PATH = Regex("^/verified_app_updates/Synapse-Private-[1-9][0-9]*[.]apk$")
    }
}
