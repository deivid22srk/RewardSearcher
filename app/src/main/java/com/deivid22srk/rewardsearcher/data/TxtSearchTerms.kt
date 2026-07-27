package com.deivid22srk.rewardsearcher.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Reads custom search queries from a user-selected .txt file.
 *
 * The file is opened via the Storage Access Framework (the URI is stored in
 * SettingsRepository.searchTxtUri and a persistable read permission is taken
 * at selection time so we can re-open it across process restarts).
 *
 * Queries are separated by line breaks. Blank lines and lines that look like
 * comments (starting with #) are skipped. Each line is trimmed.
 *
 * Returns the list of valid queries, or an empty list if the file cannot be
 * read or contains no usable lines.
 *
 * The caller is responsible for picking `count` random items (or all of
 * them if the file has fewer lines than requested) before passing them to
 * the SearchOverlayService as pregenerated terms.
 */
object TxtSearchTerms {

    /**
     * Reads and parses the .txt file at [uri].
     * Runs on Dispatchers.IO so it never blocks the UI thread.
     */
    suspend fun readAll(context: Context, uri: Uri): List<String> =
        withContext(Dispatchers.IO) {
            val result = mutableListOf<String>()
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                        var line = reader.readLine()
                        while (line != null) {
                            val trimmed = line.trim()
                            // Skip blank lines and comment lines (#).
                            if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                                result.add(trimmed)
                            }
                            line = reader.readLine()
                        }
                    }
                }
            } catch (_: Exception) {
                // Caller will see an empty list and can show an error.
            }
            result
        }

    /**
     * Convenience: read the file and return [count] randomly-shuffled entries.
     * If the file contains fewer than [count] lines, returns all of them
     * (shuffled). If the file is empty or unreadable, returns an empty list.
     */
    suspend fun readRandom(context: Context, uri: Uri, count: Int): List<String> {
        val all = readAll(context, uri)
        if (all.isEmpty()) return emptyList()
        val shuffled = all.shuffled()
        return if (shuffled.size >= count) shuffled.take(count) else shuffled
    }
}
