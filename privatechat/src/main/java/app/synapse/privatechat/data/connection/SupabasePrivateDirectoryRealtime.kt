package app.synapse.privatechat.data.connection

import app.synapse.privatechat.data.chat.PrivateChatAuthenticatedSession
import app.synapse.privatechat.data.chat.PrivateChatSessionResolver
import app.synapse.privatechat.data.diagnostics.PrivateConnectionDiagnostics
import app.synapse.privatechat.data.diagnostics.PrivateDiagnosticOperation
import app.synapse.privatechat.data.supabase.SynapsePrivateBackendConfig
import app.synapse.privatechat.domain.account.PrivateAccountId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class SupabasePrivateDirectoryRealtime(
    private val config: SynapsePrivateBackendConfig,
    private val sessions: PrivateChatSessionResolver,
    private val diagnostics: PrivateConnectionDiagnostics,
    private val client: OkHttpClient = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build(),
) {
    fun changes(accountId: PrivateAccountId): Flow<Unit> =
        flow {
            var retryMillis = 1_000L
            while (currentCoroutineContext().isActive) {
                try {
                    val session = sessions.resolve(accountId) ?: throw IOException("Session unavailable")
                    connect(session).collect {
                        retryMillis = 1_000L
                        emit(it)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    diagnostics.record(PrivateDiagnosticOperation.REALTIME, failure = failure)
                }
                // Directory RPC polling remains active while this notification channel reconnects.
                delay(retryMillis)
                retryMillis = (retryMillis * 2).coerceAtMost(30_000L)
            }
        }

    private fun connect(session: PrivateChatAuthenticatedSession): Flow<Unit> =
        callbackFlow {
            val joined = AtomicBoolean(false)
            val heartbeatAcknowledged = AtomicBoolean(true)
            val request =
                Request
                    .Builder()
                    .url("https://${config.projectUri.host}/realtime/v1/websocket?apikey=${config.publishableKey}&vsn=1.0.0")
                    .build()
            val socket =
                client.newWebSocket(
                    request,
                    object : WebSocketListener() {
                        override fun onOpen(
                            webSocket: WebSocket,
                            response: Response,
                        ) {
                            if (!webSocket.send(PrivateDirectoryRealtimeProtocol.join(session.accessTokenForRequest()))) {
                                close(IOException("Channel join unavailable"))
                            }
                        }

                        override fun onMessage(
                            webSocket: WebSocket,
                            text: String,
                        ) {
                            try {
                                when (PrivateDirectoryRealtimeProtocol.parse(text)) {
                                    PrivateDirectoryRealtimeEvent.JOINED -> {
                                        joined.set(true)
                                        trySend(Unit)
                                    }
                                    PrivateDirectoryRealtimeEvent.CHANGED -> {
                                        if (joined.get()) trySend(Unit)
                                    }
                                    PrivateDirectoryRealtimeEvent.HEARTBEAT_ACKNOWLEDGED -> heartbeatAcknowledged.set(true)
                                    PrivateDirectoryRealtimeEvent.REJECTED -> close(IOException("Channel rejected"))
                                    PrivateDirectoryRealtimeEvent.IGNORED -> Unit
                                }
                            } catch (_: Exception) {
                                close(IOException("Channel response invalid"))
                            }
                        }

                        override fun onFailure(
                            webSocket: WebSocket,
                            t: Throwable,
                            response: Response?,
                        ) {
                            close(IOException("Channel connection failed"))
                        }

                        override fun onClosing(
                            webSocket: WebSocket,
                            code: Int,
                            reason: String,
                        ) {
                            close(IOException("Channel connection closed"))
                        }
                    },
                )
            val heartbeat =
                launch {
                    delay(10_000)
                    if (!joined.get()) {
                        close(IOException("Channel join timed out"))
                        return@launch
                    }
                    while (isActive) {
                        val current = sessions.resolve(session.accountId)
                        if (current == null || !session.hasSameAuthenticatedDeviceAs(current)) {
                            close(IOException("Channel session changed"))
                            return@launch
                        }
                        if (!heartbeatAcknowledged.getAndSet(false) || !socket.send(PrivateDirectoryRealtimeProtocol.heartbeat())) {
                            close(IOException("Channel heartbeat timed out"))
                            return@launch
                        }
                        delay(20_000)
                    }
                }
            awaitClose {
                heartbeat.cancel()
                socket.cancel()
            }
        }.buffer(Channel.CONFLATED)
}
