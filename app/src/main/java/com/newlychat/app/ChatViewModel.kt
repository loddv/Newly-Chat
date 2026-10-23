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
    val error: ErrorNotification? = null,
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
    private data class FailedMessage(
        val content: String,
        val provider: AiProviderType,
        val model: String,
        val requestMessages: List<ProviderMessage>
    )

    private val conversationId = "default"
    private var modelsJob: Job? = null
    private var failedMessage: FailedMessage? = null
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
        failedMessage = null
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
                    error = ErrorNotification(
                        "Adicione uma API key para ${provider.label} em Configurações.",
                        ErrorAction.OPEN_SETTINGS
                    )
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
                val notification = error.toErrorNotification()
                _uiState.update {
                    it.copy(
                        error = notification.copy(
                            action = if (notification.action == ErrorAction.RETRY) {
                                ErrorAction.RETRY_MODELS
                            } else {
                                notification.action
                            }
                        ),
                        showModelSelector = false
                    )
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
            _uiState.update {
                it.copy(
                    error = ErrorNotification(
                        "Adicione uma API key para ${providerType.label} em Configurações.",
                        ErrorAction.OPEN_SETTINGS
                    )
                )
            }
            return
        }

        failedMessage = null
        viewModelScope.launch { submitMessage(content, providerType, model, key, addUserMessage = true) }
    }

    fun retryFailedMessage() {
        if (_uiState.value.isLoading) return
        val failed = failedMessage ?: return
        val key = preferences.getApiKey(failed.provider)
        if (key.isNullOrBlank()) {
            _uiState.update {
                it.copy(
                    error = ErrorNotification(
                        "Adicione uma API key para ${failed.provider.label} em Configurações.",
                        ErrorAction.OPEN_SETTINGS
                    )
                )
            }
            return
        }
        failedMessage = null
        viewModelScope.launch {
            submitMessage(
                failed.content,
                failed.provider,
                failed.model,
                key,
                addUserMessage = false,
                retryMessages = failed.requestMessages
            )
        }
    }

    fun retryModelLoading() {
        if (_uiState.value.isLoadingModels) return
        _uiState.update { it.copy(showModelSelector = true) }
        loadModels()
    }

    private suspend fun submitMessage(
        content: String,
        providerType: AiProviderType,
        model: String,
        key: String,
        addUserMessage: Boolean,
        retryMessages: List<ProviderMessage>? = null
    ) {
        _uiState.update { it.copy(isLoading = true, error = null) }
        var requestMessages = retryMessages.orEmpty()
        try {
            if (addUserMessage) {
                requestMessages = _uiState.value.messages.map { ProviderMessage(it.role, it.content) } +
                    ProviderMessage("user", content)
                messages.insert(
                    ChatMessage(
                        UUID.randomUUID().toString(), conversationId, "user", content,
                        System.currentTimeMillis(), providerType.name, model
                    )
                )
            }
            val response = providers.create(providerType).sendMessage(
                requestMessages,
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
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val notification = error.toErrorNotification()
            if (notification.action == ErrorAction.RETRY) {
                failedMessage = FailedMessage(content, providerType, model, requestMessages)
            }
            _uiState.update { it.copy(error = notification) }
        } finally {
            _uiState.update { it.copy(isLoading = false) }
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
