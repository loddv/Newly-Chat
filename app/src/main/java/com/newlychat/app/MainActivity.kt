package com.newlychat.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Divider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import com.newlychat.app.ui.chat.MessageContent
import com.newlychat.app.ui.theme.NewlyChatTheme
import com.newlychat.app.ui.theme.ThemeMode
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.newlychat.app.data.AiProviderType
import com.newlychat.app.data.ChatDatabase
import com.newlychat.app.data.ChatMessage
import com.newlychat.app.data.ProviderFactory
import com.newlychat.app.data.SecurePreferences

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val database = ChatDatabase.create(applicationContext)
        val preferences = SecurePreferences(applicationContext)
        val providerFactory = ProviderFactory()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
                modelClass.isAssignableFrom(ChatViewModel::class.java) ->
                    ChatViewModel(database.messageDao(), preferences, providerFactory)
                modelClass.isAssignableFrom(SettingsViewModel::class.java) ->
                    SettingsViewModel(preferences, providerFactory)
                else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
            } as T
        }
        setContent {
            val chatViewModel: ChatViewModel = viewModel(factory = factory)
            val settingsViewModel: SettingsViewModel = viewModel(factory = factory)
            val settingsState by settingsViewModel.uiState.collectAsState()
            NewlyChatTheme(themeMode = settingsState.themeMode) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ChatScreen(chatViewModel, settingsViewModel)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreen(viewModel: ChatViewModel, settingsViewModel: SettingsViewModel) {
    val state by viewModel.uiState.collectAsState()
    var input by remember { mutableStateOf("") }
    var showProviderMenu by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    if (showSettings) {
        val closeSettings = {
            viewModel.refreshConfiguredProviders()
            showSettings = false
        }
        BackHandler(onBack = closeSettings)
        SettingsScreen(
            viewModel = settingsViewModel,
            onClose = closeSettings
        )
    } else {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Row(
                            modifier = Modifier.clickable { viewModel.toggleModelSelector() }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(state.provider.label, fontWeight = FontWeight.Bold)
                                Text(
                                    state.model,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(" ▾", color = MaterialTheme.colorScheme.primary)
                        }
                    },
                    actions = {
                        TextButton(onClick = { showProviderMenu = true }) { Text("Provider") }
                        DropdownMenu(
                            expanded = showProviderMenu,
                            onDismissRequest = { showProviderMenu = false }
                        ) {
                            AiProviderType.values().forEach { provider ->
                                DropdownMenuItem(
                                    text = { Text("${provider.label}${if (provider in state.configuredProviders) "" else " · setup"}") },
                                    onClick = {
                                        viewModel.selectProvider(provider)
                                        showProviderMenu = false
                                    }
                                )
                            }
                        }
                        TextButton(onClick = { showSettings = true }) { Text("Configurações") }
                    }
                )
            }
        ) { padding ->
            if (state.showModelSelector) {
                ModelSelectorBottomSheet(
                    models = state.availableModels,
                    selectedModel = state.model,
                    isLoading = state.isLoadingModels,
                    onModelSelected = viewModel::selectModel,
                    onDismiss = viewModel::toggleModelSelector
                )
            }
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.messages, key = { it.id }) { message -> MessageBubble(message) }
                    if (state.isLoading) {
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator()
                                Text("  Thinking…")
                            }
                        }
                    }
                }
                state.error?.let { error ->
                    Text(error, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = viewModel::clearError) { Text("Dismiss") }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("Message") },
                        enabled = !state.isLoading,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
                    )
                    Button(
                        onClick = { viewModel.sendMessage(input); input = "" },
                        enabled = input.isNotBlank() && !state.isLoading,
                        modifier = Modifier.padding(start = 8.dp)
                    ) { Text("Send") }
                }
                Text(
                    "Tokens in ${state.inputTokens} · out ${state.outputTokens}",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelSelectorBottomSheet(
    models: List<String>,
    selectedModel: String,
    isLoading: Boolean,
    onModelSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            "Selecionar Modelo",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        if (isLoading) {
            Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(models) { model ->
                    val selected = model == selectedModel
                    Surface(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onModelSelected(model) },
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surface
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                model,
                                modifier = Modifier.weight(1f),
                                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurface
                            )
                            if (selected) Text("✓", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val userMessage = message.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (userMessage) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = if (userMessage) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(0.88f)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                MessageContent(
                    message.content,
                    color = if (userMessage) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!userMessage) {
                    Text(
                        "${message.provider} · ${message.model}",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    viewModel: SettingsViewModel,
    onClose: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Configurações") },
                navigationIcon = {
                    TextButton(onClick = onClose) { Text("Voltar") }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(
                    "Gerenciador de Provedores",
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    "As chaves são armazenadas criptografadas neste dispositivo.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            item {
                AppearanceCard(uiState.themeMode, viewModel::setThemeMode)
            }
            item {
                GeneralSettingsCards(
                    temperature = uiState.temperature,
                    maxTokens = uiState.maxTokens,
                    onTemperatureChange = viewModel::setTemperature,
                    onMaxTokensChange = viewModel::setMaxTokens
                )
            }
            items(AiProviderType.values()) { provider ->
                ProviderCard(
                    provider = provider,
                    apiKey = uiState.apiKeys[provider] ?: "",
                    isConfigured = provider in uiState.configuredProviders,
                    models = uiState.availableModels[provider].orEmpty(),
                    selectedModel = uiState.selectedModels[provider] ?: provider.model,
                    isLoadingModels = provider in uiState.loadingModels,
                    modelError = uiState.modelErrors[provider],
                    onApiKeyChange = { viewModel.updateApiKey(provider, it) },
                    onSave = { viewModel.saveApiKey(provider) },
                    onDelete = { viewModel.deleteApiKey(provider) },
                    onLoadModels = { viewModel.loadModels(provider) },
                    onSelectModel = { viewModel.selectModel(provider, it) }
                )
            }
        }
    }
}

@Composable
private fun ProviderCard(
    provider: AiProviderType,
    apiKey: String,
    isConfigured: Boolean,
    models: List<String>,
    selectedModel: String,
    isLoadingModels: Boolean,
    modelError: String?,
    onApiKeyChange: (String) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onLoadModels: () -> Unit,
    onSelectModel: (String) -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }
    var modelMenuExpanded by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(provider.label, style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (isConfigured) "Configurada" else "Não configurada",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isConfigured) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error
                    )
                }
                TextButton(onClick = { isExpanded = !isExpanded }) {
                    Text(if (isExpanded) "Recolher" else "Expandir")
                }
            }

            if (isExpanded) {
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = onApiKeyChange,
                    label = { Text("API key") },
                    placeholder = { Text("Digite para adicionar ou substituir") },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )
                Button(
                    onClick = onSave,
                    enabled = apiKey.isNotBlank() && !isLoadingModels,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Salvar")
                }
                TextButton(
                    onClick = onDelete,
                    enabled = isConfigured && !isLoadingModels,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Remover chave")
                }
                Text("Modelo de chat", style = MaterialTheme.typography.titleSmall)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = { modelMenuExpanded = true },
                        enabled = models.isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(selectedModel)
                    }
                    DropdownMenu(
                        expanded = modelMenuExpanded,
                        onDismissRequest = { modelMenuExpanded = false }
                    ) {
                        models.forEach { model ->
                            DropdownMenuItem(
                                text = { Text(model) },
                                onClick = {
                                    onSelectModel(model)
                                    modelMenuExpanded = false
                                }
                            )
                        }
                    }
                    TextButton(
                        onClick = onLoadModels,
                        enabled = isConfigured && !isLoadingModels
                    ) {
                        Text(if (isLoadingModels) "Carregando…" else "Buscar modelos")
                    }
                }
                modelError?.let { error ->
                    Text(error, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun AppearanceCard(themeMode: ThemeMode, onThemeMode: (ThemeMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Tema", style = MaterialTheme.typography.titleMedium)
                    Text(
                        themeMode.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(if (expanded) "▴" else "▾")
            }
            if (expanded) {
                Divider(modifier = Modifier.padding(vertical = 8.dp))
                ThemeMode.values().forEach { mode ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .clickable { onThemeMode(mode); expanded = false }
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(mode.label)
                        if (mode == themeMode) Text("✓", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun GeneralSettingsCards(
    temperature: Double,
    maxTokens: Int,
    onTemperatureChange: (Double) -> Unit,
    onMaxTokensChange: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Temperatura (Criatividade)", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Quanto maior, mais criativo. Quanto menor, mais focado.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Slider(
                        value = temperature.toFloat(),
                        onValueChange = { onTemperatureChange(it.toDouble()) },
                        valueRange = 0f..2f,
                        modifier = Modifier.weight(1f)
                    )
                    Text(String.format(java.util.Locale.US, "%.2f", temperature))
                }
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Máximo de Tokens", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Limite de tokens gerados por resposta (1–10000)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                var text by remember(maxTokens) { mutableStateOf(maxTokens.toString()) }
                OutlinedTextField(
                    value = text,
                    onValueChange = { input ->
                        text = input.filter(Char::isDigit).take(5)
                        text.toIntOrNull()?.let(onMaxTokensChange)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    suffix = { Text("tokens") }
                )
            }
        }
    }
}
