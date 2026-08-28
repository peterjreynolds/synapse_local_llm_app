package app.synapse.privatechat.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.synapse.privatechat.domain.update.PrivateAppInstallerLaunchOutcome
import app.synapse.privatechat.domain.update.PrivateAppUpdateDownloadReceipt
import app.synapse.privatechat.ui.account.PrivateAccountAccessScreen
import app.synapse.privatechat.ui.account.PrivateAccountAccessViewModel
import app.synapse.privatechat.ui.account.PrivateAccountSessionGateScreen
import app.synapse.privatechat.ui.account.PrivateAccountSessionUiState
import app.synapse.privatechat.ui.chat.PrivateChatRoute
import app.synapse.privatechat.ui.chat.PrivateChatViewModel
import app.synapse.privatechat.ui.update.PrivateAppUpdateDialog
import app.synapse.privatechat.ui.update.PrivateAppUpdateViewModel

@Composable
fun PrivateChatApp(
    accountAccessViewModel: PrivateAccountAccessViewModel,
    chatViewModel: PrivateChatViewModel,
    appUpdateViewModel: PrivateAppUpdateViewModel,
    onOpenAppInstaller: (PrivateAppUpdateDownloadReceipt) -> PrivateAppInstallerLaunchOutcome,
) {
    val accountAccessState by accountAccessViewModel.uiState.collectAsStateWithLifecycle()
    val appUpdateState by appUpdateViewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(appUpdateViewModel) {
        appUpdateViewModel.checkOnceOnAppOpen()
    }
    when (val session = accountAccessState.session) {
        PrivateAccountSessionUiState.SignedOut ->
            PrivateAccountAccessScreen(
                state = accountAccessState,
                onSelectMode = accountAccessViewModel::selectAccessMode,
                onSubmit = accountAccessViewModel::submitAccountAccess,
                onDismissNotice = accountAccessViewModel::clearSubmissionNotice,
            )

        is PrivateAccountSessionUiState.Active ->
            PrivateChatRoute(
                accountSession = session.receipt,
                viewModel = chatViewModel,
                signOutState = accountAccessState.signOut,
                onSignOut = {
                    accountAccessViewModel.signOutPrivateAccount(chatViewModel::deactivateAccount)
                },
            )

        PrivateAccountSessionUiState.Restoring,
        PrivateAccountSessionUiState.SigningOut,
        PrivateAccountSessionUiState.TransportUnavailable,
        PrivateAccountSessionUiState.LocalStateUnavailable,
        is PrivateAccountSessionUiState.VerificationRejected,
        PrivateAccountSessionUiState.VerificationFailed,
        ->
            PrivateAccountSessionGateScreen(
                state = session,
                onRetry = accountAccessViewModel::retrySessionRestore,
            )
    }
    PrivateAppUpdateDialog(
        state = appUpdateState,
        onDownload = appUpdateViewModel::downloadUpdate,
        onLater = appUpdateViewModel::dismissUpdate,
        onInstall = appUpdateViewModel::requestInstallerLaunch,
        onInstallerLaunchStarted = appUpdateViewModel::markInstallerLaunchStarted,
        onOpenInstaller = onOpenAppInstaller,
        onInstallerLaunchOutcome = appUpdateViewModel::recordInstallerLaunchOutcome,
    )
}
