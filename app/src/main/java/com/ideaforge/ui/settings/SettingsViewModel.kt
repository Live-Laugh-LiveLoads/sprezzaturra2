package com.ideaforge.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ideaforge.audio.AudioImporter
import com.ideaforge.core.agents.AgentId
import com.ideaforge.core.agents.PromptFile
import com.ideaforge.data.RoomUsageLedger
import com.ideaforge.data.db.AppDatabase
import com.ideaforge.export.BackupManager
import com.ideaforge.prompts.AssetPromptStore
import com.ideaforge.providers.ProviderFactory
import com.ideaforge.settings.AppSettings
import com.ideaforge.settings.LlmProfile
import com.ideaforge.settings.SecretStore
import com.ideaforge.settings.SettingsRepository
import com.ideaforge.whisper.WhisperModelManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** Result of the "Test" button on a provider. */
internal sealed interface TestState {
    data object Running : TestState
    data class Ok(val modelCount: Int) : TestState
    data class Failed(val message: String) : TestState
}

/** The "pick from list" dialog of the agents section. */
internal data class ModelPicker(
    val agent: AgentId,
    val loading: Boolean = true,
    val models: List<String> = emptyList(),
    val error: String? = null,
)

/** Progress of a Whisper model download; [error] is set when the last attempt failed. */
internal data class DownloadState(val progress: Float = 0f, val error: String? = null)

internal data class PromptRow(val agent: AgentId, val version: String, val customized: Boolean)

/** Outcome of the long-running actions in the Data section. */
internal sealed interface DataOp {
    data object Idle : DataOp
    data class Working(val label: String) : DataOp
    data class Done(val message: String) : DataOp
    data class Failed(val message: String) : DataOp
}

/**
 * State and actions for every settings section. The section being shown comes from the
 * navigation argument `section`. Setting changes go straight to [SettingsRepository], which saves
 * immediately; long operations (downloads, backup) run in [viewModelScope] so they survive rotation.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repo: SettingsRepository,
    private val secrets: SecretStore,
    private val providers: ProviderFactory,
    private val whisper: WhisperModelManager,
    private val prompts: AssetPromptStore,
    private val backups: BackupManager,
    private val importer: AudioImporter,
    db: AppDatabase,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val section: String = savedState.get<String>("section").orEmpty()

    val settings: StateFlow<AppSettings> = repo.settings

    fun update(transform: (AppSettings) -> AppSettings) = repo.update(transform)

    // ---- keys and providers ------------------------------------------------------------------

    /** Slot id to masked tail ("••••1234") for every slot that has a key. */
    val keyHints: StateFlow<Map<String, String>> = combine(secrets.changes, repo.settings) { _, s ->
        s.llmProfiles.map { it.id } + AppSettings.TRANSCRIPTION_KEY
    }.map { slots ->
        slots.mapNotNull { slot -> secrets.get(slot)?.let { slot to "••••" + it.takeLast(4) } }.toMap()
    }.flowOn(Dispatchers.IO) // the first read opens the keystore, which is slow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun saveKey(slot: String, key: String) = secrets.set(slot, key)

    fun clearKey(slot: String) = secrets.set(slot, null)

    private val _tests = MutableStateFlow<Map<String, TestState>>(emptyMap())
    internal val tests: StateFlow<Map<String, TestState>> = _tests

    /** Calls the endpoint's model list with the saved key; success proves URL and key work. */
    fun testProfile(profile: LlmProfile) {
        _tests.update { it + (profile.id to TestState.Running) }
        viewModelScope.launch {
            val result = try {
                TestState.Ok(providers.llm(profile).listModels().size)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TestState.Failed(e.message ?: e.javaClass.simpleName)
            }
            _tests.update { it + (profile.id to result) }
        }
    }

    fun isBuiltIn(profileId: String) = AppSettings.DEFAULT_PROFILES.any { it.id == profileId }

    fun addCustomProfile(name: String, baseUrl: String) {
        val profile = LlmProfile("custom-" + UUID.randomUUID().toString().take(8), name.trim(), baseUrl.trim())
        repo.update { it.copy(llmProfiles = it.llmProfiles + profile) }
    }

    /** Removes a custom endpoint, its key, and re-points any agent that used it back to xAI. */
    fun deleteProfile(profileId: String) {
        if (isBuiltIn(profileId)) return
        secrets.set(profileId, null)
        repo.update { s ->
            s.copy(
                llmProfiles = s.llmProfiles.filterNot { it.id == profileId },
                agentModels = s.agentModels.mapValues { (_, m) ->
                    if (m.profileId == profileId) m.copy(profileId = AppSettings.XAI) else m
                },
            )
        }
        _tests.update { it - profileId }
    }

    fun updateProfile(profileId: String, transform: (LlmProfile) -> LlmProfile) {
        repo.update { s -> s.copy(llmProfiles = s.llmProfiles.map { if (it.id == profileId) transform(it) else it }) }
    }

    // ---- agents ------------------------------------------------------------------------------

    private val _picker = MutableStateFlow<ModelPicker?>(null)
    internal val picker: StateFlow<ModelPicker?> = _picker
    private var pickerJob: Job? = null

    /** Fetches the model list of the profile the agent currently uses. */
    fun openModelPicker(agent: AgentId) {
        val s = repo.current
        val profile = s.profile(s.agentModel(agent).profileId)
        pickerJob?.cancel()
        if (profile == null) {
            _picker.value = ModelPicker(agent, loading = false, error = "This agent's provider no longer exists. Pick another one.")
            return
        }
        _picker.value = ModelPicker(agent)
        pickerJob = viewModelScope.launch {
            _picker.value = try {
                ModelPicker(agent, loading = false, models = providers.llm(profile).listModels().sorted())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ModelPicker(agent, loading = false, error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun closeModelPicker() {
        pickerJob?.cancel()
        _picker.value = null
    }

    // ---- prompts -----------------------------------------------------------------------------

    private val _promptRows = MutableStateFlow<List<PromptRow>>(emptyList())
    internal val promptRows: StateFlow<List<PromptRow>> = _promptRows

    /** Re-reads versions and customised flags; called when returning from the editor. */
    fun refreshPrompts() {
        viewModelScope.launch {
            _promptRows.value = AgentId.entries.map { agent ->
                val version = runCatching { PromptFile.parse(prompts.text(agent)).version }.getOrDefault("unreadable")
                PromptRow(agent, version, prompts.isCustomized(agent))
            }
        }
    }

    // ---- costs -------------------------------------------------------------------------------

    val monthToDate: StateFlow<Double> = db.usage()
        .observeCostSince(RoomUsageLedger.monthStart(System.currentTimeMillis()))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0.0)

    // ---- on-device transcription -------------------------------------------------------------

    val whisperModels get() = whisper.models

    private val _downloaded = MutableStateFlow(downloadedNames())
    internal val downloaded: StateFlow<Set<String>> = _downloaded

    private val _downloads = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    internal val downloads: StateFlow<Map<String, DownloadState>> = _downloads
    private val downloadJobs = mutableMapOf<String, Job>()

    private fun downloadedNames() = whisper.models.filter { whisper.isDownloaded(it.name) }.map { it.name }.toSet()

    /** Starts (or ignores a repeat of) a download. It runs in the ViewModel scope, so rotation does not stop it. */
    fun downloadModel(name: String) {
        if (downloadJobs[name]?.isActive == true) return
        _downloads.update { it + (name to DownloadState()) }
        downloadJobs[name] = viewModelScope.launch(Dispatchers.IO) {
            try {
                whisper.download(name) { p -> _downloads.update { it + (name to DownloadState(p.coerceIn(0f, 1f))) } }
                _downloads.update { it - name }
            } catch (e: CancellationException) {
                _downloads.update { it - name }
                throw e
            } catch (e: Exception) {
                _downloads.update { it + (name to DownloadState(0f, e.message ?: "Download failed")) }
            } finally {
                _downloaded.value = downloadedNames()
            }
        }
    }

    fun cancelDownload(name: String) {
        downloadJobs[name]?.cancel()
    }

    fun deleteModel(name: String) {
        whisper.delete(name)
        _downloaded.value = downloadedNames()
    }

    fun isWhisperAvailable(): Boolean = com.ideaforge.whisper.WhisperNative.isAvailable()

    // ---- data --------------------------------------------------------------------------------

    private val _op = MutableStateFlow<DataOp>(DataOp.Idle)
    internal val op: StateFlow<DataOp> = _op

    fun dismissOp() {
        if (_op.value !is DataOp.Working) _op.value = DataOp.Idle
    }

    fun backupTo(uri: Uri) {
        if (_op.value is DataOp.Working) return
        _op.value = DataOp.Working("Writing backup...")
        viewModelScope.launch(Dispatchers.IO) {
            _op.value = try {
                val out = context.contentResolver.openOutputStream(uri, "wt") ?: error("Could not open the chosen file")
                out.use { backups.backup(it) }
                DataOp.Done("Backup saved. API keys are not included; enter them again after restoring.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DataOp.Failed("Backup failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /** On success the app restarts, so this only ever reports failures. */
    fun restoreFrom(uri: Uri) {
        if (_op.value is DataOp.Working) return
        _op.value = DataOp.Working("Restoring... the app will restart when done.")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val input = context.contentResolver.openInputStream(uri) ?: error("Could not open the chosen file")
                input.use { backups.restore(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _op.value = DataOp.Failed("Restore failed: ${e.message ?: e.javaClass.simpleName}. Nothing was changed.")
            }
        }
    }

    fun importAudio(uri: Uri) {
        if (_op.value is DataOp.Working) return
        _op.value = DataOp.Working("Importing audio...")
        viewModelScope.launch {
            _op.value = try {
                importer.import(uri)
                DataOp.Done("Audio imported as a new session. Open it from the home screen.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DataOp.Failed("Import failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }
}
