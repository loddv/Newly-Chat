package com.newlychat.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.newlychat.app.data.AiProviderType
import com.newlychat.app.data.ProviderFactory
import com.newlychat.app.data.SecurePreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val apiKeys: Map<AiProviderType, String> = emptyMap(),
    val configuredProviders: Set<AiProviderType> = emptySet(),
    val availableModels: Map<AiProviderType, List<String>> = emptyMap(),
    val selectedModels: Map<AiProviderType, String> = emptyMap(),
    val loadingModels: Set<AiProviderType> = emptySet(),
    val modelErrors: Map<AiProviderType, String> = emptyMap()
)

class SettingsViewModel(
    private val preferences: SecurePreferences,
    private val providers: ProviderFactory
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        SettingsUiState(
            configuredProviders = loadConfiguredProviders(),
            selectedModels = AiProviderType.values().associateWith(preferences::selectedModel)
        )
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    fun updateApiKey(provider: AiProviderType, apiKey: String) {
        _uiState.update { state -> state.copy(apiKeys = state.apiKeys + (provider to apiKey)) }
    }

    fun saveApiKey(provider: AiProviderType) {
        val apiKey = _uiState.value.apiKeys[provider].orEmpty()
        if (apiKey.isBlank()) return

        preferences.saveApiKey(provider, apiKey)
        _uiState.update { state ->
            state.copy(
                apiKeys = state.apiKeys + (provider to ""),
                configuredProviders = state.configuredProviders + provider,
                availableModels = state.availableModels - provider,
                modelErrors = state.modelErrors - provider
            )
        }
    }

    fun deleteApiKey(provider: AiProviderType) {
        preferences.deleteApiKey(provider)
        _uiState.update { state ->
            state.copy(
                apiKeys = state.apiKeys + (provider to ""),
                configuredProviders = state.configuredProviders - provider,
                availableModels = state.availableModels - provider,
                modelErrors = state.modelErrors - provider
            )
        }
    }

    fun loadModels(provider: AiProviderType) {
        val apiKey = preferences.getApiKey(provider)
        if (apiKey.isNullOrBlank()) {
            _uiState.update { state ->
                state.copy(modelErrors = state.modelErrors + (provider to "Configure uma API key antes de buscar modelos."))
            }
            return
        }
        if (provider in _uiState.value.loadingModels) return

        _uiState.update { state ->
            state.copy(
                loadingModels = state.loadingModels + provider,
                modelErrors = state.modelErrors - provider
            )
        }
        viewModelScope.launch {
            try {
                val models = providers.listModels(provider, apiKey)
                if (models.isEmpty()) {
                    throw IllegalStateException("Nenhum modelo de chat disponível para este provedor.")
                }
                _uiState.update { state ->
                    state.copy(availableModels = state.availableModels + (provider to models))
                }
            } catch (error: Exception) {
                _uiState.update { state ->
                    state.copy(
                        modelErrors = state.modelErrors + (
                            provider to (error.message ?: "Não foi possível carregar os modelos.")
                        )
                    )
                }
            } finally {
                _uiState.update { state ->
                    state.copy(loadingModels = state.loadingModels - provider)
                }
            }
        }
    }

    fun selectModel(provider: AiProviderType, model: String) {
        if (model !in _uiState.value.availableModels[provider].orEmpty()) {
            _uiState.update { state ->
                state.copy(modelErrors = state.modelErrors + (provider to "Selecione um modelo disponível."))
            }
            return
        }
        preferences.setSelectedModel(provider, model)
        _uiState.update { state ->
            state.copy(selectedModels = state.selectedModels + (provider to model))
        }
    }

    private fun loadConfiguredProviders(): Set<AiProviderType> =
        AiProviderType.values().filterTo(mutableSetOf()) {
            !preferences.getApiKey(it).isNullOrBlank()
        }
}
