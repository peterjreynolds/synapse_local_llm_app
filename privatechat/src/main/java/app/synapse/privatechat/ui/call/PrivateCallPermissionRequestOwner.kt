package app.synapse.privatechat.ui.call

/** Android permission results authorize only the exact consent screen that requested them. */
internal class PrivateCallPermissionRequestOwner {
    private var pending: PrivateCallConsentRequest? = null

    fun begin(request: PrivateCallConsentRequest): Boolean {
        if (pending != null) return false
        pending = request
        return true
    }

    fun consumeFor(current: PrivateCallUiState): Boolean {
        val requested = pending ?: return false
        pending = null
        return current is PrivateCallUiState.Consent && current.request === requested
    }
}
