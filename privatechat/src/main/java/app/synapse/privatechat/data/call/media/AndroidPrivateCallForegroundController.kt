package app.synapse.privatechat.data.call.media

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.synapse.privatechat.domain.call.PrivateCallForegroundGateway
import app.synapse.privatechat.domain.call.PrivateCallMediaKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.util.UUID

class AndroidPrivateCallForegroundController(
    context: Context,
) : PrivateCallForegroundGateway {
    private val applicationContext = context.applicationContext

    /** Invoke only after the visible activity obtains microphone/camera and notification permissions. */
    override suspend fun start(
        callId: UUID,
        mediaKind: PrivateCallMediaKind,
        onHangUp: () -> Unit,
    ) {
        check(
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
        ) {
            "Grant microphone permission before starting a call."
        }
        if (mediaKind == PrivateCallMediaKind.VIDEO) {
            check(ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                "Grant camera permission before starting a video call."
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            check(
                ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED,
            ) {
                "Enable call notifications so the ongoing call and Hang up control stay visible."
            }
        }
        check(
            NotificationManagerCompat.from(applicationContext).areNotificationsEnabled(),
        ) { "Enable call notifications before starting a call." }
        val started = PrivateCallForegroundRegistration.register(callId.toString(), mediaKind, onHangUp)
        try {
            ContextCompat.startForegroundService(
                applicationContext,
                Intent(applicationContext, PrivateCallForegroundService::class.java)
                    .setAction(
                        PrivateCallForegroundService.ACTION_START,
                    ).putExtra(PrivateCallForegroundService.EXTRA_CALL_ID, callId.toString()),
            )
            withTimeout(5_000) { started.await() }
        } catch (failure: Throwable) {
            stop(callId)
            throw failure
        }
    }

    override fun stop(callId: UUID) {
        if (PrivateCallForegroundRegistration.clear(callId.toString())) {
            applicationContext.stopService(Intent(applicationContext, PrivateCallForegroundService::class.java))
        }
    }
}

internal object PrivateCallForegroundRegistration {
    private var registration: Registration? = null

    @Synchronized fun register(
        callId: String,
        kind: PrivateCallMediaKind,
        onHangUp: () -> Unit,
    ): CompletableDeferred<Unit> {
        check(registration == null) { "Another call is already active." }
        return CompletableDeferred<Unit>().also { registration = Registration(callId, kind, onHangUp, it) }
    }

    @Synchronized fun forCall(callId: String): Registration? = registration?.takeIf { it.callId == callId }

    @Synchronized fun clear(callId: String): Boolean {
        val current = registration?.takeIf { it.callId == callId } ?: return false
        registration = null
        current.started.cancel()
        return true
    }

    fun requestHangUp(callId: String) {
        val callback =
            synchronized(this) {
                val current = registration?.takeIf { it.callId == callId } ?: return
                if (current.hangUpRequested) return
                current.hangUpRequested = true
                current.started.cancel()
                current.onHangUp
            }
        callback()
    }

    data class Registration(
        val callId: String,
        val kind: PrivateCallMediaKind,
        val onHangUp: () -> Unit,
        val started: CompletableDeferred<Unit>,
        var hangUpRequested: Boolean = false,
    )
}
