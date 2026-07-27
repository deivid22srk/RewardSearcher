package com.deivid22srk.rewardsearcher.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.deivid22srk.rewardsearcher.MainActivity
import com.deivid22srk.rewardsearcher.data.AISearchGenerator
import com.deivid22srk.rewardsearcher.data.LocalAIManager
import com.deivid22srk.rewardsearcher.data.SearchTerms
import com.deivid22srk.rewardsearcher.ui.theme.RewardSearcherTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class SearchOverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    companion object {
        const val EXTRA_SEARCH_COUNT = "search_count"
        const val EXTRA_DELAY_MS = "delay_ms"
        const val EXTRA_BROWSER = "browser"
        const val EXTRA_SEARCH_PREFIX = "search_prefix"
        const val EXTRA_USE_AI = "use_ai"
        const val EXTRA_AI_URL = "ai_url"
        const val EXTRA_AI_MODEL = "ai_model"
        const val EXTRA_AI_KEY = "ai_key"
        const val EXTRA_USE_LOCAL_AI = "use_local_ai"
        const val EXTRA_PREGENERATED_TERMS = "pregenerated_terms"
        const val CHANNEL_ID = "search_overlay_channel"
        const val NOTIFICATION_ID = 1001

        // Feature 1: Chrome URL params (e.g. "PC=U316&FORM=CHROMN")
        const val EXTRA_CHROME_URL_PARAMS = "chrome_url_params"

        // Feature 2: dual-browser mode
        const val EXTRA_DUAL_BROWSER = "dual_browser"
        const val EXTRA_BING_COUNT = "bing_count"
        const val EXTRA_CHROME_COUNT = "chrome_count"

        // Chrome package used when the user selects the "Chrome" browser
        // or when the dual-browser workflow reaches the Chrome phase.
        private const val CHROME_PACKAGE = "com.android.chrome"
        private const val BING_PACKAGE = "com.microsoft.bing"
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var searchJob: Job? = null
    private var windowManager: WindowManager? = null
    private var overlayView: ComposeView? = null
    private var params: WindowManager.LayoutParams? = null

    private var currentIndex by mutableIntStateOf(0)
    private var totalCount by mutableIntStateOf(0)
    private var isPaused by mutableStateOf(false)
    private var isRunning by mutableStateOf(false)
    private var currentTerm by mutableStateOf("")
    private var currentBrowserLabel by mutableStateOf("")

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val count = intent?.getIntExtra(EXTRA_SEARCH_COUNT, 30) ?: 30
        val delayMs = intent?.getLongExtra(EXTRA_DELAY_MS, 3000L) ?: 3000L
        val browser = intent?.getStringExtra(EXTRA_BROWSER) ?: "bing"
        val prefix = intent?.getStringExtra(EXTRA_SEARCH_PREFIX) ?: ""
        val useAI = intent?.getBooleanExtra(EXTRA_USE_AI, true) ?: true
        val aiUrl = intent?.getStringExtra(EXTRA_AI_URL) ?: ""
        val aiModel = intent?.getStringExtra(EXTRA_AI_MODEL) ?: ""
        val aiKey = intent?.getStringExtra(EXTRA_AI_KEY) ?: ""
        val useLocalAI = intent?.getBooleanExtra(EXTRA_USE_LOCAL_AI, false) ?: false
        val pregeneratedTerms = intent?.getStringArrayExtra(EXTRA_PREGENERATED_TERMS)

        // Feature 1
        val chromeUrlParams = intent?.getStringExtra(EXTRA_CHROME_URL_PARAMS) ?: "PC=U316&FORM=CHROMN"

        // Feature 2
        val dualBrowser = intent?.getBooleanExtra(EXTRA_DUAL_BROWSER, false) ?: false
        val bingCount = intent?.getIntExtra(EXTRA_BING_COUNT, 20) ?: 20
        val chromeCount = intent?.getIntExtra(EXTRA_CHROME_COUNT, 30) ?: 30

        totalCount = if (dualBrowser) bingCount + chromeCount else count
        currentIndex = 0
        isPaused = false
        isRunning = true
        currentBrowserLabel = if (dualBrowser) "Bing" else browserLabel(browser)

        startForeground(NOTIFICATION_ID, createNotification())
        showOverlay()

        if (dualBrowser) {
            startDualBrowserSearches(
                bingCount, chromeCount, delayMs, prefix,
                useAI, aiUrl, aiModel, aiKey, useLocalAI,
                pregeneratedTerms, chromeUrlParams
            )
        } else {
            startSearches(
                count, delayMs, browser, prefix,
                useAI, aiUrl, aiModel, aiKey, useLocalAI,
                pregeneratedTerms, chromeUrlParams
            )
        }

        return START_NOT_STICKY
    }

    private fun browserLabel(browser: String): String =
        if (browser == "chrome") "Chrome" else "Bing"

    /**
     * Single-browser workflow (existing behaviour, now also threads the
     * Chrome URL params through to [performSearch]).
     */
    private fun startSearches(
        count: Int, delayMs: Long, browser: String, prefix: String,
        useAI: Boolean, aiUrl: String, aiModel: String, aiKey: String,
        useLocalAI: Boolean, pregeneratedTerms: Array<String>?,
        chromeUrlParams: String
    ) {
        searchJob = serviceScope.launch {
            val terms = when {
                pregeneratedTerms != null -> pregeneratedTerms.toList()
                useAI && useLocalAI -> {
                    currentTerm = "Gerando com IA local…"
                    val localAI = LocalAIManager(this@SearchOverlayService)
                    // fallbackOnError=true so the foreground service still
                    // has something to search for if the model fails to load
                    // (e.g. unsupported architecture, corrupt download). The
                    // failure reason is logged via LocalAIManager.loadError.
                    val generated = localAI.generateSearches(count, fallbackOnError = true) { token ->
                        currentTerm = token
                    }
                    if (generated.isEmpty()) {
                        android.util.Log.w("SearchOverlayService",
                            "Local AI generation returned empty; falling back to static pool. " +
                            "loadError=${localAI.loadError.value}")
                        SearchTerms.getShuffled(count)
                    } else {
                        generated
                    }
                }
                useAI -> {
                    currentTerm = "Gerando pesquisas com IA…"
                    AISearchGenerator.generate(count, aiUrl, aiModel, aiKey)
                }
                else -> {
                    currentTerm = "Carregando pesquisas…"
                    SearchTerms.getShuffled(count, prefix)
                }
            }

            for ((index, term) in terms.withIndex()) {
                while (isPaused && isActive) delay(200)
                if (!isActive) break

                currentIndex = index + 1
                currentTerm = term
                performSearch(term, browser, chromeUrlParams)
                delay(delayMs)
            }
            if (isActive) {
                isRunning = false
                stopSelf()
            }
        }
    }

    /**
     * Feature 2: dual-browser workflow.
     * Runs `bingCount` searches via the Bing app first, then `chromeCount`
     * searches via Chrome (with the user-configurable URL params). The
     * overlay's `currentBrowserLabel` is updated so the user always knows
     * which phase is active.
     *
     * If AI generation is enabled, the model produces the full combined
     * batch up front and the terms are split between the two phases. If
     * AI is disabled, the static term pool is shuffled and split.
     */
    private fun startDualBrowserSearches(
        bingCount: Int, chromeCount: Int, delayMs: Long, prefix: String,
        useAI: Boolean, aiUrl: String, aiModel: String, aiKey: String,
        useLocalAI: Boolean, pregeneratedTerms: Array<String>?,
        chromeUrlParams: String
    ) {
        searchJob = serviceScope.launch {
            val total = bingCount + chromeCount
            val allTerms = when {
                pregeneratedTerms != null && pregeneratedTerms.isNotEmpty() -> {
                    pregeneratedTerms.toList()
                }
                useAI && useLocalAI -> {
                    currentTerm = "Gerando com IA local…"
                    val localAI = LocalAIManager(this@SearchOverlayService)
                    val generated = localAI.generateSearches(total, fallbackOnError = true) { token ->
                        currentTerm = token
                    }
                    if (generated.isEmpty()) {
                        android.util.Log.w("SearchOverlayService",
                            "Local AI generation returned empty; falling back to static pool. " +
                            "loadError=${localAI.loadError.value}")
                        SearchTerms.getShuffled(total, prefix)
                    } else {
                        generated
                    }
                }
                useAI -> {
                    currentTerm = "Gerando pesquisas com IA…"
                    AISearchGenerator.generate(total, aiUrl, aiModel, aiKey)
                }
                else -> {
                    currentTerm = "Carregando pesquisas…"
                    SearchTerms.getShuffled(total, prefix)
                }
            }

            val bingTerms = allTerms.take(bingCount)
            val chromeTerms = allTerms.drop(bingCount).take(chromeCount)

            // Phase 1: Bing app
            currentBrowserLabel = "Bing"
            for ((index, term) in bingTerms.withIndex()) {
                while (isPaused && isActive) delay(200)
                if (!isActive) break
                currentIndex = index + 1
                currentTerm = term
                performSearch(term, "bing", chromeUrlParams)
                delay(delayMs)
            }

            // Phase 2: Chrome (with URL params)
            if (isActive && chromeTerms.isNotEmpty()) {
                currentBrowserLabel = "Chrome"
                for ((index, term) in chromeTerms.withIndex()) {
                    while (isPaused && isActive) delay(200)
                    if (!isActive) break
                    currentIndex = bingCount + index + 1
                    currentTerm = term
                    performSearch(term, "chrome", chromeUrlParams)
                    delay(delayMs)
                }
            }

            if (isActive) {
                isRunning = false
                stopSelf()
            }
        }
    }

    /**
     * Launches a Bing search in the chosen browser.
     *
     * For "bing"  → opens the Bing app (com.microsoft.bing) with a clean URL.
     * For "chrome" → opens Chrome (com.android.chrome) with the Bing search
     *                URL plus the user-configurable query params
     *                (default "PC=U316&FORM=CHROMN") so Microsoft Rewards
     *                credits the search to the Chrome-on-Bing flow.
     *
     * If the chosen app is not installed, falls back to the system default
     * browser via ACTION_VIEW without a package set.
     */
    private fun performSearch(term: String, browser: String, chromeUrlParams: String) {
        val encoded = Uri.encode(term)
        val params = chromeUrlParams.trim()
        val url = if (browser == "chrome" && params.isNotEmpty()) {
            "https://www.bing.com/search?q=$encoded&$params"
        } else {
            "https://www.bing.com/search?q=$encoded"
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        when (browser) {
            "bing" -> intent.setPackage(BING_PACKAGE)
            "chrome" -> intent.setPackage(CHROME_PACKAGE)
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            // Fallback: drop the package restriction and let the system
            // resolver pick any browser that can handle the URL.
            val fallback = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                startActivity(fallback)
            } catch (_: Exception) {}
        }
    }

    private fun showOverlay() {
        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@SearchOverlayService)
            setViewTreeSavedStateRegistryOwner(this@SearchOverlayService)

            setContent {
                RewardSearcherTheme {
                    Surface(
                        shape = RoundedCornerShape(28.dp),
                        tonalElevation = 8.dp,
                        shadowElevation = 8.dp,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "Reward Searcher",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary
                            )

                            if (currentBrowserLabel.isNotBlank()) {
                                Text(
                                    text = "Navegador: $currentBrowserLabel",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                if (isRunning) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 3.dp
                                    )
                                }
                                Text(
                                    text = if (isPaused) "Pausado" else "Pesquisando…",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            Text(
                                text = "$currentIndex / $totalCount",
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            Text(
                                text = currentTerm,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilledIconButton(
                                    onClick = { isPaused = !isPaused },
                                    colors = IconButtonDefaults.filledIconButtonColors(
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                                    )
                                ) {
                                    Icon(
                                        imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                        contentDescription = if (isPaused) "Continuar" else "Pausar"
                                    )
                                }

                                Spacer(modifier = Modifier.width(4.dp))

                                FilledIconButton(
                                    onClick = { stopSelf() },
                                    colors = IconButtonDefaults.filledIconButtonColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer
                                    )
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Cancelar"
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        val layoutFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlags,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 100
        }

        overlayView = composeView
        windowManager?.addView(composeView, params)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Search Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Notification for search overlay service"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Reward Searcher")
            .setContentText("Realizando pesquisas…")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        searchJob?.cancel()
        overlayView?.let { windowManager?.removeView(it) }
        overlayView = null
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }
}
