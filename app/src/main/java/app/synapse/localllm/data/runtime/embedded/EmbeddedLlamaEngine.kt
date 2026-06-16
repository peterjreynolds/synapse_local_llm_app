package app.synapse.localllm.data.runtime.embedded

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

class EmbeddedLlamaEngine private constructor(
    private val nativeLibraryDirectory: String,
) {
    private val mutableState = MutableStateFlow<EmbeddedLlamaEngineState>(
        EmbeddedLlamaEngineState.Uninitialized,
    )
    val state: StateFlow<EmbeddedLlamaEngineState> = mutableState.asStateFlow()

    private var loadedModelPath: String? = null
    private var cancelGeneration = false

    @OptIn(ExperimentalCoroutinesApi::class)
    private val llamaDispatcher = Dispatchers.IO.limitedParallelism(1)

    init {
        mutableState.value = EmbeddedLlamaEngineState.Initializing
        System.loadLibrary(NATIVE_LIBRARY_NAME)
        init(nativeLibraryDirectory)
        mutableState.value = EmbeddedLlamaEngineState.Initialized
        Log.i(TAG, "Embedded llama.cpp initialized: ${systemInfo()}")
    }

    suspend fun loadModel(modelPath: String) =
        withContext(llamaDispatcher) {
            if (loadedModelPath == modelPath && mutableState.value is EmbeddedLlamaEngineState.ModelReady) {
                return@withContext
            }
            if (loadedModelPath != null) {
                unloadCurrentModel()
            }

            val modelFile = File(modelPath)
            require(modelFile.isFile) { "Embedded model file is missing." }
            require(modelFile.canRead()) { "Embedded model file cannot be read." }

            mutableState.value = EmbeddedLlamaEngineState.LoadingModel
            val loadCode = load(modelPath)
            if (loadCode != 0) {
                val exception = IOException("llama.cpp failed to load model: code $loadCode")
                mutableState.value = EmbeddedLlamaEngineState.Error(exception)
                throw exception
            }
            val prepareCode = prepare()
            if (prepareCode != 0) {
                val exception = IOException("llama.cpp failed to prepare model: code $prepareCode")
                mutableState.value = EmbeddedLlamaEngineState.Error(exception)
                throw exception
            }

            loadedModelPath = modelPath
            cancelGeneration = false
            mutableState.value = EmbeddedLlamaEngineState.ModelReady
        }

    fun streamResponse(
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int,
        temperature: Double,
    ): Flow<String> = flow {
        require(userPrompt.isNotBlank()) { "Embedded prompt cannot be blank." }
        check(mutableState.value is EmbeddedLlamaEngineState.ModelReady) {
            "Embedded llama.cpp model is not ready."
        }

        try {
            cancelGeneration = false
            mutableState.value = EmbeddedLlamaEngineState.ProcessingPrompt
            val systemCode = processSystemPrompt(systemPrompt.ifBlank { "You are Synapse." })
            if (systemCode != 0) {
                throw IOException("llama.cpp failed to process system prompt: code $systemCode")
            }
            val userCode = processUserPrompt(
                userPrompt,
                maxTokens.coerceIn(MIN_PREDICT_TOKENS, MAX_PREDICT_TOKENS),
                temperature.toFloat().coerceIn(MIN_TEMPERATURE, MAX_TEMPERATURE),
            )
            if (userCode != 0) {
                throw IOException("llama.cpp failed to process user prompt: code $userCode")
            }

            mutableState.value = EmbeddedLlamaEngineState.Generating
            while (!cancelGeneration) {
                val token = generateNextToken() ?: break
                if (token.isNotEmpty()) emit(token)
            }
            mutableState.value = EmbeddedLlamaEngineState.ModelReady
        } catch (exception: CancellationException) {
            cancelGeneration = true
            mutableState.value = EmbeddedLlamaEngineState.ModelReady
            throw exception
        } catch (exception: Exception) {
            mutableState.value = EmbeddedLlamaEngineState.Error(exception)
            throw exception
        }
    }.flowOn(llamaDispatcher)

    fun cancelGeneration() {
        cancelGeneration = true
    }

    fun destroy() {
        cancelGeneration = true
        when (mutableState.value) {
            EmbeddedLlamaEngineState.Uninitialized,
            EmbeddedLlamaEngineState.Initializing,
            -> Unit

            EmbeddedLlamaEngineState.Initialized -> shutdown()
            else -> {
                unload()
                shutdown()
            }
        }
        mutableState.value = EmbeddedLlamaEngineState.Uninitialized
    }

    private fun unloadCurrentModel() {
        mutableState.value = EmbeddedLlamaEngineState.UnloadingModel
        unload()
        loadedModelPath = null
        mutableState.value = EmbeddedLlamaEngineState.Initialized
    }

    private external fun init(nativeLibraryDirectory: String)

    private external fun load(modelPath: String): Int

    private external fun prepare(): Int

    private external fun systemInfo(): String

    private external fun processSystemPrompt(systemPrompt: String): Int

    private external fun processUserPrompt(
        userPrompt: String,
        predictLength: Int,
        temperature: Float,
    ): Int

    private external fun generateNextToken(): String?

    private external fun unload()

    private external fun shutdown()

    companion object {
        private const val TAG = "EmbeddedLlamaEngine"
        private const val NATIVE_LIBRARY_NAME = "synapse-llama"
        private const val MIN_PREDICT_TOKENS = 1
        private const val MAX_PREDICT_TOKENS = 4096
        private const val MIN_TEMPERATURE = 0.0f
        private const val MAX_TEMPERATURE = 2.0f

        @Volatile
        private var instance: EmbeddedLlamaEngine? = null

        fun getInstance(context: Context): EmbeddedLlamaEngine =
            instance ?: synchronized(this) {
                instance ?: EmbeddedLlamaEngine(
                    nativeLibraryDirectory = context.applicationInfo.nativeLibraryDir,
                ).also { engine -> instance = engine }
            }
    }
}

sealed interface EmbeddedLlamaEngineState {
    data object Uninitialized : EmbeddedLlamaEngineState

    data object Initializing : EmbeddedLlamaEngineState

    data object Initialized : EmbeddedLlamaEngineState

    data object LoadingModel : EmbeddedLlamaEngineState

    data object UnloadingModel : EmbeddedLlamaEngineState

    data object ModelReady : EmbeddedLlamaEngineState

    data object ProcessingPrompt : EmbeddedLlamaEngineState

    data object Generating : EmbeddedLlamaEngineState

    data class Error(
        val exception: Exception,
    ) : EmbeddedLlamaEngineState
}
