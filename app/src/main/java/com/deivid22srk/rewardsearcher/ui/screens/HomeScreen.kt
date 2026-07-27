package com.deivid22srk.rewardsearcher.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.deivid22srk.rewardsearcher.data.AISearchGenerator
import com.deivid22srk.rewardsearcher.data.LocalAIManager
import com.deivid22srk.rewardsearcher.service.SearchOverlayService
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    searchCount: Int,
    delayMs: Long,
    browser: String,
    searchPrefix: String,
    useAI: Boolean,
    aiUrl: String,
    aiModel: String,
    aiKey: String,
    showAIPreviews: Boolean,
    useLocalAI: Boolean,
    // Feature 1
    chromeUrlParams: String,
    // Feature 2
    dualBrowser: Boolean,
    bingCount: Int,
    chromeCount: Int,
    localAIManager: LocalAIManager,
    onDualBrowserChange: (Boolean) -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToChat: () -> Unit
) {
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scope = rememberCoroutineScope()

    // Real loading progress from the LocalAIManager (Feature 3).
    val isModelLoaded by localAIManager.isLoaded.collectAsState()
    val isModelLoading by localAIManager.isLoading.collectAsState()
    val modelLoadProgress by localAIManager.loadProgress.collectAsState()
    val modelLoadStage by localAIManager.loadStage.collectAsState()

    var count by remember { mutableFloatStateOf(searchCount.toFloat()) }
    var bingCountState by remember(bingCount) { mutableFloatStateOf(bingCount.toFloat()) }
    var chromeCountState by remember(chromeCount) { mutableFloatStateOf(chromeCount.toFloat()) }
    var prefix by remember(searchPrefix) { mutableStateOf(searchPrefix) }
    var showGenerateDialog by remember { mutableStateOf(false) }
    var generatedTerms by remember { mutableStateOf<List<String>>(emptyList()) }
    var isGenerating by remember { mutableStateOf(false) }
    var streamingText by remember { mutableStateOf("") }

    if (showGenerateDialog) {
        AlertDialog(
            onDismissRequest = { if (!isGenerating) showGenerateDialog = false },
            title = { Text("Gerar Pesquisas com IA") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Feature 3: real progress while the model is being loaded
                    // for generation. Replaces the previous "freezes for a few
                    // seconds" behaviour where the UI appeared hung.
                    if (useLocalAI && isModelLoading && !isModelLoaded) {
                        LinearProgressIndicator(
                            progress = { modelLoadProgress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = "${(modelLoadProgress * 100).roundToInt()}% — ${modelLoadStage.ifBlank { "Carregando…" }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (isGenerating) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text(
                                text = if (useLocalAI) "Gerando com IA local…" else "Gerando com IA…",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Card(
                            modifier = Modifier.fillMaxWidth().height(200.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                            )
                        ) {
                            LazyColumn(modifier = Modifier.padding(12.dp)) {
                                item {
                                    Text(
                                        text = streamingText,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    } else if (generatedTerms.isNotEmpty()) {
                        Text(
                            text = "${generatedTerms.size} pesquisas geradas:",
                            style = MaterialTheme.typography.titleSmall
                        )
                        LazyColumn(
                            modifier = Modifier.height(250.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            itemsIndexed(generatedTerms) { index, term ->
                                Text(
                                    text = "${index + 1}. $term",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (isGenerating) {
                    TextButton(onClick = {}) { Text("Aguarde…") }
                } else {
                    TextButton(onClick = { showGenerateDialog = false }) { Text("Fechar") }
                }
            },
            dismissButton = {
                if (!isGenerating && generatedTerms.isNotEmpty()) {
                    TextButton(onClick = {
                        showGenerateDialog = false
                        startService(
                            context = context,
                            count = count.roundToInt(),
                            delayMs = delayMs,
                            browser = browser,
                            prefix = prefix,
                            useAI = false,
                            aiUrl = aiUrl,
                            aiModel = aiModel,
                            aiKey = aiKey,
                            useLocalAI = useLocalAI,
                            pregeneratedTerms = generatedTerms,
                            chromeUrlParams = chromeUrlParams,
                            dualBrowser = dualBrowser,
                            bingCount = bingCountState.roundToInt(),
                            chromeCount = chromeCountState.roundToInt()
                        )
                    }) {
                        Text("Iniciar com estas")
                    }
                }
            }
        )
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(
                        text = "Reward Searcher",
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    // Feature 4: chat button — opens the ChatScreen.
                    IconButton(onClick = onNavigateToChat) {
                        Icon(Icons.Default.Chat, contentDescription = "Chat com IA local")
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Configurações")
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                shape = MaterialTheme.shapes.extraLarge
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.RocketLaunch,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = if (dualBrowser) {
                            "${bingCountState.roundToInt() + chromeCountState.roundToInt()} pesquisas"
                        } else {
                            "${count.roundToInt()} pesquisas"
                        },
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (dualBrowser) {
                            "${bingCountState.roundToInt()} Bing + ${chromeCountState.roundToInt()} Chrome"
                        } else {
                            "Microsoft Rewards via ${if (browser == "bing") "Bing" else "Chrome"}"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                    if (useAI) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.SmartToy,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            )
                            Text(
                                text = if (useLocalAI) "IA Local (GGUF)" else "IA em nuvem",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }

            // Feature 2: dual-browser toggle.
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                ),
                shape = MaterialTheme.shapes.extraLarge
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Modo duplo navegador",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "Faz N pesquisas no Bing e depois M no Chrome",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = dualBrowser,
                            onCheckedChange = onDualBrowserChange
                        )
                    }
                }
            }

            // Single-browser count slider (hidden in dual mode).
            AnimatedVisibility(visible = !dualBrowser) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Quantidade de pesquisas",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Slider(
                        value = count,
                        onValueChange = { count = it },
                        valueRange = 1f..100f
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("1", style = MaterialTheme.typography.bodySmall)
                        Text("100", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // Dual-browser count sliders (shown only in dual mode).
            AnimatedVisibility(visible = dualBrowser) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Pesquisas no Bing: ${bingCountState.roundToInt()}",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Slider(
                            value = bingCountState,
                            onValueChange = { bingCountState = it },
                            valueRange = 0f..100f
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Pesquisas no Chrome: ${chromeCountState.roundToInt()}",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Slider(
                            value = chromeCountState,
                            onValueChange = { chromeCountState = it },
                            valueRange = 0f..100f
                        )
                    }
                    Text(
                        text = "Ao concluir as pesquisas no Bing, o app continua automaticamente no Chrome.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            OutlinedTextField(
                value = prefix,
                onValueChange = { prefix = it },
                label = { Text("Prefixo (opcional)") },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large
            )

            if (useAI && showAIPreviews) {
                FilledTonalButton(
                    onClick = {
                        showGenerateDialog = true
                        isGenerating = true
                        generatedTerms = emptyList()
                        streamingText = ""
                        scope.launch {
                            val total = if (dualBrowser) {
                                bingCountState.roundToInt() + chromeCountState.roundToInt()
                            } else {
                                count.roundToInt()
                            }
                            if (useLocalAI) {
                                generatedTerms = localAIManager.generateSearches(total) { token ->
                                    streamingText += token
                                }
                            } else {
                                generatedTerms = AISearchGenerator.generate(
                                    total, aiUrl, aiModel, aiKey
                                )
                            }
                            isGenerating = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large
                ) {
                    Icon(Icons.Default.SmartToy, contentDescription = null)
                    Spacer(modifier = Modifier.size(8.dp))
                    Text("Gerar Pesquisas com IA")
                }
            }

            // Feature 4: quick chat button — alternative entry point to the
            // ChatScreen (in addition to the top-bar icon).
            if (useLocalAI) {
                FilledTonalButton(
                    onClick = onNavigateToChat,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large
                ) {
                    Icon(Icons.Default.Chat, contentDescription = null)
                    Spacer(modifier = Modifier.size(8.dp))
                    Text("Conversar com o modelo local")
                }
            }

            Button(
                onClick = {
                    if (!Settings.canDrawOverlays(context)) {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                        context.startActivity(intent)
                    } else {
                        startService(
                            context = context,
                            count = count.roundToInt(),
                            delayMs = delayMs,
                            browser = browser,
                            prefix = prefix,
                            useAI = useAI,
                            aiUrl = aiUrl,
                            aiModel = aiModel,
                            aiKey = aiKey,
                            useLocalAI = useLocalAI,
                            pregeneratedTerms = null,
                            chromeUrlParams = chromeUrlParams,
                            dualBrowser = dualBrowser,
                            bingCount = bingCountState.roundToInt(),
                            chromeCount = chromeCountState.roundToInt()
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = MaterialTheme.shapes.large,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(Icons.Default.RocketLaunch, contentDescription = null)
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = "Iniciar Pesquisas",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * Centralized helper that builds the foreground-service Intent with all the
 * extras the SearchOverlayService needs. Keeps the call sites (the "Iniciar
 * Pesquisas" button and the "Iniciar com estas" dialog button) in sync.
 */
private fun startService(
    context: android.content.Context,
    count: Int,
    delayMs: Long,
    browser: String,
    prefix: String,
    useAI: Boolean,
    aiUrl: String,
    aiModel: String,
    aiKey: String,
    useLocalAI: Boolean,
    pregeneratedTerms: List<String>?,
    chromeUrlParams: String,
    dualBrowser: Boolean,
    bingCount: Int,
    chromeCount: Int
) {
    val intent = Intent(context, SearchOverlayService::class.java).apply {
        putExtra(SearchOverlayService.EXTRA_SEARCH_COUNT, count)
        putExtra(SearchOverlayService.EXTRA_DELAY_MS, delayMs)
        putExtra(SearchOverlayService.EXTRA_BROWSER, browser)
        putExtra(SearchOverlayService.EXTRA_SEARCH_PREFIX, prefix)
        putExtra(SearchOverlayService.EXTRA_USE_AI, useAI)
        putExtra(SearchOverlayService.EXTRA_AI_URL, aiUrl)
        putExtra(SearchOverlayService.EXTRA_AI_MODEL, aiModel)
        putExtra(SearchOverlayService.EXTRA_AI_KEY, aiKey)
        putExtra(SearchOverlayService.EXTRA_USE_LOCAL_AI, useLocalAI)
        putExtra(SearchOverlayService.EXTRA_CHROME_URL_PARAMS, chromeUrlParams)
        putExtra(SearchOverlayService.EXTRA_DUAL_BROWSER, dualBrowser)
        putExtra(SearchOverlayService.EXTRA_BING_COUNT, bingCount)
        putExtra(SearchOverlayService.EXTRA_CHROME_COUNT, chromeCount)
        pregeneratedTerms?.let { putExtra(SearchOverlayService.EXTRA_PREGENERATED_TERMS, it.toTypedArray()) }
    }
    context.startForegroundService(intent)
}
