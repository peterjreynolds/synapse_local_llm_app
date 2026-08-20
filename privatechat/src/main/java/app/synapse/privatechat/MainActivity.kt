package app.synapse.privatechat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import app.synapse.privatechat.ui.PrivateChatApp
import app.synapse.privatechat.ui.account.PrivateAccountAccessViewModel
import app.synapse.privatechat.ui.theme.SynapsePrivateTheme

class MainActivity : ComponentActivity() {
    private val compositionRoot by lazy(PrivateChatCompositionRoot::create)
    private val accountAccessViewModel: PrivateAccountAccessViewModel by viewModels {
        compositionRoot.accountAccessViewModelFactory
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            SynapsePrivateTheme {
                PrivateChatApp(accountAccessViewModel = accountAccessViewModel)
            }
        }
    }
}
