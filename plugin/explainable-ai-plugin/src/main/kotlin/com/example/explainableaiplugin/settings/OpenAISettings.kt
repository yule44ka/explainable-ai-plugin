package com.example.explainableaiplugin.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.*

/**
 * Service for managing OpenAI API settings.
 * Uses PasswordSafe API for secure token storage.
 */
@Service
@State(
    name = "OpenAISettings",
    storages = [Storage("openai-settings.xml")]
)
class OpenAISettings : PersistentStateComponent<OpenAISettings.State> {
    
    data class State(
        var apiEndpoint: String = "https://api.openai.com/v1",
        var model: String = "gpt-4.1-nano",
        var temperature: Double = 0.7,
        var maxTokens: Int = 2000
    )
    
    private var state = State()
    
    companion object {
        private const val CREDENTIAL_SUBSYSTEM = "ExplainableAIPlugin"
        private const val CREDENTIAL_KEY = "OpenAI_API_Key"
        
        fun getInstance(): OpenAISettings = service()
        
        private fun createCredentialAttributes(): CredentialAttributes {
            return CredentialAttributes(
                generateServiceName(CREDENTIAL_SUBSYSTEM, CREDENTIAL_KEY)
            )
        }
    }
    
    override fun getState(): State = state
    
    override fun loadState(state: State) {
        this.state = state
    }
    
    /**
     * Get API key from secure storage
     */
    fun getApiKey(): String? {
        return PasswordSafe.instance.getPassword(createCredentialAttributes())
    }
    
    /**
     * Save API key to secure storage
     */
    fun setApiKey(apiKey: String?) {
        val credentialAttributes = createCredentialAttributes()
        if (apiKey.isNullOrEmpty()) {
            PasswordSafe.instance.set(credentialAttributes, null)
        } else {
            val credentials = Credentials(CREDENTIAL_KEY, apiKey)
            PasswordSafe.instance.set(credentialAttributes, credentials)
        }
    }
    
    /**
     * Check if API key is configured
     */
    fun isApiKeyConfigured(): Boolean {
        return !getApiKey().isNullOrEmpty()
    }
    
    /**
     * Clear all settings
     */
    fun clear() {
        setApiKey(null)
        state = State()
    }
    
    // Getters and setters for other settings
    
    var apiEndpoint: String
        get() = state.apiEndpoint
        set(value) {
            state.apiEndpoint = value
        }
    
    var model: String
        get() = state.model
        set(value) {
            state.model = value
        }
    
    var temperature: Double
        get() = state.temperature
        set(value) {
            state.temperature = value.coerceIn(0.0, 2.0)
        }
    
    var maxTokens: Int
        get() = state.maxTokens
        set(value) {
            state.maxTokens = value.coerceIn(1, 32000)
        }
}
