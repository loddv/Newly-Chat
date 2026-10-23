package com.newlychat.app

import com.newlychat.app.data.ProviderApiException
import java.io.IOException

enum class ErrorAction {
    RETRY,
    RETRY_MODELS,
    OPEN_SETTINGS
}

data class ErrorNotification(
    val message: String,
    val action: ErrorAction? = null
)

fun Throwable.toErrorNotification(): ErrorNotification = when (this) {
    is ProviderApiException -> when {
        httpStatus == 503 || apiStatus.equals("UNAVAILABLE", ignoreCase = true) ->
            ErrorNotification(
                "O serviço do modelo está temporariamente indisponível devido à alta demanda. " +
                    "Tente novamente em alguns instantes.",
                ErrorAction.RETRY
            )
        httpStatus == 408 || httpStatus == 429 || httpStatus in 500..599 ->
            ErrorNotification(
                "O provedor está temporariamente indisponível ou ocupado. Tente novamente em alguns instantes.",
                ErrorAction.RETRY
            )
        httpStatus == 401 ->
            ErrorNotification("A API key foi recusada. Confira ou atualize a chave nas configurações.", ErrorAction.OPEN_SETTINGS)
        httpStatus == 403 ->
            ErrorNotification("O provedor recusou o acesso. Confira as permissões e a conta nas configurações.")
        httpStatus == 404 ->
            ErrorNotification("O modelo não foi encontrado. Selecione outro modelo nas configurações.")
        httpStatus == 400 || httpStatus == 422 ->
            ErrorNotification("A solicitação foi rejeitada. Confira o modelo e tente simplificar a mensagem.")
        httpStatus == 402 ->
            ErrorNotification("A conta do provedor precisa de atenção para continuar usando a API.")
        else ->
            ErrorNotification("Não foi possível concluir a solicitação (${provider.label}, HTTP $httpStatus).")
    }
    is IOException ->
        ErrorNotification("Não foi possível conectar ao provedor. Verifique sua conexão e tente novamente.", ErrorAction.RETRY)
    else -> ErrorNotification("Não foi possível concluir a solicitação.")
}
