package com.newlychat.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.newlychat.app.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "messages")
data class ChatMessage(
    @PrimaryKey val id: String,
    val conversationId: String,
    val role: String,
    val content: String,
    val timestamp: Long,
    val provider: String,
    val model: String
)

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp")
    fun observeMessages(conversationId: String): Flow<List<ChatMessage>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: ChatMessage)
}

@Database(entities = [ChatMessage::class], version = 1, exportSchema = false)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao

    companion object {
        fun create(context: Context): ChatDatabase =
            Room.databaseBuilder(context, ChatDatabase::class.java, "newly-chat.db").build()
    }
}

class SecurePreferences(context: Context) {
    private val preferences = EncryptedSharedPreferences.create(
        context,
        "provider_secrets",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun getApiKey(provider: AiProviderType): String? = preferences.getString(provider.keyName, null)

    fun saveApiKey(provider: AiProviderType, apiKey: String) {
        preferences.edit().putString(provider.keyName, apiKey.trim()).apply()
    }

    fun deleteApiKey(provider: AiProviderType) {
        preferences.edit().remove(provider.keyName).apply()
    }

    fun activeProvider(): AiProviderType? =
        preferences.getString("active_provider", null)?.let { AiProviderType.valueOf(it) }

    fun setActiveProvider(provider: AiProviderType) {
        preferences.edit().putString("active_provider", provider.name).apply()
    }

    fun selectedModel(provider: AiProviderType): String =
        preferences.getString("model_${provider.name}", null) ?: provider.model

    fun setSelectedModel(provider: AiProviderType, model: String) {
        preferences.edit().putString("model_${provider.name}", model).apply()
    }

    fun getThemeMode(): ThemeMode =
        runCatching { ThemeMode.valueOf(preferences.getString("theme_mode", null).orEmpty()) }
            .getOrDefault(ThemeMode.SYSTEM)

    fun setThemeMode(mode: ThemeMode) {
        preferences.edit().putString("theme_mode", mode.name).apply()
    }

    fun getTemperature(): Double = preferences.getFloat("temperature", DEFAULT_TEMPERATURE.toFloat()).toDouble()

    fun setTemperature(value: Double) {
        preferences.edit().putFloat("temperature", value.toFloat()).apply()
    }

    fun getMaxTokens(): Int = preferences.getInt("max_tokens", DEFAULT_MAX_TOKENS)

    fun setMaxTokens(value: Int) {
        preferences.edit().putInt("max_tokens", value).apply()
    }

    companion object {
        const val DEFAULT_TEMPERATURE = 1.0
        const val DEFAULT_MAX_TOKENS = 2048
    }
}
