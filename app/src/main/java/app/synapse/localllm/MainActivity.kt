package app.synapse.localllm

import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.synapse.localllm.ui.AppLockScreen
import app.synapse.localllm.ui.AppLockViewModel
import app.synapse.localllm.ui.AppLockViewModelFactory
import app.synapse.localllm.ui.ChatAppearanceViewModel
import app.synapse.localllm.ui.ChatAppearanceViewModelFactory
import app.synapse.localllm.ui.DirectCallViewModel
import app.synapse.localllm.ui.DirectCallViewModelFactory
import app.synapse.localllm.ui.DirectCallRingtoneViewModel
import app.synapse.localllm.ui.DirectCallRingtoneViewModelFactory
import app.synapse.localllm.ui.OwnerAdminViewModel
import app.synapse.localllm.ui.OwnerAdminViewModelFactory
import app.synapse.localllm.ui.RemoteAccountViewModel
import app.synapse.localllm.ui.RemoteAccountViewModelFactory
import app.synapse.localllm.ui.RemoteChatApp
import app.synapse.localllm.ui.RemoteChatViewModel
import app.synapse.localllm.ui.RemoteChatViewModelFactory
import app.synapse.localllm.ui.RemoteGroupViewModel
import app.synapse.localllm.ui.RemoteGroupViewModelFactory
import app.synapse.localllm.ui.SynapseViewModel
import app.synapse.localllm.ui.SynapseViewModelFactory
import app.synapse.localllm.ui.theme.SynapseTheme

class MainActivity : FragmentActivity() {
    private val appLockViewModel: AppLockViewModel by viewModels {
        AppLockViewModelFactory(requireSynapseApplication().graph)
    }
    private val chatAppearanceViewModel: ChatAppearanceViewModel by viewModels {
        ChatAppearanceViewModelFactory(requireSynapseApplication().graph)
    }
    private val directCallViewModel: DirectCallViewModel by viewModels {
        DirectCallViewModelFactory(requireSynapseApplication().graph)
    }
    private val directCallRingtoneViewModel: DirectCallRingtoneViewModel by viewModels {
        DirectCallRingtoneViewModelFactory(requireSynapseApplication().graph)
    }
    private val localViewModel: SynapseViewModel by viewModels {
        SynapseViewModelFactory(requireSynapseApplication().graph)
    }
    private val remoteViewModel: RemoteChatViewModel by viewModels {
        RemoteChatViewModelFactory(requireSynapseApplication().graph)
    }
    private val remoteAccountViewModel: RemoteAccountViewModel by viewModels {
        RemoteAccountViewModelFactory(requireSynapseApplication().graph)
    }
    private val remoteGroupViewModel: RemoteGroupViewModel by viewModels {
        RemoteGroupViewModelFactory(requireSynapseApplication().graph)
    }
    private val ownerAdminViewModel: OwnerAdminViewModel by viewModels {
        OwnerAdminViewModelFactory(requireSynapseApplication().graph)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consumeTrustedNotificationNavigation()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            SynapseTheme {
                val appLockState by appLockViewModel.uiState.collectAsStateWithLifecycle()
                if (appLockState.isLoading || (appLockState.isEnabled && !appLockState.isUnlocked)) {
                    AppLockScreen(
                        state = appLockState,
                        onUnlock = appLockViewModel::unlock,
                        onResetPin = appLockViewModel::resetPinWithAccountPassword,
                    )
                } else {
                    RemoteChatApp(
                        remoteViewModel = remoteViewModel,
                        remoteAccountViewModel = remoteAccountViewModel,
                        remoteGroupViewModel = remoteGroupViewModel,
                        localViewModel = localViewModel,
                        ownerAdminViewModel = ownerAdminViewModel,
                        appLockState = appLockState,
                        appLockViewModel = appLockViewModel,
                        chatAppearanceViewModel = chatAppearanceViewModel,
                        directCallViewModel = directCallViewModel,
                        directCallRingtoneViewModel = directCallRingtoneViewModel,
                        directCallVideoRendererController = requireSynapseApplication().graph.directCallMediaGateway,
                        requestOwnerIdentityConfirmation = ::requestOwnerIdentityConfirmation,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeTrustedNotificationNavigation()
    }

    override fun onStart() {
        super.onStart()
        requireSynapseApplication().graph.remoteRoomVisibilityTracker.setAppForegrounded(true)
    }

    override fun onStop() {
        appLockViewModel.lock()
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

    private fun consumeTrustedNotificationNavigation() {
        val coordinator = requireSynapseApplication().graph.remoteNotificationNavigationCoordinator
        val roomId = coordinator.consumeRoom()
        remoteViewModel.openNotificationRoom(roomId)
        coordinator.consumeCall()?.let(directCallViewModel::openNotificationCall)
    }

    private fun requestOwnerIdentityConfirmation(onResult: (Boolean) -> Unit) {
        if (!isLocalOwnerConfirmationAvailable()) {
            onResult(true)
            return
        }
        var resultDelivered = false
        fun deliverResult(confirmed: Boolean) {
            if (resultDelivered) return
            resultDelivered = true
            onResult(confirmed)
        }
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    deliverResult(true)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    deliverResult(false)
                }
            },
        )
        val promptBuilder = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Confirm owner action")
            .setSubtitle("Use biometrics or this phone's screen lock")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            promptBuilder.setAllowedAuthenticators(ownerAuthenticators)
        } else {
            @Suppress("DEPRECATION")
            promptBuilder.setDeviceCredentialAllowed(true)
        }
        prompt.authenticate(promptBuilder.build())
    }

    private fun isLocalOwnerConfirmationAvailable(): Boolean {
        val biometricManager = BiometricManager.from(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return biometricManager.canAuthenticate(ownerAuthenticators) == BiometricManager.BIOMETRIC_SUCCESS
        }
        val biometricAvailable = biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_WEAK,
        ) == BiometricManager.BIOMETRIC_SUCCESS
        val deviceCredentialAvailable = getSystemService(KeyguardManager::class.java).isDeviceSecure
        return biometricAvailable || deviceCredentialAvailable
    }

    private companion object {
        val ownerAuthenticators =
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
    }
}
