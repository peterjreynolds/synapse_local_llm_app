package app.synapse.localllm.data.calling

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import app.synapse.localllm.domain.calling.DirectCallTerminalNotificationStore
import app.synapse.localllm.domain.remote.RemoteDirectCallId

class AndroidDirectCallTerminalNotificationStore private constructor(
    private val preferences: SharedPreferences,
) : DirectCallTerminalNotificationStore {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
    )

    @Synchronized
    override fun contains(callId: RemoteDirectCallId): Boolean =
        readCallIds().contains(callId.raw)

    @Synchronized
    override fun record(callId: RemoteDirectCallId) {
        val retainedCallIds = buildList {
            add(callId.raw)
            addAll(readCallIds().filterNot { existingCallId -> existingCallId == callId.raw })
        }.take(MAXIMUM_RETAINED_CALL_IDS)
        preferences.edit {
            putString(TERMINAL_CALL_IDS_KEY, retainedCallIds.joinToString(SEPARATOR))
        }
    }

    private fun readCallIds(): List<String> =
        preferences.getString(TERMINAL_CALL_IDS_KEY, null)
            ?.split(SEPARATOR)
            ?.filter(String::isNotBlank)
            .orEmpty()

    private companion object {
        const val MAXIMUM_RETAINED_CALL_IDS = 64
        const val PREFERENCES_NAME = "direct_call_terminal_notifications"
        const val SEPARATOR = "\n"
        const val TERMINAL_CALL_IDS_KEY = "terminal_call_ids"
    }
}
