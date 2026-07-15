package dev.tclawyered.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.tclawyered.app.crypto.KeyVault
import dev.tclawyered.app.llm.LlmConfig
import dev.tclawyered.app.llm.Provider
import kotlinx.coroutines.flow.first

private val Context.dataStore by preferencesDataStore(name = "tc_lawyered_settings")

/**
 * Provider config + preferences (F-11, F-12), stored in DataStore. API keys are
 * encrypted with [KeyVault] before they touch disk and only decrypted transiently
 * right before a call — mirroring the extension's config.js guarantees.
 */
class SettingsRepository(private val context: Context) {

    private fun keyPref(p: Provider) = stringPreferencesKey("provider_${p.id}_key")
    private fun modelPref(p: Provider) = stringPreferencesKey("provider_${p.id}_model")

    /** Persist a provider's key (encrypted) + chosen model. */
    suspend fun saveProvider(provider: Provider, apiKey: String, model: String) {
        val enc = KeyVault.encrypt(apiKey)
        context.dataStore.edit { prefs ->
            prefs[keyPref(provider)] = enc
            prefs[modelPref(provider)] = model
        }
    }

    /** Set which provider is used for new summarizations. */
    suspend fun setActive(provider: Provider) {
        context.dataStore.edit { it[ACTIVE] = provider.id }
    }

    suspend fun getActive(): Provider? =
        Provider.fromId(context.dataStore.data.first()[ACTIVE])

    /** Resolve the active provider into a decrypted LlmConfig, or null. */
    suspend fun getActiveConfig(): LlmConfig? = getActive()?.let { getConfig(it) }

    /** Resolve a specific provider into a decrypted LlmConfig, or null. */
    suspend fun getConfig(provider: Provider): LlmConfig? {
        val prefs = context.dataStore.data.first()
        val enc = prefs[keyPref(provider)] ?: return null
        val model = prefs[modelPref(provider)] ?: provider.defaultModel
        return try {
            LlmConfig(provider, model, KeyVault.decrypt(enc))
        } catch (_: Exception) {
            null // corrupt/undecryptable key — treat as unconfigured
        }
    }

    /** UI snapshot: which providers are configured, their model, and the active one. */
    suspend fun snapshot(): List<ProviderState> {
        val prefs = context.dataStore.data.first()
        val active = Provider.fromId(prefs[ACTIVE])
        return Provider.entries.map { p ->
            ProviderState(
                provider = p,
                configured = prefs[keyPref(p)] != null,
                model = prefs[modelPref(p)] ?: p.defaultModel,
                active = p == active,
            )
        }
    }

    /* ------------------------------ prefs ------------------------------ */

    suspend fun hiveEnabled(): Boolean = context.dataStore.data.first()[HIVE_ENABLED] ?: true
    suspend fun setHiveEnabled(value: Boolean) {
        context.dataStore.edit { it[HIVE_ENABLED] = value }
    }

    suspend fun autoSummarize(): Boolean = context.dataStore.data.first()[AUTO_SUMMARIZE] ?: true
    suspend fun setAutoSummarize(value: Boolean) {
        context.dataStore.edit { it[AUTO_SUMMARIZE] = value }
    }

    /** Whether the user has acknowledged the one-time auto-scroll disclaimer. */
    suspend fun autoScrollAck(): Boolean = context.dataStore.data.first()[AUTO_SCROLL_ACK] ?: false
    suspend fun setAutoScrollAck(value: Boolean) {
        context.dataStore.edit { it[AUTO_SCROLL_ACK] = value }
    }

    companion object {
        private val ACTIVE = stringPreferencesKey("active_provider")
        private val HIVE_ENABLED = booleanPreferencesKey("hive_enabled")
        private val AUTO_SUMMARIZE = booleanPreferencesKey("auto_summarize")
        private val AUTO_SCROLL_ACK = booleanPreferencesKey("auto_scroll_ack")
    }
}

data class ProviderState(
    val provider: Provider,
    val configured: Boolean,
    val model: String,
    val active: Boolean,
)
