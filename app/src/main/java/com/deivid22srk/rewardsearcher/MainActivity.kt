package com.deivid22srk.rewardsearcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.deivid22srk.rewardsearcher.data.LocalAIManager
import com.deivid22srk.rewardsearcher.data.ModelDownloadManager
import com.deivid22srk.rewardsearcher.data.SettingsRepository
import com.deivid22srk.rewardsearcher.ui.screens.HomeScreen
import com.deivid22srk.rewardsearcher.ui.screens.SettingsScreen
import com.deivid22srk.rewardsearcher.ui.theme.RewardSearcherTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var settingsRepo: SettingsRepository
    private lateinit var localAIManager: LocalAIManager
    private lateinit var downloadManager: ModelDownloadManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        settingsRepo = SettingsRepository(this)
        localAIManager = LocalAIManager(this)
        downloadManager = ModelDownloadManager(this)

        setContent {
            val dynamicColor by settingsRepo.dynamicColor.collectAsState(initial = true)
            val darkThemePref by settingsRepo.darkTheme.collectAsState(initial = "system")
            val searchCount by settingsRepo.searchCount.collectAsState(initial = 30)
            val delayMs by settingsRepo.delayMs.collectAsState(initial = 3000L)
            val browser by settingsRepo.browser.collectAsState(initial = "bing")
            val searchPrefix by settingsRepo.searchPrefix.collectAsState(initial = "")
            val useAI by settingsRepo.useAI.collectAsState(initial = true)
            val aiUrl by settingsRepo.aiUrl.collectAsState(initial = "")
            val aiModel by settingsRepo.aiModel.collectAsState(initial = "")
            val aiKey by settingsRepo.aiKey.collectAsState(initial = "")
            val showAIPreviews by settingsRepo.showAIPreviews.collectAsState(initial = false)
            val useLocalAI by settingsRepo.useLocalAI.collectAsState(initial = false)

            val isDark = when (darkThemePref) {
                "light" -> false
                "dark" -> true
                else -> isSystemInDarkTheme()
            }

            RewardSearcherTheme(
                darkTheme = isDark,
                dynamicColor = dynamicColor
            ) {
                val navController = rememberNavController()

                NavHost(navController = navController, startDestination = "home") {
                    composable("home") {
                        HomeScreen(
                            searchCount = searchCount,
                            delayMs = delayMs,
                            browser = browser,
                            searchPrefix = searchPrefix,
                            useAI = useAI,
                            aiUrl = aiUrl,
                            aiModel = aiModel,
                            aiKey = aiKey,
                            showAIPreviews = showAIPreviews,
                            useLocalAI = useLocalAI,
                            localAIManager = localAIManager,
                            onNavigateToSettings = { navController.navigate("settings") }
                        )
                    }
                    composable("settings") {
                        SettingsScreen(
                            delayMs = delayMs,
                            browser = browser,
                            dynamicColor = dynamicColor,
                            darkTheme = darkThemePref,
                            useAI = useAI,
                            aiUrl = aiUrl,
                            aiModel = aiModel,
                            aiKey = aiKey,
                            showAIPreviews = showAIPreviews,
                            useLocalAI = useLocalAI,
                            localAIManager = localAIManager,
                            downloadManager = downloadManager,
                            onDelayChange = { v -> lifecycleScope.launch { settingsRepo.setDelayMs(v) } },
                            onBrowserChange = { v -> lifecycleScope.launch { settingsRepo.setBrowser(v) } },
                            onDynamicColorChange = { v -> lifecycleScope.launch { settingsRepo.setDynamicColor(v) } },
                            onDarkThemeChange = { v -> lifecycleScope.launch { settingsRepo.setDarkTheme(v) } },
                            onUseAIChange = { v -> lifecycleScope.launch { settingsRepo.setUseAI(v) } },
                            onAiUrlChange = { v -> lifecycleScope.launch { settingsRepo.setAiUrl(v) } },
                            onAiModelChange = { v -> lifecycleScope.launch { settingsRepo.setAiModel(v) } },
                            onAiKeyChange = { v -> lifecycleScope.launch { settingsRepo.setAiKey(v) } },
                            onShowAIPreviewsChange = { v -> lifecycleScope.launch { settingsRepo.setShowAIPreviews(v) } },
                            onUseLocalAIChange = { v -> lifecycleScope.launch { settingsRepo.setUseLocalAI(v) } },
                            onNavigateBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        localAIManager.freeModel()
    }
}
