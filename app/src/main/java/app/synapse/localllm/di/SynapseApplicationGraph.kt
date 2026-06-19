package app.synapse.localllm.di

import android.content.Context
import androidx.room.Room
import app.synapse.localllm.BuildConfig
import app.synapse.localllm.application.SynapseTurnCoordinator
import app.synapse.localllm.data.chat.RoomConversationRepository
import app.synapse.localllm.data.diagnostics.AndroidDebugArchiveExporter
import app.synapse.localllm.data.diagnostics.RoomGenerationDiagnosticsRepository
import app.synapse.localllm.data.db.SYNAPSE_DATABASE_MIGRATION_1_2
import app.synapse.localllm.data.db.SYNAPSE_DATABASE_MIGRATION_2_3
import app.synapse.localllm.data.db.SYNAPSE_DATABASE_MIGRATION_3_4
import app.synapse.localllm.data.db.SYNAPSE_DATABASE_MIGRATION_4_5
import app.synapse.localllm.data.db.SYNAPSE_DATABASE_MIGRATION_5_6
import app.synapse.localllm.data.db.SYNAPSE_DATABASE_MIGRATION_6_7
import app.synapse.localllm.data.db.SynapseDatabase
import app.synapse.localllm.data.library.AndroidMarkdownPdfExporter
import app.synapse.localllm.data.library.RoomLibraryWorkspaceRepository
import app.synapse.localllm.data.memory.DefaultMemoryCandidateNormalizer
import app.synapse.localllm.data.memory.DeterministicMemoryProjector
import app.synapse.localllm.data.memory.EvidenceBackedMemoryAdmissionGate
import app.synapse.localllm.data.memory.PatternMemoryCommandInterpreter
import app.synapse.localllm.data.memory.RuleBasedMemoryCandidateProposer
import app.synapse.localllm.data.memory.RoomMemoryRepository
import app.synapse.localllm.data.memory.VerifiedPromptContextAssembler
import app.synapse.localllm.data.runtime.AndroidEmbeddedModelStore
import app.synapse.localllm.data.runtime.AndroidForegroundModelDownloadController
import app.synapse.localllm.data.runtime.AndroidModelDownloader
import app.synapse.localllm.data.runtime.BuiltInModelCatalogRepository
import app.synapse.localllm.data.runtime.LlamaServerGateway
import app.synapse.localllm.data.runtime.PhoneLocalInferenceRuntime
import app.synapse.localllm.data.runtime.TermuxCommandGateway
import app.synapse.localllm.data.runtime.embedded.EmbeddedLlamaRuntime
import app.synapse.localllm.data.settings.SynapseSettingsStore
import app.synapse.localllm.data.storage.AndroidStorageHealthGovernor
import app.synapse.localllm.data.storage.RoomStorageHealthSnapshotRepository
import app.synapse.localllm.data.update.AndroidAppUpdateDownloader
import app.synapse.localllm.data.update.GitHubReleaseAppUpdateRepository
import app.synapse.localllm.domain.chat.ConversationRepository
import app.synapse.localllm.domain.diagnostics.GenerationDiagnosticsRepository
import app.synapse.localllm.domain.ids.SynapseIdFactory
import app.synapse.localllm.domain.library.LibraryWorkspaceRepository
import app.synapse.localllm.domain.memory.MemoryAdmissionGate
import app.synapse.localllm.domain.memory.MemoryCandidateNormalizer
import app.synapse.localllm.domain.memory.MemoryCandidateProposer
import app.synapse.localllm.domain.memory.MemoryCommandInterpreter
import app.synapse.localllm.domain.memory.MemoryProjector
import app.synapse.localllm.domain.memory.MemoryRepository
import app.synapse.localllm.domain.memory.PromptContextAssembler
import app.synapse.localllm.domain.runtime.LocalInferenceRuntime
import app.synapse.localllm.domain.runtime.ModelCatalogRepository
import app.synapse.localllm.domain.runtime.ModelDownloader
import app.synapse.localllm.domain.storage.StorageHealthGovernor
import app.synapse.localllm.domain.time.SynapseClock
import app.synapse.localllm.domain.time.SystemSynapseClock
import app.synapse.localllm.domain.update.AppUpdateDownloader
import app.synapse.localllm.domain.update.AppUpdateRepository
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

class SynapseApplicationGraph private constructor(context: Context) {
    private val applicationContext = context.applicationContext

    val clock: SynapseClock = SystemSynapseClock()
    val idFactory = SynapseIdFactory()
    val database: SynapseDatabase = Room.databaseBuilder(
        applicationContext,
        SynapseDatabase::class.java,
        DATABASE_NAME,
    ).addMigrations(
        SYNAPSE_DATABASE_MIGRATION_1_2,
        SYNAPSE_DATABASE_MIGRATION_2_3,
        SYNAPSE_DATABASE_MIGRATION_3_4,
        SYNAPSE_DATABASE_MIGRATION_4_5,
        SYNAPSE_DATABASE_MIGRATION_5_6,
        SYNAPSE_DATABASE_MIGRATION_6_7,
    ).build()

    val settingsStore = SynapseSettingsStore(applicationContext)
    val embeddedModelStore = AndroidEmbeddedModelStore(applicationContext)
    val modelCatalogRepository: ModelCatalogRepository = BuiltInModelCatalogRepository()
    val modelDownloader: ModelDownloader = AndroidModelDownloader(applicationContext, createHttpClient())
    val modelDownloadController = AndroidForegroundModelDownloadController(applicationContext)
    val appUpdateRepository: AppUpdateRepository =
        GitHubReleaseAppUpdateRepository(
            httpClient = createHttpClient(),
            currentVersionCode = BuildConfig.VERSION_CODE,
        )
    val appUpdateDownloader: AppUpdateDownloader = AndroidAppUpdateDownloader(applicationContext, createHttpClient())
    val debugArchiveExporter = AndroidDebugArchiveExporter(applicationContext, clock)
    val markdownPdfExporter = AndroidMarkdownPdfExporter(applicationContext, clock)

    val conversationRepository: ConversationRepository =
        RoomConversationRepository(
            database = database,
            chatDao = database.chatDao(),
            idFactory = idFactory,
            clock = clock,
        )

    val memoryRepository: MemoryRepository =
        RoomMemoryRepository(
            database = database,
            memoryDao = database.memoryDao(),
            idFactory = idFactory,
            clock = clock,
        )

    val libraryWorkspaceRepository: LibraryWorkspaceRepository =
        RoomLibraryWorkspaceRepository(
            context = applicationContext,
            database = database,
            libraryDao = database.libraryDao(),
            idFactory = idFactory,
            clock = clock,
        )

    val generationDiagnosticsRepository: GenerationDiagnosticsRepository =
        RoomGenerationDiagnosticsRepository(
            diagnosticsDao = database.diagnosticsDao(),
            idFactory = idFactory,
        )

    val memoryProjector: MemoryProjector = DeterministicMemoryProjector()
    val memoryCandidateNormalizer: MemoryCandidateNormalizer = DefaultMemoryCandidateNormalizer()
    val memoryCandidateProposer: MemoryCandidateProposer = RuleBasedMemoryCandidateProposer()
    val memoryCommandInterpreter: MemoryCommandInterpreter = PatternMemoryCommandInterpreter()
    val memoryAdmissionGate: MemoryAdmissionGate = EvidenceBackedMemoryAdmissionGate()
    val storageHealthGovernor: StorageHealthGovernor =
        AndroidStorageHealthGovernor(applicationContext, clock)
    val storageHealthSnapshotRepository =
        RoomStorageHealthSnapshotRepository(database.storageHealthDao(), idFactory)
    val promptContextAssembler: PromptContextAssembler = VerifiedPromptContextAssembler()

    val localInferenceRuntime: LocalInferenceRuntime =
        PhoneLocalInferenceRuntime(
            llamaServerGateway = LlamaServerGateway(
                httpClient = createHttpClient(),
                clock = clock,
            ),
            termuxCommandGateway = TermuxCommandGateway(
                context = applicationContext,
                idFactory = idFactory,
                clock = clock,
            ),
            embeddedLlamaRuntime = EmbeddedLlamaRuntime(
                context = applicationContext,
                idFactory = idFactory,
                clock = clock,
            ),
        )

    val turnCoordinator =
        SynapseTurnCoordinator(
            conversationRepository = conversationRepository,
            memoryRepository = memoryRepository,
            memoryCommandInterpreter = memoryCommandInterpreter,
            memoryProjector = memoryProjector,
            memoryCandidateNormalizer = memoryCandidateNormalizer,
            memoryCandidateProposer = memoryCandidateProposer,
            memoryAdmissionGate = memoryAdmissionGate,
            storageHealthGovernor = storageHealthGovernor,
            storageHealthSnapshotRepository = storageHealthSnapshotRepository,
            promptContextAssembler = promptContextAssembler,
            localInferenceRuntime = localInferenceRuntime,
            generationDiagnosticsRepository = generationDiagnosticsRepository,
            idFactory = idFactory,
            clock = clock,
        )

    private fun createHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

    companion object {
        fun create(context: Context): SynapseApplicationGraph =
            SynapseApplicationGraph(context)

        private const val DATABASE_NAME = "synapse.db"
    }
}
