package app.synapse.privatechat.ui.connection

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.synapse.privatechat.data.connection.PrivateBackgroundConnection
import app.synapse.privatechat.data.connection.PrivateConnectionForegroundService

@Composable
internal fun PrivateBackgroundConnectionButton(connection: PrivateBackgroundConnection) {
    val context = LocalContext.current
    val enabled by connection.enabled.collectAsStateWithLifecycle()
    val serviceNotice by connection.failureNotice.collectAsStateWithLifecycle()
    var notice by remember { mutableStateOf<String?>(null) }
    val start = {
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, PrivateConnectionForegroundService::class.java).setAction(PrivateConnectionForegroundService.ACTION_START),
            )
            notice = null
        } catch (_: RuntimeException) {
            notice = "Background connection could not start. Try again while the app is open."
        }
    }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) start() else notice = "Allow notifications to keep a visible background connection."
        }
    TextButton(onClick = {
        if (enabled) {
            context.stopService(Intent(context, PrivateConnectionForegroundService::class.java))
        } else if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            start()
        }
    }) {
        Text(if (enabled) "Background connection on · Stop" else "Stay connected in background")
    }
    (notice ?: serviceNotice)?.let { Text(it) }
}
