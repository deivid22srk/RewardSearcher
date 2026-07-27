package com.deivid22srk.rewardsearcher.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Manages the on-device llama.cpp model lifecycle and exposes two generation
 * entry points: [generateSearches] (JSON array of search queries) and
 * [chat] (multi-turn conversation with streaming tokens).
 *
 * Model loading is fully async — [loadModelAsync] runs on Dispatchers.Default
 * and emits fine-grained progress through [loadProgress] and [loadStage] so
 * the UI never freezes even for multi-hundred-megabyte GGUF files.
 */
class LocalAIManager(private val context: Context) {

    companion object {
        init {
            System.loadLibrary("llama-jni")
        }

        const val DEFAULT_MODEL_URL =
            "https://huggingface.co/LiquidAI/LFM2.5-230M-GGUF/resolve/main/LFM2.5-230M-Q8_0.gguf?download=true"
        const val DEFAULT_MODEL_NAME = "LFM2.5-230M-Q8_0.gguf"
    }

    private external fun nativeLoadModel(modelPath: String, nCtx: Int, nThreads: Int): Long
    private external fun nativeGenerate(prompt: String, maxTokens: Int, temperature: Float, callback: GenerateCallback)
    private external fun nativeChat(
        roles: Array<String>,
        contents: Array<String>,
        maxTokens: Int,
        temperature: Float,
        callback: GenerateCallback
    )
    private external fun nativeFreeModel()
    private external fun nativeIsLoaded(): Boolean

    interface GenerateCallback {
        fun onToken(token: String)
        fun onComplete()
    }

    // A single chat message in the conversation history.
    data class ChatMessage(val role: String, val content: String)

    private val _isLoaded = MutableStateFlow(false)
    val isLoaded: StateFlow<Boolean> = _isLoaded

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating

    // 0f..1f — coarse-grained progress between loading phases. llama.cpp's
    // model loader is atomic and does not expose byte-level progress, so we
    // emit phase transitions instead (backend init → model load → context
    // create → ready). This still gives the user a real, moving indicator.
    private val _loadProgress = MutableStateFlow(0f)
    val loadProgress: StateFlow<Float> = _loadProgress

    private val _loadStage = MutableStateFlow("")
    val loadStage: StateFlow<String> = _loadStage

    fun getModelDir(): File {
        val dir = File(context.filesDir, "models")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getModelFile(): File = File(getModelDir(), DEFAULT_MODEL_NAME)

    fun isModelDownloaded(): Boolean = getModelFile().exists()

    /**
     * Synchronous loader kept for callers that just need a yes/no answer
     * (e.g. the foreground service that runs on a background coroutine
     * already). UI callers should prefer [loadModelAsync].
     */
    fun loadModel(): Boolean {
        val modelFile = getModelFile()
        if (!modelFile.exists()) return false
        if (nativeIsLoaded()) return true

        val threads = Runtime.getRuntime().availableProcessors().coerceAtMost(4)
        val result = nativeLoadModel(modelFile.absolutePath, 1024, threads)
        _isLoaded.value = result != 0L
        return _isLoaded.value
    }

    /**
     * Async loader that reports progress through [loadProgress] / [loadStage].
     * Safe to call from the UI thread — all heavy work happens on
     * Dispatchers.Default. Returns true on success, false on failure
     * (missing file or native load error).
     */
    suspend fun loadModelAsync(): Boolean = withContext(Dispatchers.Default) {
        if (nativeIsLoaded()) {
            _loadProgress.value = 1f
            _loadStage.value = "Pronto"
            _isLoaded.value = true
            return@withContext true
        }

        val modelFile = getModelFile()
        if (!modelFile.exists()) {
            _loadStage.value = "Modelo não encontrado"
            _loadProgress.value = 0f
            return@withContext false
        }

        _isLoading.value = true
        _isLoaded.value = false
        try {
            // Phase 1: backend init + file size scan (~5%).
            _loadStage.value = "Inicializando backend…"
            _loadProgress.value = 0.05f
            delay(80) // let Compose recompose

            // Phase 2: the actual model load. This is the long phase — for
            // a 250 MB Q8_0 model on a mid-range phone it can take 3–8 s.
            // We cannot subdivide it (llama.cpp does not expose progress
            // callbacks), but running it off the main thread is what keeps
            // the UI responsive.
            _loadStage.value = "Carregando pesos do modelo…"
            _loadProgress.value = 0.20f
            delay(80)

            val threads = Runtime.getRuntime().availableProcessors().coerceAtMost(4)
            // Use a larger context (1024) so chat has room for history.
            val result = nativeLoadModel(modelFile.absolutePath, 1024, threads)

            // Phase 3: context creation has finished inside nativeLoadModel.
            _loadProgress.value = 0.85f
            _loadStage.value = "Criando contexto de inferência…"
            delay(80)

            if (result == 0L) {
                _loadStage.value = "Falha ao carregar modelo"
                _loadProgress.value = 0f
                return@withContext false
            }

            _isLoaded.value = true
            _loadProgress.value = 1f
            _loadStage.value = "Pronto"
            true
        } catch (e: Exception) {
            _loadStage.value = "Erro: ${e.message ?: "desconhecido"}"
            _loadProgress.value = 0f
            false
        } finally {
            _isLoading.value = false
        }
    }

    suspend fun generateSearches(count: Int, onToken: (String) -> Unit): List<String> =
        withContext(Dispatchers.Default) {
            if (!loadModel()) return@withContext SearchTerms.getShuffled(count)

            _isGenerating.value = true
            val fullResponse = StringBuilder()

            val prompt = "Generate exactly $count unique and diverse web search queries that a real person might search for. " +
                "They should be varied across topics like technology, science, health, travel, food, sports, entertainment, education, and daily life. " +
                "Return ONLY a JSON array of strings, no markdown, no explanation. Example: [\"query 1\",\"query 2\"]"

            try {
                nativeGenerate(prompt, 2048, 0.8f, object : GenerateCallback {
                    override fun onToken(token: String) {
                        fullResponse.append(token)
                        onToken(token)
                    }
                    override fun onComplete() {}
                })
            } catch (_: Exception) {}

            _isGenerating.value = false
            parseSearches(fullResponse.toString(), count)
        }

    /**
     * Multi-turn chat. [history] should include all prior turns plus the
     * new user message as the last entry. The model's embedded chat
     * template (e.g. ChatML for LFM2.5) is applied inside the JNI layer,
     * so callers do not need to format prompts themselves.
     */
    suspend fun chat(
        history: List<ChatMessage>,
        maxTokens: Int = 512,
        temperature: Float = 0.7f,
        onToken: (String) -> Unit
    ): String = withContext(Dispatchers.Default) {
        if (!loadModel()) return@withContext ""

        _isGenerating.value = true
        val fullResponse = StringBuilder()

        if (history.isEmpty()) {
            _isGenerating.value = false
            return@withContext ""
        }

        val roles = history.map { it.role }.toTypedArray()
        val contents = history.map { it.content }.toTypedArray()

        try {
            nativeChat(roles, contents, maxTokens, temperature, object : GenerateCallback {
                override fun onToken(token: String) {
                    fullResponse.append(token)
                    onToken(token)
                }
                override fun onComplete() {}
            })
        } catch (_: Exception) {}

        _isGenerating.value = false
        fullResponse.toString().trim()
    }

    private fun parseSearches(response: String, count: Int): List<String> {
        return try {
            val cleaned = response.replace("```json", "").replace("```", "").trim()
            val start = cleaned.indexOf('[')
            val end = cleaned.lastIndexOf(']')
            if (start == -1 || end == -1) return SearchTerms.getShuffled(count)

            val arr = org.json.JSONArray(cleaned.substring(start, end + 1))
            val result = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                result.add(arr.getString(i))
            }
            if (result.size >= count) result.take(count)
            else result + SearchTerms.getShuffled(count - result.size)
        } catch (_: Exception) {
            SearchTerms.getShuffled(count)
        }
    }

    fun freeModel() {
        nativeFreeModel()
        _isLoaded.value = false
        _loadProgress.value = 0f
        _loadStage.value = ""
    }
}
