package app.synapse.localllm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import app.synapse.localllm.ui.SynapseApp
import app.synapse.localllm.ui.SynapseViewModel
import app.synapse.localllm.ui.SynapseViewModelFactory
import app.synapse.localllm.ui.theme.SynapseTheme

class MainActivity : ComponentActivity() {
    private val synapseViewModel: SynapseViewModel by viewModels {
        SynapseViewModelFactory(requireSynapseApplication().graph)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContent {
            SynapseTheme {
                SynapseApp(viewModel = synapseViewModel)
            }
        }
    }

    private fun requireSynapseApplication(): SynapseApplication {
        val currentApplication = application
        check(currentApplication is SynapseApplication) {
            "SynapseApplication is required for MainActivity."
        }
        return currentApplication
    }
}
