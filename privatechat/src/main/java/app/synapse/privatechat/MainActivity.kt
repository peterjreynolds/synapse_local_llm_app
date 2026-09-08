package app.synapse.privatechat

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import app.synapse.privatechat.data.update.AndroidPrivateAppInstaller
import app.synapse.privatechat.ui.PrivateChatApp
import app.synapse.privatechat.ui.account.PrivateAccountAccessViewModel
import app.synapse.privatechat.ui.chat.PrivateChatViewModel
import app.synapse.privatechat.ui.theme.SynapsePrivateTheme
import app.synapse.privatechat.ui.update.PrivateAppUpdateViewModel

class MainActivity : ComponentActivity() {
    private val compositionRoot by lazy { PrivateChatCompositionRoot.create(applicationContext) }
    private val accountAccessViewModel: PrivateAccountAccessViewModel by viewModels {
        compositionRoot.accountAccessViewModelFactory
    }
    private val chatViewModel: PrivateChatViewModel by viewModels {
        compositionRoot.chatViewModelFactory
    }
    private val appUpdateViewModel: PrivateAppUpdateViewModel by viewModels {
        compositionRoot.appUpdateViewModelFactory
    }
    private val appInstaller by lazy { AndroidPrivateAppInstaller(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            SynapsePrivateTheme {
                PrivateChatApp(
                    accountAccessViewModel = accountAccessViewModel,
                    chatViewModel = chatViewModel,
                    appUpdateViewModel = appUpdateViewModel,
                    onOpenAppInstaller = appInstaller::openInstaller,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        accountAccessViewModel.onAppForegrounded()
    }

    override fun onStop() {
        accountAccessViewModel.onAppBackgrounded()
        super.onStop()
    }
}
