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
 *
 * Load failures are NOT silently swallowed: [loadError] surfaces the exact
 * reason (missing file, native load error, unsupported architecture) so the
 * UI can show it instead of falling back to the static term pool and making
 * it look like the model is running when it is not.
 */
class LocalAIManager(private val context: Context) {

    companion object {
        init {
            System.loadLibrary("llama-jni")
        }

        const val DEFAULT_MODEL_URL =
            "https://huggingface.co/LiquidAI/LFM2.5-230M-GGUF/resolve/main/LFM2.5-230M-Q8_0.gguf?download=true"
        const val DEFAULT_MODEL_NAME = "LFM2.5-230M-Q8_0.gguf"

        // Expected SHA-256 of the default model file (from the HuggingFace
        // ETag header). Used to detect truncated / corrupted downloads so
        // we never hand a half-finished file to llama.cpp.
        const val DEFAULT_MODEL_SHA256 =
            "855be85429300602eda72958547614703541b7d6dd965a8f8f6052b85a7aa935"
        const val DEFAULT_MODEL_SIZE = 246598496L // bytes
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

    // Surfaces the exact failure reason when loadModelAsync() returns false.
    // Cleared at the start of each load attempt.
    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError

    fun getModelDir(): File {
        val dir = File(context.filesDir, "models")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getModelFile(): File = File(getModelDir(), DEFAULT_MODEL_NAME)

    fun isModelDownloaded(): Boolean = getModelFile().exists()

    /**
     * Quick integrity check used before handing the file to llama.cpp.
     * Catches truncated downloads (which would otherwise cause a confusing
     * "Failed to load model" with no further explanation in the native log).
     *
     * Returns null if the file looks healthy, or a human-readable reason
     * string if it does not.
     */
    suspend fun verifyModelIntegrity(): String? = withContext(Dispatchers.IO) {
        val f = getModelFile()
        if (!f.exists()) return@withContext "Arquivo do modelo não encontrado"

        val size = f.length()
        if (size < 1024) return@withContext "Arquivo muito pequeno (${size} bytes) — download provavelmente falhou"

        // Read just the header (24 bytes for magic+version+n_tensors+n_kv).
        val header = ByteArray(24)
        try {
            f.inputStream().use { it.read(header) }
        } catch (e: Exception) {
            return@withContext "Não foi possível ler o arquivo: ${e.message}"
        }

        // GGUF magic = 0x46554747 ("GGUF" little-endian)
        val magic = ((header[0].toInt() and 0xff)) or
                    ((header[1].toInt() and 0xff) shl 8) or
                    ((header[2].toInt() and 0xff) shl 16) or
                    ((header[3].toInt() and 0xff) shl 24)
        if (magic != 0x46554747) {
            return@withContext "Magic incorreto (0x${magic.toString(16)}) — arquivo não é GGUF válido"
        }

        val version = (header[4].toInt() and 0xff) or
                      ((header[5].toInt() and 0xff) shl 8) or
                      ((header[6].toInt() and 0xff) shl 16) or
                      ((header[7].toInt() and 0xff) shl 24)
        if (version !in 1..3) {
            return@withContext "Versão GGUF não suportada: $version"
        }

        // For the default model only, also check exact size. Imported
        // models are accepted with any size as long as the magic is right.
        if (f.name == DEFAULT_MODEL_NAME && size != DEFAULT_MODEL_SIZE) {
            return@withContext "Tamanho incorreto: ${size} bytes (esperado $DEFAULT_MODEL_SIZE). " +
                "O download foi interrompido — baixe novamente."
        }

        null
    }

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
     * Async loader that reports progress through [loadProgress] / [loadStage]
     * and surfaces the exact failure reason via [loadError].
     *
     * Safe to call from the UI thread — all heavy work happens on
     * Dispatchers.Default. Returns true on success, false on failure
     * (missing file, native load error, unsupported architecture, etc.).
     */
    suspend fun loadModelAsync(): Boolean = withContext(Dispatchers.Default) {
        if (nativeIsLoaded()) {
            _loadProgress.value = 1f
            _loadStage.value = "Pronto"
            _isLoaded.value = true
            _loadError.value = null
            return@withContext true
        }

        _loadError.value = null
        val modelFile = getModelFile()
        if (!modelFile.exists()) {
            _loadStage.value = "Modelo não encontrado"
            _loadProgress.value = 0f
            _loadError.value = "Baixe ou importe um modelo GGUF nas Configurações antes de continuar."
            return@withContext false
        }

        _isLoading.value = true
        _isLoaded.value = false
        try {
            // Phase 0: file integrity check (catches truncated downloads
            // before we hand the file to llama.cpp, which would otherwise
            // fail with a cryptic "Failed to load model" message).
            _loadStage.value = "Verificando integridade do arquivo…"
            _loadProgress.value = 0.05f
            delay(60)
            val integrityError = verifyModelIntegrity()
            if (integrityError != null) {
                _loadStage.value = "Arquivo inválido"
                _loadProgress.value = 0f
                _loadError.value = integrityError
                return@withContext false
            }

            // Phase 1: backend init.
            _loadStage.value = "Inicializando backend…"
            _loadProgress.value = 0.15f
            delay(60)

            // Phase 2: the actual model load. This is the long phase — for
            // a 250 MB Q8_0 model on a mid-range phone it can take 3–8 s.
            // We cannot subdivide it (llama.cpp does not expose progress
            // callbacks), but running it off the main thread is what keeps
            // the UI responsive.
            _loadStage.value = "Carregando pesos do modelo…"
            _loadProgress.value = 0.25f
            delay(60)

            val threads = Runtime.getRuntime().availableProcessors().coerceAtMost(4)
            // Use a larger context (1024) so chat has room for history.
            val result = nativeLoadModel(modelFile.absolutePath, 1024, threads)

            // Phase 3: context creation has finished inside nativeLoadModel.
            _loadProgress.value = 0.85f
            _loadStage.value = "Criando contexto de inferência…"
            delay(60)

            if (result == 0L) {
                _loadStage.value = "Falha ao carregar modelo"
                _loadProgress.value = 0f
                _loadError.value = "O llama.cpp não conseguiu carregar o modelo. " +
                    "Verifique no logcat (tag LlamaJNI) a arquitetura do GGUF — " +
                    "modelos LFM2 exigem llama.cpp >= b6000."
                return@withContext false
            }

            _isLoaded.value = true
            _loadProgress.value = 1f
            _loadStage.value = "Pronto"
            _loadError.value = null
            true
        } catch (e: Exception) {
            _loadStage.value = "Erro: ${e.message ?: "desconhecido"}"
            _loadProgress.value = 0f
            _loadError.value = e.message ?: "Erro desconhecido ao carregar modelo"
            false
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * Generate `count` search queries using the local model.
     *
     * IMPORTANT: if the model fails to load we NO LONGER silently fall back
     * to SearchTerms — that behaviour made it look like the local AI was
     * working when it actually was not (the bug the user reported). Now we
     * return an empty list and surface the error through [loadError], so
     * the UI can show "não foi possível carregar o modelo" instead of
     * pretending to generate.
     *
     * The one exception is the foreground SearchOverlayService, which still
     * needs *something* to search for; for that path we fall back to the
     * static pool so the user gets their searches done even if the model
     * is unavailable. The service logs the failure.
     */
    suspend fun generateSearches(
        count: Int,
        fallbackOnError: Boolean = false,
        onToken: (String) -> Unit
    ): List<String> = withContext(Dispatchers.Default) {
        if (!loadModel()) {
            _loadError.value = _loadError.value ?: "Falha ao carregar modelo para geração"
            return@withContext if (fallbackOnError) SearchTerms.getShuffled(count) else emptyList()
        }

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
        } catch (e: Exception) {
            _loadError.value = "Erro durante geração: ${e.message}"
            _isGenerating.value = false
            return@withContext if (fallbackOnError) SearchTerms.getShuffled(count) else emptyList()
        }

        _isGenerating.value = false
        val parsed = parseSearches(fullResponse.toString(), count)
        if (parsed.isEmpty() && !fallbackOnError) {
            _loadError.value = "O modelo não retornou pesquisas válidas. " +
                "Resposta bruta: ${fullResponse.take(200)}"
        }
        parsed
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
        if (!loadModel()) {
            _loadError.value = _loadError.value ?: "Falha ao carregar modelo para chat"
            return@withContext ""
        }

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
        } catch (e: Exception) {
            _loadError.value = "Erro durante chat: ${e.message}"
        }

        _isGenerating.value = false
        fullResponse.toString().trim()
    }

    private fun parseSearches(response: String, count: Int): List<String> {
        return try {
            val cleaned = response.replace("```json", "").replace("```", "").trim()
            val start = cleaned.indexOf('[')
            val end = cleaned.lastIndexOf(']')
            if (start == -1 || end == -1 || end <= start) return emptyList()

            val arr = org.json.JSONArray(cleaned.substring(start, end + 1))
            val result = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                result.add(arr.getString(i))
            }
            if (result.size >= count) result.take(count)
            else result + SearchTerms.getShuffled(count - result.size)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun freeModel() {
        nativeFreeModel()
        _isLoaded.value = false
        _loadProgress.value = 0f
        _loadStage.value = ""
        _loadError.value = null
    }
}
