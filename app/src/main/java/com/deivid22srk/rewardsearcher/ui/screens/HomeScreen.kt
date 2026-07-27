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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
import com.deivid22srk.rewardsearcher.data.TxtSearchTerms
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
    // AI parameters are kept in the signature so the surrounding state
    // pipeline does not change, but they are no longer surfaced in the UI
    // (the AI search-generation feature is temporarily disabled).
    @Suppress("UNUSED_PARAMETER") useAI: Boolean,
    @Suppress("UNUSED_PARAMETER") aiUrl: String,
    @Suppress("UNUSED_PARAMETER") aiModel: String,
    @Suppress("UNUSED_PARAMETER") aiKey: String,
    @Suppress("UNUSED_PARAMETER") showAIPreviews: Boolean,
    @Suppress("UNUSED_PARAMETER") useLocalAI: Boolean,
    // Feature 1
    chromeUrlParams: String,
    // Feature 2
    dualBrowser: Boolean,
    bingCount: Int,
    chromeCount: Int,
    // Feature (TXT): the URI of the user-selected .txt file with custom
    // search queries, plus its display name for the button label.
    searchTxtUri: String,
    searchTxtName: String,
    onDualBrowserChange: (Boolean) -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scope = rememberCoroutineScope()

    var count by remember { mutableFloatStateOf(searchCount.toFloat()) }
    var bingCountState by remember(bingCount) { mutableFloatStateOf(bingCount.toFloat()) }
    var chromeCountState by remember(chromeCount) { mutableFloatStateOf(chromeCount.toFloat()) }
    var prefix by remember(searchPrefix) { mutableStateOf(searchPrefix) }

    // State for the "use TXT" loading flow.
    var isTxtLoading by remember { mutableStateOf(false) }
    var txtError by remember { mutableStateOf<String?>(null) }
    var showTxtResultDialog by remember { mutableStateOf(false) }
    var txtLoadedTerms by remember { mutableStateOf<List<String>>(emptyList()) }

    if (showTxtResultDialog) {
        AlertDialog(
            onDismissRequest = { if (!isTxtLoading) showTxtResultDialog = false },
            title = { Text("Pesquisas do arquivo TXT") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (isTxtLoading) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                            Text(
                                text = "Lendo arquivo e sorteando pesquisas…",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    } else {
                        txtError?.let { err ->
                            Text(
                                text = err,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        if (txtLoadedTerms.isNotEmpty()) {
                            Text(
                                text = "${txtLoadedTerms.size} pesquisas sorteadas:",
                                style = MaterialTheme.typography.titleSmall
                            )
                            // Show up to 12 items so the dialog stays scrollable.
                            txtLoadedTerms.take(12).forEachIndexed { i, term ->
                                Text(
                                    text = "${i + 1}. $term",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            if (txtLoadedTerms.size > 12) {
                                Text(
                                    text = "… e mais ${txtLoadedTerms.size - 12}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (isTxtLoading) {
                    TextButton(onClick = {}) { Text("Aguarde…") }
                } else if (txtLoadedTerms.isNotEmpty()) {
                    TextButton(onClick = {
                        showTxtResultDialog = false
                        startService(
                            context = context,
                            delayMs = delayMs,
                            browser = browser,
                            chromeUrlParams = chromeUrlParams,
                            dualBrowser = dualBrowser,
                            bingCount = bingCountState.roundToInt(),
                            chromeCount = chromeCountState.roundToInt(),
                            pregeneratedTerms = txtLoadedTerms
                        )
                    }) { Text("Iniciar com estas") }
                } else {
                    TextButton(onClick = { showTxtResultDialog = false }) { Text("Fechar") }
                }
            },
            dismissButton = {
                if (!isTxtLoading && txtLoadedTerms.isNotEmpty()) {
                    TextButton(onClick = { showTxtResultDialog = false }) { Text("Fechar") }
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
                    // NOTE: the chat icon was removed from the top bar
                    // because the AI features are temporarily disabled.
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

            // New: "Use searches from TXT file" button.
            // Reads the user-selected .txt file (one query per line), shuffles
            // it, picks `count` (or bingCount+chromeCount in dual mode)
            // entries, shows a preview dialog, and lets the user start the
            // search service with those exact pregenerated terms.
            //
            // Disabled (with a hint message) when no TXT file has been
            // selected yet — the user must pick one in Settings first.
            val txtSelected = searchTxtUri.isNotBlank()
            val totalRequested = if (dualBrowser) {
                bingCountState.roundToInt() + chromeCountState.roundToInt()
            } else {
                count.roundToInt()
            }

            FilledTonalButton(
                onClick = {
                    if (!txtSelected) return@FilledTonalButton
                    val uri = Uri.parse(searchTxtUri)
                    isTxtLoading = true
                    txtError = null
                    txtLoadedTerms = emptyList()
                    showTxtResultDialog = true
                    scope.launch {
                        val terms = TxtSearchTerms.readRandom(context, uri, totalRequested)
                        isTxtLoading = false
                        if (terms.isEmpty()) {
                            txtError = "Não foi possível ler o arquivo. Verifique se ele " +
                                "ainda existe e contém linhas não vazias."
                        } else {
                            txtLoadedTerms = terms
                        }
                    }
                },
                enabled = txtSelected,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large
            ) {
                Icon(Icons.Default.Article, contentDescription = null)
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = if (txtSelected) {
                        "Sortear $totalRequested pesquisas do TXT"
                    } else {
                        "Selecione um arquivo TXT nas Configurações"
                    }
                )
            }
            if (txtSelected) {
                Text(
                    text = "Arquivo: $searchTxtName",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = "Nenhum arquivo TXT selecionado. Toque em Configurações " +
                        "(ícone de engrenagem no topo) para escolher um arquivo com " +
                        "suas pesquisas personalizadas — uma por linha.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
                            delayMs = delayMs,
                            browser = browser,
                            chromeUrlParams = chromeUrlParams,
                            dualBrowser = dualBrowser,
                            bingCount = bingCountState.roundToInt(),
                            chromeCount = chromeCountState.roundToInt(),
                            pregeneratedTerms = null
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
 * extras the SearchOverlayService needs.
 *
 * The AI-related extras (useAI, aiUrl, aiModel, aiKey, useLocalAI) are no
 * longer passed because the AI search-generation UI is temporarily
 * disabled. The service still accepts them (with safe defaults) for
 * backwards compatibility, but the only path that actually produces terms
 * now is `pregeneratedTerms`.
 */
private fun startService(
    context: android.content.Context,
    delayMs: Long,
    browser: String,
    chromeUrlParams: String,
    dualBrowser: Boolean,
    bingCount: Int,
    chromeCount: Int,
    pregeneratedTerms: List<String>?
) {
    val intent = Intent(context, SearchOverlayService::class.java).apply {
        // Single-browser count is derived from the dual counts when dual
        // mode is on, so the service has a sensible value either way.
        val singleCount = if (dualBrowser) (bingCount + chromeCount) else bingCount.coerceAtLeast(1)
        putExtra(SearchOverlayService.EXTRA_SEARCH_COUNT, singleCount)
        putExtra(SearchOverlayService.EXTRA_DELAY_MS, delayMs)
        putExtra(SearchOverlayService.EXTRA_BROWSER, browser)
        putExtra(SearchOverlayService.EXTRA_SEARCH_PREFIX, "")
        // AI disabled: force useAI=false so the service never tries to
        // call the (potentially not-loaded) local model or the cloud API.
        putExtra(SearchOverlayService.EXTRA_USE_AI, false)
        putExtra(SearchOverlayService.EXTRA_AI_URL, "")
        putExtra(SearchOverlayService.EXTRA_AI_MODEL, "")
        putExtra(SearchOverlayService.EXTRA_AI_KEY, "")
        putExtra(SearchOverlayService.EXTRA_USE_LOCAL_AI, false)
        putExtra(SearchOverlayService.EXTRA_CHROME_URL_PARAMS, chromeUrlParams)
        putExtra(SearchOverlayService.EXTRA_DUAL_BROWSER, dualBrowser)
        putExtra(SearchOverlayService.EXTRA_BING_COUNT, bingCount)
        putExtra(SearchOverlayService.EXTRA_CHROME_COUNT, chromeCount)
        pregeneratedTerms?.let {
            putExtra(SearchOverlayService.EXTRA_PREGENERATED_TERMS, it.toTypedArray())
        }
    }
    context.startForegroundService(intent)
}
