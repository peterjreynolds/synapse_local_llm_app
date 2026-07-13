package app.synapse.localllm

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class RemoteNotificationOpenActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val queued = requireSynapseApplication()
            .graph
            .remoteNotificationNavigationCoordinator
            .queueRoom(intent.getStringExtra(EXTRA_REMOTE_ROOM_ID))
        if (queued) {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
        }
        finish()
    }

    private fun requireSynapseApplication(): SynapseApplication {
        val currentApplication = application
        check(currentApplication is SynapseApplication) {
            "SynapseApplication is required for remote notification navigation."
        }
        return currentApplication
    }
}
