package app.synapse.privatechat

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.synapse.privatechat.crypto.SignalProtocolAdapterOwner
import app.synapse.privatechat.crypto.SignalProtocolStateCorruptedException
import app.synapse.privatechat.crypto.local.AndroidDeviceLocalContentEnvelopeCipherFactory
import app.synapse.privatechat.crypto.local.AndroidDeviceLocalEncryptedPayloadCacheStorageFactory
import app.synapse.privatechat.crypto.storage.AndroidSignalProtocolStateRepositoryFactory
import app.synapse.privatechat.data.account.LocalStateUnavailablePrivateAccountGateway
import app.synapse.privatechat.data.account.PrivateSignalDeviceBootstrapper
import app.synapse.privatechat.data.account.SupabasePrivateAccountApi
import app.synapse.privatechat.data.account.SupabasePrivateAccountGateway
import app.synapse.privatechat.data.chat.LibSignalPrivateChatCipher
import app.synapse.privatechat.data.chat.PendingTransportPrivateChatGateway
import app.synapse.privatechat.data.chat.PrivateChatEnvelopeCipher
import app.synapse.privatechat.data.chat.PrivateChatGatewayExecution
import app.synapse.privatechat.data.chat.PrivateChatLocalStateInvalidator
import app.synapse.privatechat.data.chat.PrivateChatMutationCoordinator
import app.synapse.privatechat.data.chat.PrivateChatPollingRepository
import app.synapse.privatechat.data.chat.PrivateChatSessionResolver
import app.synapse.privatechat.data.chat.PrivateChatSnapshotAssembler
import app.synapse.privatechat.data.chat.PrivateDecryptedPayloadCacheRepository
import app.synapse.privatechat.data.chat.PrivateEncryptedMutationOutbox
import app.synapse.privatechat.data.chat.PrivateSocialMutationCoordinator
import app.synapse.privatechat.data.chat.StoredPrivateChatSessionProvider
import app.synapse.privatechat.data.chat.SupabasePrivateChatBackend
import app.synapse.privatechat.data.chat.SupabasePrivateChatGateway
import app.synapse.privatechat.data.chat.SupabasePrivateChatMutationTransport
import app.synapse.privatechat.data.chat.SupabasePrivateChatPollingApi
import app.synapse.privatechat.data.chat.SupabasePrivateChatRequestExecutor
import app.synapse.privatechat.data.chat.SupabasePrivateContentMutationApi
import app.synapse.privatechat.data.chat.SupabasePrivateRoomMutationApi
import app.synapse.privatechat.data.chat.SupabasePrivateSocialGateway
import app.synapse.privatechat.data.chat.SupabasePrivateSocialMutationApi
import app.synapse.privatechat.data.session.AndroidPrivateSessionRepositoryFactory
import app.synapse.privatechat.data.session.PrivateSessionStateUnavailableException
import app.synapse.privatechat.data.supabase.SupabaseHttpTransport
import app.synapse.privatechat.data.supabase.SynapsePrivateBackendConfig
import app.synapse.privatechat.data.supabase.UrlConnectionSupabaseHttpTransport
import app.synapse.privatechat.domain.account.PrivateAccountGateway
import app.synapse.privatechat.domain.chat.PrivateChatGateway
import app.synapse.privatechat.domain.chat.PrivateClientMutationId
import app.synapse.privatechat.domain.chat.PrivateSocialGateway
import app.synapse.privatechat.ui.account.PrivateAccountAccessViewModel
import app.synapse.privatechat.ui.chat.PrivateChatViewModel
import kotlinx.coroutines.Dispatchers
import java.time.Clock
import java.util.UUID

class PrivateChatCompositionRoot private constructor(
    accountGateway: PrivateAccountGateway,
    chatGateway: PrivateChatGateway,
    socialGateway: PrivateSocialGateway,
    clock: Clock,
) {
    val accountAccessViewModelFactory: ViewModelProvider.Factory =
        viewModelFactory {
            initializer {
                PrivateAccountAccessViewModel(accountGateway)
            }
        }

    val chatViewModelFactory: ViewModelProvider.Factory =
        viewModelFactory {
            initializer {
                PrivateChatViewModel(
                    chatGateway = chatGateway,
                    socialGateway = socialGateway,
                    mutationIdFactory = { PrivateClientMutationId(UUID.randomUUID().toString()) },
                    clock = clock,
                )
            }
        }

    companion object {
        fun create(context: Context): PrivateChatCompositionRoot {
            val clock = Clock.systemUTC()
            val config =
                SynapsePrivateBackendConfig.requireValid(
                    projectUrl = BuildConfig.SUPABASE_PROJECT_URL,
                    publishableKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY,
                )
            val appContext = context.applicationContext
            val transport = UrlConnectionSupabaseHttpTransport(config)
            val runtime = createRuntime(appContext, transport, clock)
            return PrivateChatCompositionRoot(
                accountGateway = runtime.accountGateway,
                chatGateway = runtime.chatGateway,
                socialGateway = runtime.socialGateway,
                clock = clock,
            )
        }

        private fun createRuntime(
            context: Context,
            transport: SupabaseHttpTransport,
            clock: Clock,
        ): PrivateChatRuntime =
            try {
                createAvailableRuntime(context, transport, clock)
            } catch (_: PrivateSessionStateUnavailableException) {
                unavailableRuntime()
            } catch (_: SignalProtocolStateCorruptedException) {
                unavailableRuntime()
            }

        private fun createAvailableRuntime(
            context: Context,
            transport: SupabaseHttpTransport,
            clock: Clock,
        ): PrivateChatRuntime {
            val sessionRepository = AndroidPrivateSessionRepositoryFactory.create(context)
            val signalAdapterOwner =
                SignalProtocolAdapterOwner(
                    AndroidSignalProtocolStateRepositoryFactory.create(context),
                )
            val requestExecutor = SupabasePrivateChatRequestExecutor(transport)
            val pollingApi = SupabasePrivateChatPollingApi(requestExecutor)
            val mutationTransport = SupabasePrivateChatMutationTransport(requestExecutor)
            val chatBackend =
                SupabasePrivateChatBackend(
                    polling = pollingApi,
                    contentMutations = SupabasePrivateContentMutationApi(mutationTransport),
                    roomMutations = SupabasePrivateRoomMutationApi(mutationTransport),
                    socialMutations = SupabasePrivateSocialMutationApi(mutationTransport),
                )
            val localEnvelopeCipher = AndroidDeviceLocalContentEnvelopeCipherFactory.create(context)
            val envelopeCipher =
                PrivateChatEnvelopeCipher(
                    signalCipher = LibSignalPrivateChatCipher(signalAdapterOwner),
                    localCipher = localEnvelopeCipher,
                )
            val payloadCache =
                PrivateDecryptedPayloadCacheRepository(
                    AndroidDeviceLocalEncryptedPayloadCacheStorageFactory.create(context),
                )
            val sessionResolver =
                PrivateChatSessionResolver(
                    sessionProvider = StoredPrivateChatSessionProvider(sessionRepository),
                    payloadCache = payloadCache,
                    clock = clock,
                )
            val execution = PrivateChatGatewayExecution(sessionResolver)
            val pollingRepository =
                PrivateChatPollingRepository(
                    backend = chatBackend,
                    envelopeCipher = envelopeCipher,
                    payloadCache = payloadCache,
                    clock = clock,
                )
            val encryptedMutationOutbox = PrivateEncryptedMutationOutbox(envelopeCipher, chatBackend, clock)
            val snapshotAssembler = PrivateChatSnapshotAssembler()
            val chatMutations =
                PrivateChatMutationCoordinator(
                    execution = execution,
                    backend = chatBackend,
                    pollingRepository = pollingRepository,
                    snapshotAssembler = snapshotAssembler,
                    encryptedMutationOutbox = encryptedMutationOutbox,
                    payloadCache = payloadCache,
                    clock = clock,
                )
            val socialMutations =
                PrivateSocialMutationCoordinator(
                    execution = execution,
                    backend = chatBackend,
                    encryptedMutationOutbox = encryptedMutationOutbox,
                    pollingRepository = pollingRepository,
                )
            return PrivateChatRuntime(
                accountGateway =
                    SupabasePrivateAccountGateway(
                        backend = SupabasePrivateAccountApi(transport, clock),
                        signalDeviceBootstrapper = PrivateSignalDeviceBootstrapper(signalAdapterOwner),
                        sessionRepository = sessionRepository,
                        localStateInvalidator =
                            PrivateChatLocalStateInvalidator(
                                pollingRepository = pollingRepository,
                                envelopeCipher = envelopeCipher,
                            ),
                        operationDispatcher = Dispatchers.IO,
                        clock = clock,
                    ),
                chatGateway =
                    SupabasePrivateChatGateway(
                        execution = execution,
                        pollingRepository = pollingRepository,
                        snapshotAssembler = snapshotAssembler,
                        mutations = chatMutations,
                    ),
                socialGateway =
                    SupabasePrivateSocialGateway(
                        execution = execution,
                        pollingRepository = pollingRepository,
                        snapshotAssembler = snapshotAssembler,
                        mutations = socialMutations,
                    ),
            )
        }

        private fun unavailableRuntime(): PrivateChatRuntime =
            PrivateChatRuntime(
                accountGateway = LocalStateUnavailablePrivateAccountGateway,
                chatGateway = PendingTransportPrivateChatGateway,
                socialGateway = PendingTransportPrivateChatGateway,
            )
    }
}

private data class PrivateChatRuntime(
    val accountGateway: PrivateAccountGateway,
    val chatGateway: PrivateChatGateway,
    val socialGateway: PrivateSocialGateway,
)
