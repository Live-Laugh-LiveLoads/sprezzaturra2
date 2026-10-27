package com.ideaforge.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.ideaforge.core.json.AppJson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds [AppSettings] as one JSON document in plain SharedPreferences and exposes it as a
 * StateFlow. Reads are synchronous and cheap, so workers and the recorder read [current] directly.
 */
@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(load())

    val settings: StateFlow<AppSettings> = state.asStateFlow()
    val current: AppSettings get() = state.value

    fun update(transform: (AppSettings) -> AppSettings) {
        state.update { old ->
            val new = transform(old)
            prefs.edit().putString(KEY, AppJson.encodeToString(AppSettings.serializer(), new)).apply()
            new
        }
    }

    /** Raw JSON of the settings, for backups. Contains no secrets. */
    fun exportJson(): String = AppJson.encodeToString(AppSettings.serializer(), current)

    fun importJson(json: String) {
        val parsed = AppJson.decodeFromString(AppSettings.serializer(), json)
        update { parsed }
    }

    private fun load(): AppSettings {
        val json = prefs.getString(KEY, null) ?: return AppSettings()
        return try {
            AppJson.decodeFromString(AppSettings.serializer(), json)
        } catch (e: Exception) {
            Log.w(TAG, "Settings unreadable, using defaults: ${e.javaClass.simpleName}")
            AppSettings()
        }
    }

    private companion object {
        const val KEY = "app_settings"
        const val TAG = "Settings"
    }
}

/**
 * API keys in EncryptedSharedPreferences (AES-256, key in the Android Keystore).
 * Keys are never logged and never included in backups or exports.
 */
@Singleton
class SecretStore @Inject constructor(@ApplicationContext private val context: Context) {

    private val prefs: SharedPreferences by lazy { open() }
    private val version = MutableStateFlow(0)

    /** Bumps whenever a key changes, so UI can re-check "key set" badges. */
    val changes: StateFlow<Int> = version.asStateFlow()

    fun get(slot: String): String? = prefs.getString(slot, null)?.takeIf { it.isNotBlank() }

    fun has(slot: String): Boolean = get(slot) != null

    fun set(slot: String, value: String?) {
        prefs.edit().apply {
            if (value.isNullOrBlank()) remove(slot) else putString(slot, value.trim())
        }.apply()
        version.update { it + 1 }
    }

    private fun open(): SharedPreferences {
        val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return try {
            create(masterKey)
        } catch (e: Exception) {
            // A keystore reset (e.g. after a restore or OS update on some older phones) makes the old
            // file undecryptable. Start a fresh store; the user re-enters keys in Settings.
            Log.w("SecretStore", "Encrypted prefs unreadable, recreating: ${e.javaClass.simpleName}")
            context.deleteSharedPreferences(FILE)
            create(masterKey)
        }
    }

    private fun create(masterKey: MasterKey) = EncryptedSharedPreferences.create(
        context,
        FILE,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private companion object {
        const val FILE = "secrets"
    }
}
