package com.deivid22srk.rewardsearcher.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File

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
    private external fun nativeFreeModel()
    private external fun nativeIsLoaded(): Boolean

    interface GenerateCallback {
        fun onToken(token: String)
        fun onComplete()
    }

    private val _isLoaded = MutableStateFlow(false)
    val isLoaded: StateFlow<Boolean> = _isLoaded

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating

    fun getModelDir(): File {
        val dir = File(context.filesDir, "models")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getModelFile(): File = File(getModelDir(), DEFAULT_MODEL_NAME)

    fun isModelDownloaded(): Boolean = getModelFile().exists()

    fun loadModel(): Boolean {
        val modelFile = getModelFile()
        if (!modelFile.exists()) return false
        if (nativeIsLoaded()) return true

        val threads = Runtime.getRuntime().availableProcessors().coerceAtMost(4)
        val result = nativeLoadModel(modelFile.absolutePath, 512, threads)
        _isLoaded.value = result != 0L
        return _isLoaded.value
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
    }
}
