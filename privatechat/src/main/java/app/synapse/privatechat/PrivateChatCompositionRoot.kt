package app.synapse.privatechat

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.synapse.privatechat.data.account.PendingTransportPrivateAccountGateway
import app.synapse.privatechat.domain.account.PrivateAccountGateway
import app.synapse.privatechat.ui.account.PrivateAccountAccessViewModel

class PrivateChatCompositionRoot private constructor(
    accountGateway: PrivateAccountGateway,
) {
    val accountAccessViewModelFactory: ViewModelProvider.Factory =
        viewModelFactory {
            initializer {
                PrivateAccountAccessViewModel(accountGateway)
            }
        }

    companion object {
        fun create(): PrivateChatCompositionRoot =
            PrivateChatCompositionRoot(
                accountGateway = PendingTransportPrivateAccountGateway,
            )
    }
}
