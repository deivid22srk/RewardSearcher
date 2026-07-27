package com.deivid22srk.rewardsearcher.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.OpenInBrowser
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.deivid22srk.rewardsearcher.data.LocalAIManager
import com.deivid22srk.rewardsearcher.data.ModelDownloadManager
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    delayMs: Long,
    browser: String,
    dynamicColor: Boolean,
    darkTheme: String,
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
    // Feature (TXT): custom search-queries file
    searchTxtUri: String,
    searchTxtName: String,
    localAIManager: LocalAIManager,
    downloadManager: ModelDownloadManager,
    onDelayChange: (Long) -> Unit,
    onBrowserChange: (String) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit,
    onDarkThemeChange: (String) -> Unit,
    onUseAIChange: (Boolean) -> Unit,
    onAiUrlChange: (String) -> Unit,
    onAiModelChange: (String) -> Unit,
    onAiKeyChange: (String) -> Unit,
    onShowAIPreviewsChange: (Boolean) -> Unit,
    onUseLocalAIChange: (Boolean) -> Unit,
    onChromeUrlParamsChange: (String) -> Unit,
    onDualBrowserChange: (Boolean) -> Unit,
    onBingCountChange: (Int) -> Unit,
    onChromeCountChange: (Int) -> Unit,
    onSearchTxtSelected: (uri: String, name: String) -> Unit,
    onSearchTxtCleared: () -> Unit,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var delay by remember { mutableFloatStateOf(delayMs / 1000f) }
    var customUrl by remember { mutableStateOf(aiUrl) }
    var customModel by remember { mutableStateOf(aiModel) }
    var customKey by remember { mutableStateOf(aiKey) }
    var showCustomFields by remember { mutableStateOf(aiUrl.isNotBlank() || aiModel.isNotBlank() || aiKey.isNotBlank()) }
    // Feature 1: editable mirror of the chrome URL params setting.
    var chromeParams by remember(chromeUrlParams) { mutableStateOf(chromeUrlParams) }
    // Feature 2: editable mirror of the dual-browser counts.
    var bingCountLocal by remember(bingCount) { mutableFloatStateOf(bingCount.toFloat()) }
    var chromeCountLocal by remember(chromeCount) { mutableFloatStateOf(chromeCount.toFloat()) }

    val downloadProgress by downloadManager.downloadProgress.collectAsState()
    val isDownloading by downloadManager.isDownloading.collectAsState()
    val downloadError by downloadManager.downloadError.collectAsState()
    var modelDownloaded by remember { mutableStateOf(localAIManager.isModelDownloaded()) }
    var importResult by remember { mutableStateOf<String?>(null) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val inputStream = context.contentResolver.openInputStream(it)
                val tempFile = File(context.cacheDir, "import_model.gguf")
                inputStream?.use { input: java.io.InputStream ->
                    tempFile.outputStream().use { output: java.io.OutputStream -> input.copyTo(output) }
                }
                val success = downloadManager.importModel(tempFile)
                tempFile.delete()
                modelDownloaded = success
                importResult = if (success) "Modelo importado com sucesso!" else "Erro ao importar modelo"
            } catch (_: Exception) {
                importResult = "Erro ao importar arquivo"
            }
        }
    }

    // TXT file picker for custom search queries. Uses OpenDocument (not
    // GetContent) so we can take a PERSISTABLE read permission — this lets
    // us re-open the file after the app process is killed without asking
    // the user again. The permission grant is taken inside the callback.
    val txtPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            try {
                // Take a persistable read grant so we can re-open this URI
                // across process restarts. The flag must match what we
                // declare in the launcher (FLAG_GRANT_READ_URI_PERMISSION).
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                try {
                    context.contentResolver.takePersistableUriPermission(uri, flags)
                } catch (_: SecurityException) {
                    // Some providers do not support persistable grants; we
                    // can still use the URI for the lifetime of this
                    // process, just not after a restart.
                }
                // Derive a friendly display name from the URI's last path
                // segment (works for most SAF providers including the
                // system Documents UI).
                val name = uri.lastPathSegment ?: "arquivo.txt"
                onSearchTxtSelected(uri.toString(), name)
            } catch (_: Exception) {
                // Silently ignore — the user can retry.
            }
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Configurações") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
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
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                ),
                shape = MaterialTheme.shapes.extraLarge
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "Pesquisas",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Intervalo entre pesquisas: ${delay.roundToInt()}s",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Slider(
                            value = delay,
                            onValueChange = {
                                delay = it
                                onDelayChange((it * 1000).toLong())
                            },
                            valueRange = 1f..15f
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Navegador",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            SegmentedButton(
                                selected = browser == "bing",
                                onClick = { onBrowserChange("bing") },
                                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                            ) {
                                Text("Bing")
                            }
                            SegmentedButton(
                                selected = browser == "chrome",
                                onClick = { onBrowserChange("chrome") },
                                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                            ) {
                                Text("Chrome")
                            }
                        }

                        // Feature 1: Chrome URL parameters.
                        // Visible whenever the single-browser mode is set to Chrome,
                        // and always visible when dual-browser mode is enabled (since
                        // dual mode always runs a Chrome phase).
                        AnimatedVisibility(visible = browser == "chrome" || dualBrowser) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        Icons.Default.OpenInBrowser,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "Parâmetros da URL do Chrome",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                                Text(
                                    text = "Anexado à URL do Bing quando a pesquisa abre no Chrome. Padrão: PC=U316&FORM=CHROMN",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                OutlinedTextField(
                                    value = chromeParams,
                                    onValueChange = {
                                        chromeParams = it
                                        onChromeUrlParamsChange(it)
                                    },
                                    placeholder = { Text("PC=U316&FORM=CHROMN") },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = MaterialTheme.shapes.large,
                                    singleLine = true
                                )
                            }
                        }
                    }

                    // Feature 2: dual-browser toggle + per-browser counts.
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Modo duplo navegador",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = "Faz N pesquisas no Bing e M no Chrome em sequência",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = dualBrowser,
                            onCheckedChange = onDualBrowserChange
                        )
                    }
                    AnimatedVisibility(visible = dualBrowser) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "Pesquisas no Bing: ${bingCountLocal.roundToInt()}",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Slider(
                                    value = bingCountLocal,
                                    onValueChange = {
                                        bingCountLocal = it
                                        onBingCountChange(it.roundToInt())
                                    },
                                    valueRange = 0f..100f
                                )
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "Pesquisas no Chrome: ${chromeCountLocal.roundToInt()}",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Slider(
                                    value = chromeCountLocal,
                                    onValueChange = {
                                        chromeCountLocal = it
                                        onChromeCountChange(it.roundToInt())
                                    },
                                    valueRange = 0f..100f
                                )
                            }
                        }
                    }
                }
            }

            // ─────────────────────────────────────────────────────────────
            // AI search-generation card — TEMPORARILY DISABLED.
            //
            // The local-AI and cloud-AI search generation features are
            // turned off in the UI for now (see the project worklog for
            // the rationale). We keep the entire Card rendered-but-hidden
            // so that:
            //   * the underlying code paths stay intact and can be
            //     re-enabled by flipping a single flag;
            //   * we do not have to remove the matching parameters from
            //     the SettingsScreen signature (which would cascade
            //     changes through MainActivity).
            // ─────────────────────────────────────────────────────────────
            AnimatedVisibility(visible = false) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                ),
                shape = MaterialTheme.shapes.extraLarge
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "Inteligência Artificial",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Gerar pesquisas com IA",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = "Pesquisas únicas e variadas geradas por IA",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = useAI,
                            onCheckedChange = onUseAIChange
                        )
                    }

                    AnimatedVisibility(visible = useAI) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Ver pesquisas geradas",
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Switch(
                                    checked = showAIPreviews,
                                    onCheckedChange = onShowAIPreviewsChange
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "IA Local (GGUF)",
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    Text(
                                        text = "Rodar modelo localmente no dispositivo",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = useLocalAI,
                                    onCheckedChange = onUseLocalAIChange
                                )
                            }

                            AnimatedVisibility(visible = useLocalAI) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                                        )
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(16.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(
                                                text = "Modelo: LFM2.5-230M-Q8_0",
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                            Text(
                                                text = if (modelDownloaded) "Status: Baixado ✓" else "Status: Não baixado",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = if (modelDownloaded)
                                                    MaterialTheme.colorScheme.primary
                                                else
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                            )

                                            if (isDownloading) {
                                                LinearProgressIndicator(
                                                    progress = { downloadProgress.coerceIn(0f, 1f) },
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                                Text(
                                                    text = "${(downloadProgress * 100).roundToInt()}%",
                                                    style = MaterialTheme.typography.bodySmall
                                                )
                                            }

                                            downloadError?.let {
                                                Text(
                                                    text = "Erro: $it",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.error
                                                )
                                            }

                                            importResult?.let {
                                                Text(
                                                    text = it,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }

                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                FilledTonalButton(
                                                    onClick = {
                                                        scope.launch {
                                                            val success = downloadManager.downloadModel()
                                                            modelDownloaded = success
                                                        }
                                                    },
                                                    enabled = !isDownloading
                                                ) {
                                                    if (isDownloading) {
                                                        CircularProgressIndicator(
                                                            modifier = Modifier.size(16.dp),
                                                            strokeWidth = 2.dp
                                                        )
                                                    } else {
                                                        Icon(
                                                            Icons.Default.Download,
                                                            contentDescription = null,
                                                            modifier = Modifier.size(16.dp)
                                                        )
                                                    }
                                                    Spacer(modifier = Modifier.size(4.dp))
                                                    Text("Baixar")
                                                }

                                                FilledTonalButton(
                                                    onClick = {
                                                        filePickerLauncher.launch("application/octet-stream")
                                                    },
                                                    enabled = !isDownloading
                                                ) {
                                                    Icon(
                                                        Icons.Default.FolderOpen,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                    Spacer(modifier = Modifier.size(4.dp))
                                                    Text("Importar")
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            AnimatedVisibility(visible = !useLocalAI) {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Usar API personalizada",
                                                style = MaterialTheme.typography.bodyLarge
                                            )
                                            Text(
                                                text = "Compatível com OpenAI",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Switch(
                                            checked = showCustomFields,
                                            onCheckedChange = {
                                                showCustomFields = it
                                                if (!it) {
                                                    customUrl = ""
                                                    customModel = ""
                                                    customKey = ""
                                                    onAiUrlChange("")
                                                    onAiModelChange("")
                                                    onAiKeyChange("")
                                                }
                                            }
                                        )
                                    }

                                    AnimatedVisibility(visible = showCustomFields) {
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedTextField(
                                                value = customUrl,
                                                onValueChange = {
                                                    customUrl = it
                                                    onAiUrlChange(it)
                                                },
                                                label = { Text("URL da API") },
                                                placeholder = { Text("https://api.openai.com/v1") },
                                                modifier = Modifier.fillMaxWidth(),
                                                shape = MaterialTheme.shapes.large,
                                                singleLine = true
                                            )
                                            OutlinedTextField(
                                                value = customModel,
                                                onValueChange = {
                                                    customModel = it
                                                    onAiModelChange(it)
                                                },
                                                label = { Text("Modelo") },
                                                placeholder = { Text("gpt-4o-mini") },
                                                modifier = Modifier.fillMaxWidth(),
                                                shape = MaterialTheme.shapes.large,
                                                singleLine = true
                                            )
                                            OutlinedTextField(
                                                value = customKey,
                                                onValueChange = {
                                                    customKey = it
                                                    onAiKeyChange(it)
                                                },
                                                label = { Text("API Key") },
                                                modifier = Modifier.fillMaxWidth(),
                                                shape = MaterialTheme.shapes.large,
                                                singleLine = true,
                                                visualTransformation = PasswordVisualTransformation()
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            } // end AnimatedVisibility(visible = false) — AI card disabled

            // ─────────────────────────────────────────────────────────────
            // TXT search-queries file card.
            //
            // Lets the user pick a .txt file containing one search query
            // per line. The URI + display name are persisted in
            // SettingsRepository so the HomeScreen can re-read the file
            // (and re-open it across process restarts thanks to the
            // persistable URI permission taken at selection time).
            // ─────────────────────────────────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                ),
                shape = MaterialTheme.shapes.extraLarge
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "Arquivo de pesquisas (.txt)",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Text(
                        text = "Selecione um arquivo de texto com as pesquisas que você " +
                            "quer usar. Cada linha vira uma pesquisa. Linhas em branco " +
                            "e linhas começando com # são ignoradas. Na tela inicial, " +
                            "o botão \"Sortear N pesquisas do TXT\" vai embaralhar o " +
                            "arquivo e usar as pesquisas sorteadas.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (searchTxtUri.isNotBlank()) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Article,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = "Arquivo selecionado:",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                                Text(
                                    text = searchTxtName.ifBlank { searchTxtUri },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilledTonalButton(
                            onClick = {
                                txtPickerLauncher.launch(arrayOf("text/plain", "application/octet-stream", "*/*"))
                            }
                        ) {
                            Icon(
                                Icons.Default.FolderOpen,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.size(4.dp))
                            Text(if (searchTxtUri.isNotBlank()) "Trocar arquivo" else "Selecionar arquivo")
                        }

                        if (searchTxtUri.isNotBlank()) {
                            FilledTonalButton(
                                onClick = onSearchTxtCleared
                            ) {
                                Text("Remover")
                            }
                        }
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                ),
                shape = MaterialTheme.shapes.extraLarge
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "Aparência",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Cor dinâmica (Material You)",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Switch(
                            checked = dynamicColor,
                            onCheckedChange = onDynamicColorChange
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Tema",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            val options = listOf("system" to "Sistema", "light" to "Claro", "dark" to "Escuro")
                            options.forEachIndexed { index, (value, label) ->
                                SegmentedButton(
                                    selected = darkTheme == value,
                                    onClick = { onDarkThemeChange(value) },
                                    shape = SegmentedButtonDefaults.itemShape(index = index, count = 3)
                                ) {
                                    Text(label)
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
