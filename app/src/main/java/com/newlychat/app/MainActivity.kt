package com.newlychat.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
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
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ChatViewModel(database.messageDao(), preferences, providerFactory) as T
        }
        setContent {
            val chatViewModel: ChatViewModel = viewModel(factory = factory)
            MaterialTheme {
                ChatScreen(chatViewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreen(viewModel: ChatViewModel) {
    val state by viewModel.uiState.collectAsState()
    var input by remember { mutableStateOf("") }
    var showProviderMenu by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.provider.label)
                        Text(state.provider.model, style = MaterialTheme.typography.labelSmall)
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
                    TextButton(onClick = { showSettings = true }) { Text("Keys") }
                }
            )
        }
    ) { padding ->
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
    if (showSettings) {
        ProviderSettings(
            configuredProviders = state.configuredProviders,
            onSave = viewModel::saveApiKey,
            onClose = { showSettings = false }
        )
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
                Text(message.content)
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

@Composable
private fun ProviderSettings(
    configuredProviders: Set<AiProviderType>,
    onSave: (AiProviderType, String) -> Unit,
    onClose: () -> Unit
) {
    var selected by remember { mutableStateOf(AiProviderType.CLAUDE) }
    var key by remember { mutableStateOf("") }
    var menuExpanded by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Provider API keys") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { menuExpanded = true }) { Text(selected.label) }
                    DropdownMenu(menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        AiProviderType.values().forEach { provider ->
                            DropdownMenuItem(
                                text = { Text(provider.label) },
                                onClick = { selected = provider; key = ""; menuExpanded = false }
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Text(if (selected in configuredProviders) "Configured" else "Not configured")
                }
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("API key (blank removes)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )
                Text(
                    "Keys are encrypted on this device. They are sent only to the selected provider.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(selected, key); onClose() }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Close") } }
    )
}
