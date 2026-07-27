package com.deivid22srk.rewardsearcher.ui.screens

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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
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
import androidx.compose.ui.platform.Localext
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
                inputStream?.use { input ->
                    tempFile.outputStream().use { output -> input.copyTo(output) }
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
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceainerHigh
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
