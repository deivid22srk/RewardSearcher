package com.deivid22srk.rewardsearcher.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object AISearchGenerator {

    private const val DEFAULT_URL = "https://integrate.api.nvidia.com/v1"
    private const val DEFAULT_KEY = "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"
    private const val DEFAULT_MODEL = "minimaxai/minimax-m3"

    suspend fun generate(
        count: Int,
        customUrl: String = "",
        customModel: String = "",
        customKey: String = ""
    ): List<String> = withContext(Dispatchers.IO) {
        val baseUrl = customUrl.ifBlank { DEFAULT_URL }.trimEnd('/')
        val apiKey = customKey.ifBlank { DEFAULT_KEY }
        val model = customModel.ifBlank { DEFAULT_MODEL }

        val prompt = "Generate exactly $count unique and diverse web search queries that a real person might search for. " +
            "They should be varied across topics like technology, science, health, travel, food, sports, entertainment, education, and daily life. " +
            "Return ONLY a JSON array of strings, no markdown, no explanation. Example: [\"query 1\",\"query 2\"]"

        try {
            val url = URL("$baseUrl/chat/completions")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.doOutput = true
            conn.connectTimeout = 30000
            conn.readTimeout = 60000

            val body = JSONObject().apply {
                put("model", model)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
                })
                put("temperature", 1.0)
                put("max_tokens", 4096)
            }

            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

            val responseCode = conn.responseCode
            if (responseCode != 200) {
                conn.disconnect()
                return@withContext SearchTerms.getShuffled(count)
            }

            val response = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
            conn.disconnect()

            val json = JSONObject(response)
            val content = json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")

            val cleaned = content
                .replace("```json", "")
                .replace("```", "")
                .trim()

            val start = cleaned.indexOf('[')
            val end = cleaned.lastIndexOf(']')
            if (start == -1 || end == -1) {
                return@withContext SearchTerms.getShuffled(count)
            }

            val arr = JSONArray(cleaned.substring(start, end + 1))
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
}
