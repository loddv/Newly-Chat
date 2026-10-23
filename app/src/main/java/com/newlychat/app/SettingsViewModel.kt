package com.newlychat.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.newlychat.app.data.AiProviderType
import com.newlychat.app.data.ProviderFactory
import com.newlychat.app.data.SecurePreferences
import com.newlychat.app.ui.theme.ThemeMode
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
    val modelErrors: Map<AiProviderType, ErrorNotification> = emptyMap(),
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val temperature: Double = SecurePreferences.DEFAULT_TEMPERATURE,
    val maxTokens: Int = SecurePreferences.DEFAULT_MAX_TOKENS
)

class SettingsViewModel(
    private val preferences: SecurePreferences,
    private val providers: ProviderFactory
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        SettingsUiState(
            configuredProviders = loadConfiguredProviders(),
            selectedModels = AiProviderType.values().associateWith(preferences::selectedModel),
            themeMode = preferences.getThemeMode(),
            temperature = preferences.getTemperature(),
            maxTokens = preferences.getMaxTokens()
        )
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        preferences.setThemeMode(mode)
        _uiState.update { it.copy(themeMode = mode) }
    }

    fun setTemperature(value: Double) {
        val clamped = value.coerceIn(0.0, 2.0)
        preferences.setTemperature(clamped)
        _uiState.update { it.copy(temperature = clamped) }
    }

    fun setMaxTokens(value: Int) {
        if (value !in 1..10000) return
        preferences.setMaxTokens(value)
        _uiState.update { it.copy(maxTokens = value) }
    }

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
                state.copy(
                    modelErrors = state.modelErrors + (
                        provider to ErrorNotification("Configure uma API key antes de buscar modelos.")
                    )
                )
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
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update { state ->
                    state.copy(
                        modelErrors = state.modelErrors + (provider to error.toErrorNotification())
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
                state.copy(
                    modelErrors = state.modelErrors + (
                        provider to ErrorNotification("Selecione um modelo disponível.")
                    )
                )
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
