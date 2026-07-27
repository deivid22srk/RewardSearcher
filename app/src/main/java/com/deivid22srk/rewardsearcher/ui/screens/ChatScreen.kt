package com.deivid22srk.rewardsearcher.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.deivid22srk.rewardsearcher.data.LocalAIManager
import kotlinx.coroutines.launch

/**
 * Feature 4: Chat screen.
 *
 * Opens a separate screen where the user can have a multi-turn conversation
 * with the locally-loaded GGUF model. The model's embedded chat template is
 * applied inside the JNI layer, so any GGUF model that ships a template
 * (LFM2.5-230M uses ChatML) renders correctly.
 *
 * If the model is not yet loaded, the screen kicks off [LocalAIManager.loadModelAsync]
 * and shows a real progress bar — see Feature 3.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    localAIManager: LocalAIManager,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val isLoaded by localAIManager.isLoaded.collectAsState()
    val isLoading by localAIManager.isLoading.collectAsState()
    val loadProgress by localAIManager.loadProgress.collectAsState()
    val loadStage by localAIManager.loadStage.collectAsState()
    val loadError by localAIManager.loadError.collectAsState()
    val isGenerating by localAIManager.isGenerating.collectAsState()

    // Conversation history shown in the UI. Each entry is either a user
    // turn (sent by the user) or an assistant turn (streamed from the model).
    val messages = remember { mutableStateListOf<LocalAIManager.ChatMessage>() }

    // While the assistant is streaming, we accumulate tokens into a single
    // mutable entry so the UI updates on every token without rebuilding
    // the whole list.
    var streamingText by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Kick off model loading as soon as the screen opens if it has not been
    // loaded yet. This runs off the main thread and reports progress via
    // loadProgress / loadStage — the UI shows a real progress bar instead
    // of freezing.
    LaunchedEffect(Unit) {
        if (!isLoaded && !isLoading) {
            scope.launch { localAIManager.loadModelAsync() }
        }
    }

    // Auto-scroll to the latest message whenever the list grows or the
    // streaming text changes.
    LaunchedEffect(messages.size, streamingText) {
        if (messages.isNotEmpty() || streamingText.isNotEmpty()) {
            listState.animateScrollToItem(messages.size + if (streamingText.isNotEmpty()) 1 else 0)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Chat Local", fontWeight = FontWeight.SemiBold)
                        Text(
                            text = when {
                                isGenerating -> "Gerando resposta…"
                                isLoading -> loadStage.ifBlank { "Carregando…" }
                                isLoaded -> "Modelo pronto"
                                else -> "Aguardando modelo"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
                    }
                }
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 2.dp,
                shadowElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(12.dp)
                ) {
                    if (isLoading && !isLoaded) {
                        // Feature 3: real loading progress bar replaces the
                        // old "freezes for a few seconds" behaviour.
                        LinearProgressIndicator(
                            progress = { loadProgress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                        )
                        Text(
                            text = loadStage.ifBlank { "Carregando modelo…" },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            placeholder = { Text("Pergunte algo ao modelo…") },
                            modifier = Modifier.weight(1f),
                            enabled = isLoaded && !isGenerating && !isLoading,
                            maxLines = 4
                        )
                        IconButton(
                            onClick = {
                                if (input.isBlank() || isGenerating || !isLoaded) return@IconButton
                                val userText = input.trim()
                                input = ""
                                messages.add(LocalAIManager.ChatMessage("user", userText))
                                streamingText = ""

                                val historyForModel = messages.toList()
                                scope.launch {
                                    val reply = localAIManager.chat(
                                        history = historyForModel,
                                        maxTokens = 512,
                                        temperature = 0.7f,
                                        onToken = { token -> streamingText += token }
                                    )
                                    val finalReply = reply.ifBlank { streamingText.trim() }
                                    if (finalReply.isNotBlank()) {
                                        messages.add(LocalAIManager.ChatMessage("assistant", finalReply))
                                    }
                                    streamingText = ""
                                }
                            },
                            enabled = isLoaded && !isGenerating && !isLoading && input.isNotBlank()
                        ) {
                            if (isGenerating) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Enviar")
                            }
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        if (messages.isEmpty() && streamingText.isEmpty()) {
            // Empty state.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Converse com o modelo local",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (isLoaded) {
                        "O modelo está pronto. Envie a primeira mensagem abaixo."
                    } else if (isLoading) {
                        "Carregando o modelo… aguarde alguns segundos."
                    } else {
                        "Toque para carregar o modelo local e iniciar o chat."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (isLoading) {
                    Spacer(modifier = Modifier.height(16.dp))
                    LinearProgressIndicator(
                        progress = { loadProgress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "${(loadProgress * 100).toInt()}% — ${loadStage.ifBlank { "" }}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (!isLoaded) {
                    Spacer(modifier = Modifier.height(16.dp))
                    androidx.compose.material3.FilledTonalButton(
                        onClick = { scope.launch { localAIManager.loadModelAsync() } }
                    ) {
                        Text("Carregar modelo")
                    }
                }

                // Surface any load error prominently so the user knows
                // exactly why the model is not running instead of guessing.
                loadError?.let { err ->
                    Spacer(modifier = Modifier.height(16.dp))
                    androidx.compose.material3.Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = androidx.compose.material3.CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Não foi possível carregar o modelo",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = err,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Dica: modelos LFM2 (como o LFM2.5-230M) exigem " +
                                    "llama.cpp >= b6000. O app já está configurado com " +
                                    "uma versão compatível; se ainda assim falhar, " +
                                    "verifique o logcat (tag LlamaJNI) para a arquitetura " +
                                    "do GGUF.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            }
            return@Scaffold
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages) { msg ->
                ChatBubble(role = msg.role, text = msg.content)
            }
            if (streamingText.isNotEmpty()) {
                item {
                    ChatBubble(role = "assistant", text = streamingText, streaming = true)
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(role: String, text: String, streaming: Boolean = false) {
    val isUser = role == "user"
    val containerColor = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val textColor = if (isUser) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .background(
                    color = containerColor,
                    shape = RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (isUser) 16.dp else 4.dp,
                        bottomEnd = if (isUser) 4.dp else 16.dp
                    )
                )
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Column {
                Text(
                    text = text + if (streaming) " ▍" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColor
                )
            }
        }
    }
}
