package app.synapse.localllm

import android.content.pm.ServiceInfo
import android.os.Build

internal fun modelDownloadForegroundServiceType(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
    } else {
        0
    }

internal fun smsAutoReplyForegroundServiceType(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
    } else {
        0
    }
