package com.newlychat.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.newlychat.app.data.AiProviderType
import com.newlychat.app.data.ChatMessage
import com.newlychat.app.data.MessageDao
import com.newlychat.app.data.ProviderFactory
import com.newlychat.app.data.ProviderMessage
import com.newlychat.app.data.SecurePreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val provider: AiProviderType = AiProviderType.CLAUDE,
    val model: String = AiProviderType.CLAUDE.model,
    val configuredProviders: Set<AiProviderType> = emptySet(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val availableModels: List<String> = emptyList(),
    val isLoadingModels: Boolean = false,
    val showModelSelector: Boolean = false
)

class ChatViewModel(
    private val messages: MessageDao,
    private val preferences: SecurePreferences,
    private val providers: ProviderFactory
) : ViewModel() {
    private val conversationId = "default"
    private var modelsJob: Job? = null
    private val initialProvider = preferences.activeProvider() ?: AiProviderType.CLAUDE
    private val _uiState = MutableStateFlow(
        ChatUiState(
            provider = initialProvider,
            model = preferences.selectedModel(initialProvider)
        )
    )
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            messages.observeMessages(conversationId).collect { rows ->
                _uiState.update { it.copy(messages = rows) }
            }
        }
        refreshConfiguredProviders()
    }

    fun saveApiKey(provider: AiProviderType, apiKey: String) {
        if (apiKey.isBlank()) preferences.deleteApiKey(provider) else preferences.saveApiKey(provider, apiKey)
        refreshConfiguredProviders()
    }

    fun selectProvider(provider: AiProviderType) {
        preferences.setActiveProvider(provider)
        _uiState.update {
            it.copy(
                provider = provider,
                model = preferences.selectedModel(provider),
                availableModels = emptyList(),
                showModelSelector = false,
                error = null
            )
        }
    }

    fun selectModel(model: String) {
        val provider = _uiState.value.provider
        preferences.setSelectedModel(provider, model)
        _uiState.update { it.copy(model = model, showModelSelector = false) }
    }

    fun toggleModelSelector() {
        val open = !_uiState.value.showModelSelector
        _uiState.update { it.copy(showModelSelector = open) }
        if (open) loadModels()
    }

    private fun loadModels() {
        val provider = _uiState.value.provider
        val key = preferences.getApiKey(provider)
        if (key.isNullOrBlank()) {
            _uiState.update {
                it.copy(
                    availableModels = emptyList(),
                    showModelSelector = false,
                    error = "Adicione uma API key para ${provider.label} em Configurações."
                )
            }
            return
        }
        modelsJob?.cancel()
        _uiState.update { it.copy(isLoadingModels = true, availableModels = emptyList()) }
        modelsJob = viewModelScope.launch {
            try {
                val models = providers.listModels(provider, key)
                _uiState.update { if (it.provider == provider) it.copy(availableModels = models) else it }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(error = "Erro ao carregar modelos: ${error.message}", showModelSelector = false)
                }
            } finally {
                _uiState.update { it.copy(isLoadingModels = false) }
            }
        }
    }

    fun sendMessage(text: String) {
        val content = text.trim()
        if (content.isEmpty() || _uiState.value.isLoading) return
        val providerType = _uiState.value.provider
        val model = _uiState.value.model
        val key = preferences.getApiKey(providerType)
        if (key.isNullOrBlank()) {
            _uiState.update { it.copy(error = "Adicione uma API key para ${providerType.label} em Configurações.") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val history = _uiState.value.messages.map { ProviderMessage(it.role, it.content) }
                val userMessage = ChatMessage(
                    UUID.randomUUID().toString(), conversationId, "user", content,
                    System.currentTimeMillis(), providerType.name, model
                )
                messages.insert(userMessage)
                val response = providers.create(providerType).sendMessage(
                    history + ProviderMessage("user", content),
                    key,
                    model,
                    preferences.getTemperature(),
                    preferences.getMaxTokens()
                )
                messages.insert(
                    ChatMessage(
                        UUID.randomUUID().toString(), conversationId, "assistant", response.content,
                        System.currentTimeMillis(), providerType.name, response.model
                    )
                )
                _uiState.update {
                    it.copy(
                        inputTokens = it.inputTokens + response.inputTokens,
                        outputTokens = it.outputTokens + response.outputTokens
                    )
                }
            } catch (error: Exception) {
                _uiState.update { it.copy(error = error.message ?: "Unable to send message.") }
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun refreshConfiguredProviders() {
        val provider = _uiState.value.provider
        _uiState.update {
            it.copy(
                model = preferences.selectedModel(provider),
                configuredProviders = AiProviderType.values().filterTo(mutableSetOf()) {
                    !preferences.getApiKey(it).isNullOrBlank()
                }
            )
        }
    }
}
