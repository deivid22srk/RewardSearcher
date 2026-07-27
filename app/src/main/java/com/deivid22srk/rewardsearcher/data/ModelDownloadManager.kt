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

    /**
     * Downloads a GGUF model file.
     *
     * HuggingFace serves the file via a 302 redirect to a CloudFront/S3
     * URL with a signed query string. The default HttpURLConnection
     * behaviour (instanceFollowRedirects = true) usually handles this,
     * but we:
     *   * Set an explicit User-Agent so HF does not rate-limit us as
     *     an unknown client.
     *   * Disable connection reuse so we always re-resolve after the
     *     redirect (avoids a class of truncation bugs seen on Android
     *     12+ where the redirect target connection is reused from a
     *     stale pool entry).
     *   * Validate the final downloaded size against Content-Length AND
     *     (for the default model) against the known expected size, so
     *     a truncated download is detected immediately rather than
     *     producing a "Failed to load model" error later.
     */
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
            connection.useCaches = false
            connection.setRequestProperty(
                "User-Agent",
                "RewardSearcher/1.0 (Android; model downloader)"
            )
            connection.connect()

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                _downloadError.value = "HTTP $responseCode ao baixar o modelo"
                _isDownloading.value = false
                _downloadProgress.value = -1f
                connection.disconnect()
                return@withContext false
            }

            val totalSize = connection.contentLengthLong
            if (totalSize <= 0) {
                _downloadError.value = "Servidor não informou o tamanho do arquivo (Content-Length ausente)"
                _isDownloading.value = false
                _downloadProgress.value = -1f
                connection.disconnect()
                return@withContext false
            }

            var downloaded = 0L
            var lastReportedPercent = -1

            connection.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(64 * 1024) // 64 KB — better throughput on HF CDN
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloaded += bytesRead
                        _downloadProgress.value = downloaded.toFloat() / totalSize
                        val pct = (downloaded * 100 / totalSize).toInt()
                        if (pct != lastReportedPercent && pct % 5 == 0) {
                            lastReportedPercent = pct
                        }
                    }
                }
            }

            connection.disconnect()

            // Size validation. Truncated downloads are the most common cause
            // of "Failed to load model" downstream — detect them here so we
            // can give the user a clear "tente novamente" message instead.
            if (downloaded != totalSize) {
                tempFile.delete()
                _downloadError.value = "Download incompleto: $downloaded de $totalSize bytes. Tente novamente."
                _isDownloading.value = false
                _downloadProgress.value = -1f
                return@withContext false
            }

            // For the default model, additionally verify the size matches
            // the known-good value (246598496 bytes for LFM2.5-230M-Q8_0).
            // This catches a subtle class of bugs where HF serves a partial
            // response with a wrong Content-Length header.
            if (fileName == LocalAIManager.DEFAULT_MODEL_NAME &&
                downloaded != LocalAIManager.DEFAULT_MODEL_SIZE
            ) {
                tempFile.delete()
                _downloadError.value = "Tamanho inesperado: $downloaded bytes (esperado ${LocalAIManager.DEFAULT_MODEL_SIZE}). " +
                    "O arquivo no servidor pode ter mudado — atualize o app."
                _isDownloading.value = false
                _downloadProgress.value = -1f
                return@withContext false
            }

            // Final check: read the GGUF magic and confirm the file is
            // actually a GGUF (not an HTML error page from a CDN).
            val magicBytes = ByteArray(4)
            try {
                tempFile.inputStream().use { it.read(magicBytes) }
            } catch (e: Exception) {
                tempFile.delete()
                _downloadError.value = "Não foi possível ler o arquivo baixado: ${e.message}"
                _isDownloading.value = false
                _downloadProgress.value = -1f
                return@withContext false
            }
            val magic = (magicBytes[0].toInt() and 0xff) or
                        ((magicBytes[1].toInt() and 0xff) shl 8) or
                        ((magicBytes[2].toInt() and 0xff) shl 16) or
                        ((magicBytes[3].toInt() and 0xff) shl 24)
            if (magic != 0x46554747) { // "GGUF" little-endian
                tempFile.delete()
                _downloadError.value = "Arquivo baixado não é um GGUF válido (magic=0x${magic.toString(16)}). " +
                    "Possível página de erro HTML — tente novamente."
                _isDownloading.value = false
                _downloadProgress.value = -1f
                return@withContext false
            }

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

            // Validate the imported file too — a wrong-format import (e.g.
            // a .bin or .safetensors) would otherwise fail silently later.
            val magicBytes = ByteArray(4)
            destFile.inputStream().use { it.read(magicBytes) }
            val magic = (magicBytes[0].toInt() and 0xff) or
                        ((magicBytes[1].toInt() and 0xff) shl 8) or
                        ((magicBytes[2].toInt() and 0xff) shl 16) or
                        ((magicBytes[3].toInt() and 0xff) shl 24)
            if (magic != 0x46554747) {
                destFile.delete()
                _downloadError.value = "Arquivo importado não é um GGUF válido (magic=0x${magic.toString(16)})"
                return false
            }
            _downloadError.value = null
            true
        } catch (e: Exception) {
            _downloadError.value = e.message ?: "Erro ao importar modelo"
            false
        }
    }
}
