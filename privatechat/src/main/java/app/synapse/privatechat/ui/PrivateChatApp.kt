package app.synapse.privatechat.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.synapse.privatechat.ui.account.PrivateAccountAccessScreen
import app.synapse.privatechat.ui.account.PrivateAccountAccessViewModel

@Composable
fun PrivateChatApp(accountAccessViewModel: PrivateAccountAccessViewModel) {
    val accountAccessState by accountAccessViewModel.uiState.collectAsStateWithLifecycle()
    PrivateAccountAccessScreen(
        state = accountAccessState,
        onSelectMode = accountAccessViewModel::selectAccessMode,
        onSubmit = accountAccessViewModel::submitAccountAccess,
        onDismissNotice = accountAccessViewModel::clearSubmissionNotice,
    )
}
