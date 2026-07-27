package com.deivid22srk.rewardsearcher.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class ModelDownloadManager(private val context: Context) {

    private val _downloadProgress = MutableStateFlow(-1f)
    val downloadProgress: StateFlow<Float> = _downloadProgress

    private val _isDownloading = MutableStateFlow(false)
    val isDownloading: StateFlow<Boolean> = _isDownloading

    private val _downloadError = MutableStateFlow<String?>(null)
    val downloadError: StateFlow<String?> = _downloadError

    suspend fun downloadModel(
        url: String = LocalAIManager.DEFAULT_MODEL_URL,
        fileName: String = LocalAIManager.DEFAULT_MODEL_NAME
    ): Boolean = withContext(Dispatchers.IO) {
        _isDownloading.value = true
        _downloadProgress.value = 0f
        _downloadError.value = null

        try {
            val dir = File(context.filesDir, "models")
            if (!dir.exists()) dir.mkdirs()
            val outFile = File(dir, fileName)
            val tempFile = File(dir, "$fileName.tmp")

            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 30000
            connection.readTimeout = 60000
            connection.instanceFollowRedirects = true
            connection.connect()

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                _downloadError.value = "HTTP $responseCode"
                _isDownloading.value = false
                _downloadProgress.value = -1f
                return@withContext false
            }

            val totalSize = connection.contentLengthLong
            var downloaded = 0L

            connection.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloaded += bytesRead
                        if (totalSize > 0) {
                            _downloadProgress.value = downloaded.toFloat() / totalSize
                        }
                    }
                }
            }

            connection.disconnect()
            tempFile.renameTo(outFile)
            _downloadProgress.value = 1f
            _isDownloading.value = false
            true
        } catch (e: Exception) {
            _downloadError.value = e.message ?: "Download failed"
            _isDownloading.value = false
            _downloadProgress.value = -1f
            false
        }
    }

    fun importModel(sourceFile: File, fileName: String = LocalAIManager.DEFAULT_MODEL_NAME): Boolean {
        return try {
            val dir = File(context.filesDir, "models")
            if (!dir.exists()) dir.mkdirs()
            val destFile = File(dir, fileName)
            sourceFile.copyTo(destFile, overwrite = true)
            true
        } catch (_: Exception) {
            false
        }
    }
}
