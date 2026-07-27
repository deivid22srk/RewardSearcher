package com.deivid22srk.rewardsearcher.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
    localAIManager: LocalAIManager,
    onNavigateToSettings: () -> Unit
) {
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scope = rememberCoroutineScope()

    var count by remember { mutableFloatStateOf(searchCount.toFloat()) }
    var prefix by remember { mutableStateOf(searchPrefix) }
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
                    if (isGenerating) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text(
                                text = if (useLocalAI) "Gerando com IA local..." else "Gerando com IA...",
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
                    TextButton(onClick = {}) { Text("Aguarde...") }
                } else {
                    TextButton(onClick = { showGenerateDialog = false }) { Text("Fechar") }
                }
            },
            dismissButton = {
                if (!isGenerating && generatedTerms.isNotEmpty()) {
                    TextButton(onClick = {
                        showGenerateDialog = false
                        val intent = Intent(context, SearchOverlayService::class.java).apply {
                            putExtra(SearchOverlayService.EXTRA_SEARCH_COUNT, count.roundToInt())
                            putExtra(SearchOverlayService.EXTRA_DELAY_MS, delayMs)
                            putExtra(SearchOverlayService.EXTRA_BROWSER, browser)
                            putExtra(SearchOverlayService.EXTRA_SEARCH_PREFIX, prefix)
                            putExtra(SearchOverlayService.EXTRA_USE_AI, false)
                            putExtra(SearchOverlayService.EXTRA_PREGENERATED_TERMS, generatedTerms.toTypedArray())
                        }
                        context.startForegroundService(intent)
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
                        text = "${count.roundToInt()} pesquisas",
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Microsoft Rewards via ${if (browser == "bing") "Bing" else "Chrome"}",
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
                            if (useLocalAI) {
                                generatedTerms = localAIManager.generateSearches(count.roundToInt()) { token ->
                                    streamingText += token
                                }
                            } else {
                                generatedTerms = AISearchGenerator.generate(
                                    count.roundToInt(), aiUrl, aiModel, aiKey
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

            Button(
                onClick = {
                    if (!Settings.canDrawOverlays(context)) {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                        context.startActivity(intent)
                    } else {
                        val intent = Intent(context, SearchOverlayService::class.java).apply {
                            putExtra(SearchOverlayService.EXTRA_SEARCH_COUNT, count.roundToInt())
                            putExtra(SearchOverlayService.EXTRA_DELAY_MS, delayMs)
                            putExtra(SearchOverlayService.EXTRA_BROWSER, browser)
                            putExtra(SearchOverlayService.EXTRA_SEARCH_PREFIX, prefix)
                            putExtra(SearchOverlayService.EXTRA_USE_AI, useAI)
                            putExtra(SearchOverlayService.EXTRA_AI_URL, aiUrl)
                            putExtra(SearchOverlayService.EXTRA_AI_MODEL, aiModel)
                            putExtra(SearchOverlayService.EXTRA_AI_KEY, aiKey)
                            putExtra(SearchOverlayService.EXTRA_USE_LOCAL_AI, useLocalAI)
                        }
                        context.startForegroundService(intent)
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
