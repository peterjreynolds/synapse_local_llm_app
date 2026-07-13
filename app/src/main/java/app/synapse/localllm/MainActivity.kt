package app.synapse.localllm

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import app.synapse.localllm.ui.RemoteChatApp
import app.synapse.localllm.ui.RemoteChatViewModel
import app.synapse.localllm.ui.RemoteChatViewModelFactory
import app.synapse.localllm.ui.SynapseViewModel
import app.synapse.localllm.ui.SynapseViewModelFactory
import app.synapse.localllm.ui.theme.SynapseTheme

class MainActivity : ComponentActivity() {
    private val localViewModel: SynapseViewModel by viewModels {
        SynapseViewModelFactory(requireSynapseApplication().graph)
    }
    private val remoteViewModel: RemoteChatViewModel by viewModels {
        RemoteChatViewModelFactory(requireSynapseApplication().graph)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consumeTrustedNotificationRoom()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            SynapseTheme {
                RemoteChatApp(
                    remoteViewModel = remoteViewModel,
                    localViewModel = localViewModel,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeTrustedNotificationRoom()
    }

    override fun onStart() {
        super.onStart()
        requireSynapseApplication().graph.remoteRoomVisibilityTracker.setAppForegrounded(true)
    }

    override fun onStop() {
        requireSynapseApplication().graph.remoteRoomVisibilityTracker.setAppForegrounded(false)
        super.onStop()
    }

    private fun requireSynapseApplication(): SynapseApplication {
        val currentApplication = application
        check(currentApplication is SynapseApplication) {
            "SynapseApplication is required for MainActivity."
        }
        return currentApplication
    }

    private fun consumeTrustedNotificationRoom() {
        val roomId = requireSynapseApplication()
            .graph
            .remoteNotificationNavigationCoordinator
            .consumeRoom()
        remoteViewModel.openNotificationRoom(roomId)
    }
}
