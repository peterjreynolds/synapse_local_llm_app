package app.synapse.privatechat.data.call.media

import android.content.Context
import android.view.View

enum class PrivateCallVideoTarget { LOCAL_PREVIEW, REMOTE_PARTICIPANT }

interface PrivateCallVideoRenderer {
    fun createRendererView(
        context: Context,
        target: PrivateCallVideoTarget,
    ): View

    fun releaseRendererView(view: View)
}
