package app.synapse.localllm.data.calling

import android.content.Context
import android.view.View

enum class DirectCallVideoRendererTarget {
    LOCAL_PREVIEW,
    REMOTE_PARTICIPANT,
}

interface DirectCallVideoRendererController {
    fun createRendererView(
        context: Context,
        target: DirectCallVideoRendererTarget,
    ): View

    fun releaseRendererView(view: View)
}
