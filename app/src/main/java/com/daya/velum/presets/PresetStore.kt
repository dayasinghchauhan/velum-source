package com.daya.velum.presets

import android.content.Context
import com.daya.velum.settings.CameraSettings
import com.daya.velum.settings.DataStoreSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** A user preset: a named snapshot of creative + capture settings (lens/zoom excluded). */
data class UserPreset(val id: String, val name: String, val favorite: Boolean, val settings: CameraSettings, val createdMs: Long)

/** CRUD for user presets plus hide/restore of built-in recipes. Stored as JSON in filesDir. */
class PresetStore(context: Context) {
    private val file = File(context.filesDir, "presets.json")
    private val _presets = MutableStateFlow<List<UserPreset>>(emptyList())
    val presets: StateFlow<List<UserPreset>> = _presets
    private val _hidden = MutableStateFlow<Set<String>>(emptySet())
    val hiddenBuiltIns: StateFlow<Set<String>> = _hidden

    suspend fun load() = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext
        runCatching {
            val j = JSONObject(file.readText())
            _presets.value = j.optJSONArray("presets")?.let { a ->
                (0 until a.length()).map { i ->
                    val o = a.getJSONObject(i)
                    UserPreset(o.getString("id"), o.getString("name"), o.optBoolean("fav"),
                        DataStoreSettingsRepository.fromJson(o.getJSONObject("s")), o.optLong("t"))
                }
            }.orEmpty()
            _hidden.value = j.optJSONArray("hidden")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty()
        }
    }

    private suspend fun persist() = withContext(Dispatchers.IO) {
        val j = JSONObject()
            .put("presets", JSONArray(_presets.value.map {
                JSONObject().put("id", it.id).put("name", it.name).put("fav", it.favorite).put("t", it.createdMs)
                    .put("s", DataStoreSettingsRepository.toJson(it.settings))
            }))
            .put("hidden", JSONArray(_hidden.value.toList()))
        val tmp = File(file.parentFile, "presets.json.tmp")
        tmp.writeText(j.toString()); tmp.renameTo(file)
    }

    suspend fun create(name: String, s: CameraSettings): UserPreset {
        val p = UserPreset("p_" + UUID.randomUUID().toString().take(8), name.ifBlank { "Preset" }, false, s, System.currentTimeMillis())
        _presets.value = _presets.value + p; persist(); return p
    }
    suspend fun rename(id: String, name: String) = edit(id) { it.copy(name = name.ifBlank { it.name }) }
    suspend fun overwrite(id: String, s: CameraSettings) = edit(id) { it.copy(settings = s) }
    suspend fun toggleFavorite(id: String) = edit(id) { it.copy(favorite = !it.favorite) }
    suspend fun duplicate(id: String) {
        val p = _presets.value.firstOrNull { it.id == id } ?: return
        _presets.value = _presets.value + p.copy(id = "p_" + UUID.randomUUID().toString().take(8), name = p.name + " copy", createdMs = System.currentTimeMillis())
        persist()
    }
    suspend fun delete(id: String) { _presets.value = _presets.value.filter { it.id != id }; persist() }
    suspend fun hideBuiltIn(id: String) { _hidden.value = _hidden.value + id; persist() }
    suspend fun restoreBuiltIns() { _hidden.value = emptySet(); persist() }

    private suspend fun edit(id: String, f: (UserPreset) -> UserPreset) {
        _presets.value = _presets.value.map { if (it.id == id) f(it) else it }; persist()
    }

    companion object {
        /** Applies a preset over [current] keeping lens, zoom and transient state. */
        fun applyPreset(p: UserPreset, current: CameraSettings): CameraSettings =
            p.settings.copy(lensKey = current.lensKey, zoom = current.zoom, timerSec = 0)
    }
}
