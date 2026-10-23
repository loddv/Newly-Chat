package com.newlychat.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AiProviderTypeTest {
    @Test
    fun providersExposeStableLabelsAndModels() {
        assertEquals(
            listOf("Claude", "OpenAI", "Gemini", "Mistral"),
            AiProviderType.values().map { it.label }
        )
        assertEquals(4, AiProviderType.values().map { it.keyName }.toSet().size)
        assertEquals(4, AiProviderType.values().map { it.model }.toSet().size)
    }
}
