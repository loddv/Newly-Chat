package com.newlychat.app

import com.newlychat.app.data.AiProviderType
import com.newlychat.app.data.ProviderApiException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorNotificationTest {
    @Test
    fun unavailable503OffersRetryWithHelpfulMessage() {
        val error = ProviderApiException.fromResponse(
            AiProviderType.GEMINI,
            503,
            """{"error":{"code":503,"message":"This model is currently experiencing high demand. Spikes in demand are usually temporary. Please try again later.","status":"UNAVAILABLE"}}"""
        )
        val notification = error.toErrorNotification()

        assertEquals("503", error.apiCode)
        assertEquals("UNAVAILABLE", error.apiStatus)
        assertEquals(ErrorAction.RETRY, notification.action)
        assertTrue(notification.message.contains("alta demanda"))
        assertTrue(notification.message.contains("Tente novamente"))
    }

    @Test
    fun rejectedApiKeyOffersSettingsAction() {
        val notification = ProviderApiException(
            httpStatus = 401,
            provider = AiProviderType.OPENAI,
            apiCode = "invalid_api_key",
            apiStatus = "UNAUTHENTICATED"
        ).toErrorNotification()

        assertEquals(ErrorAction.OPEN_SETTINGS, notification.action)
        assertTrue(notification.message.contains("API key"))
    }

    @Test
    fun networkErrorsOfferRetry() {
        assertEquals(ErrorAction.RETRY, IOException("socket timeout").toErrorNotification().action)
    }
}
