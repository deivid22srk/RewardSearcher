package com.deivid22srk.rewardsearcher.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    companion object {
        val KEY_SEARCH_COUNT = intPreferencesKey("search_count")
        val KEY_DELAY_MS = longPreferencesKey("delay_ms")
        val KEY_BROWSER = stringPreferencesKey("browser")
        val KEY_SEARCH_PREFIX = stringPreferencesKey("search_prefix")
        val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val KEY_DARK_THEME = stringPreferencesKey("dark_theme")
        val KEY_USE_AI = booleanPreferencesKey("use_ai")
        val KEY_AI_URL = stringPreferencesKey("ai_url")
        val KEY_AI_MODEL = stringPreferencesKey("ai_model")
        val KEY_AI_KEY = stringPreferencesKey("ai_key")
        val KEY_SHOW_AI_PREVIEWS = booleanPreferencesKey("show_ai_previews")
        val KEY_USE_LOCAL_AI = booleanPreferencesKey("use_local_ai")
    }

    val searchCount: Flow<Int> = context.dataStore.data.map { it[KEY_SEARCH_COUNT] ?: 30 }
    val delayMs: Flow<Long> = context.dataStore.data.map { it[KEY_DELAY_MS] ?: 3000L }
    val browser: Flow<String> = context.dataStore.data.map { it[KEY_BROWSER] ?: "bing" }
    val searchPrefix: Flow<String> = context.dataStore.data.map { it[KEY_SEARCH_PREFIX] ?: "" }
    val dynamicColor: Flow<Boolean> = context.dataStore.data.map { it[KEY_DYNAMIC_COLOR] ?: true }
    val darkTheme: Flow<String> = context.dataStore.data.map { it[KEY_DARK_THEME] ?: "system" }
    val useAI: Flow<Boolean> = context.dataStore.data.map { it[KEY_USE_AI] ?: true }
    val aiUrl: Flow<String> = context.dataStore.data.map { it[KEY_AI_URL] ?: "" }
    val aiModel: Flow<String> = context.dataStore.data.map { it[KEY_AI_MODEL] ?: "" }
    val aiKey: Flow<String> = context.dataStore.data.map { it[KEY_AI_KEY] ?: "" }
    val showAIPreviews: Flow<Boolean> = context.dataStore.data.map { it[KEY_SHOW_AI_PREVIEWS] ?: false }
    val useLocalAI: Flow<Boolean> = context.dataStore.data.map { it[KEY_USE_LOCAL_AI] ?: false }

    suspend fun setSearchCount(value: Int) {
        context.dataStore.edit { it[KEY_SEARCH_COUNT] = value }
    }

    suspend fun setDelayMs(value: Long) {
        context.dataStore.edit { it[KEY_DELAY_MS] = value }
    }

    suspend fun setBrowser(value: String) {
        context.dataStore.edit { it[KEY_BROWSER] = value }
    }

    suspend fun setSearchPrefix(value: String) {
        context.dataStore.edit { it[KEY_SEARCH_PREFIX] = value }
    }

    suspend fun setDynamicColor(value: Boolean) {
        context.dataStore.edit { it[KEY_DYNAMIC_COLOR] = value }
    }

    suspend fun setDarkTheme(value: String) {
        context.dataStore.edit { it[KEY_DARK_THEME] = value }
    }

    suspend fun setUseAI(value: Boolean) {
        context.dataStore.edit { it[KEY_USE_AI] = value }
    }

    suspend fun setAiUrl(value: String) {
        context.dataStore.edit { it[KEY_AI_URL] = value }
    }

    suspend fun setAiModel(value: String) {
        context.dataStore.edit { it[KEY_AI_MODEL] = value }
    }

    suspend fun setAiKey(value: String) {
        context.dataStore.edit { it[KEY_AI_KEY] = value }
    }

    suspend fun setShowAIPreviews(value: Boolean) {
        context.dataStore.edit { it[KEY_SHOW_AI_PREVIEWS] = value }
    }

    suspend fun setUseLocalAI(value: Boolean) {
        context.dataStore.edit { it[KEY_USE_LOCAL_AI] = value }
    }
}
