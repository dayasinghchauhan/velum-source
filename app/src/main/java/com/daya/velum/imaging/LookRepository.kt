package com.daya.velum.imaging

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Built-in + user looks. User looks (saved parametric looks and imported .cube files) live in filesDir/looks;
 * favourites and recents are kept alongside. Imported cubes are copied in after validation so the original
 * document can disappear without breaking the look.
 */
class LookRepository(private val context: Context) {
    private val dir = File(context.filesDir, "looks").apply { mkdirs() }
    private val index = File(dir, "looks.json")
    private val _custom = MutableStateFlow<List<Look>>(emptyList())
    val custom: StateFlow<List<Look>> = _custom
    private val _favorites = MutableStateFlow<Set<String>>(emptySet())
    val favorites: StateFlow<Set<String>> = _favorites
    private val _recents = MutableStateFlow<List<String>>(emptyList())
    val recents: StateFlow<List<String>> = _recents
    private val cubeCache = HashMap<String, Lut3D>()

    fun all(): List<Look> = LookCatalog.all + _custom.value
    fun byId(id: String?): Look? = all().firstOrNull { it.id == id }

    suspend fun load() = withContext(Dispatchers.IO) {
        if (!index.exists()) return@withContext
        runCatching {
            val j = JSONObject(index.readText())
            _custom.value = j.optJSONArray("custom")?.let { a -> (0 until a.length()).map { lookFrom(a.getJSONObject(it)) } }.orEmpty()
            _favorites.value = j.optJSONArray("fav")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty()
            _recents.value = j.optJSONArray("recent")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        }
    }

    private suspend fun persist() = withContext(Dispatchers.IO) {
        val j = JSONObject()
            .put("custom", JSONArray(_custom.value.map(::lookTo)))
            .put("fav", JSONArray(_favorites.value.toList()))
            .put("recent", JSONArray(_recents.value))
        val tmp = File(dir, "looks.json.tmp")
        tmp.writeText(j.toString())
        tmp.renameTo(index)
    }

    suspend fun toggleFavorite(id: String) { _favorites.value = _favorites.value.let { if (id in it) it - id else it + id }; persist() }
    suspend fun markUsed(id: String) { if (id == "neutral") return; _recents.value = (listOf(id) + _recents.value.filter { it != id }).take(8); persist() }

    suspend fun saveCustom(name: String, params: LookParams, basedOn: Look?): Look {
        val look = Look("u_" + UUID.randomUUID().toString().take(8), name.ifBlank { "Custom" }, LookCategory.CUSTOM,
            "Custom look" + (basedOn?.let { " based on ${it.name}" } ?: ""), params, basedOn?.cubeFile)
        _custom.value = _custom.value + look; persist(); return look
    }

    suspend fun updateCustom(look: Look) { _custom.value = _custom.value.map { if (it.id == look.id) look else it }; persist() }

    suspend fun deleteCustom(id: String) {
        val l = _custom.value.firstOrNull { it.id == id } ?: return
        _custom.value = _custom.value.filter { it.id != id }
        // Only delete the cube file if no other look references it.
        l.cubeFile?.let { f -> if (_custom.value.none { it.cubeFile == f }) File(dir, f).delete(); cubeCache.remove(f) }
        _favorites.value = _favorites.value - id; _recents.value = _recents.value - id
        persist()
    }

    /** Validates and imports a .cube file. Throws [CubeParseException] with a user-readable reason on bad input. */
    suspend fun importCube(uri: Uri): Look = withContext(Dispatchers.IO) {
        val cr = context.contentResolver
        var name = "Imported LUT"; var size = -1L
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) { name = c.getString(0)?.substringBeforeLast('.') ?: name; size = c.getLong(1) }
        }
        val lut = cr.openInputStream(uri)?.bufferedReader()?.use { CubeParser.parse(it, size) }
            ?: throw CubeParseException("Cannot open file")
        val fileName = "cube_" + UUID.randomUUID().toString().take(8) + ".cube"
        File(dir, fileName).writeText(serialize(lut))
        cubeCache[fileName] = lut
        val look = Look("u_" + fileName.removeSuffix(".cube"), lut.title?.takeIf { it.isNotBlank() } ?: name, LookCategory.CUSTOM,
            "Imported ${lut.size}³ LUT", LookParams(), fileName)
        _custom.value = _custom.value + look
        persist()
        look
    }

    /** Builds the LUT for [look] (cube first, then parametric adjustments). */
    suspend fun lutFor(look: Look): Lut3D = withContext(Dispatchers.Default) {
        val base = look.cubeFile?.let { f ->
            cubeCache[f] ?: runCatching {
                // Bundled LUTs live in the APK assets; imported ones in the app's files.
                if (f.startsWith("asset:")) context.assets.open(f.removePrefix("asset:")).bufferedReader().use { CubeParser.parse(it) }
                else File(dir, f).bufferedReader().use { CubeParser.parse(it) }
            }.getOrNull()?.also { cubeCache[f] = it }
        }
        if (base == null && look.params.isIdentity) Lut3D.identity() else Lut3D.fromLook(look.params, base)
    }

    private fun serialize(l: Lut3D) = buildString {
        l.title?.let { appendLine("TITLE \"$it\"") }
        appendLine("LUT_3D_SIZE ${l.size}")
        for (i in 0 until l.size * l.size * l.size) appendLine("%.6f %.6f %.6f".format(l.data[i * 3], l.data[i * 3 + 1], l.data[i * 3 + 2]))
    }

    private fun lookTo(l: Look) = JSONObject().put("id", l.id).put("name", l.name).put("desc", l.description)
        .put("cube", l.cubeFile ?: JSONObject.NULL).put("p", paramsTo(l.params))

    private fun lookFrom(o: JSONObject) = Look(o.getString("id"), o.optString("name", "Custom"), LookCategory.CUSTOM,
        o.optString("desc", ""), paramsFrom(o.optJSONObject("p") ?: JSONObject()), o.optString("cube").takeIf { it.isNotEmpty() && it != "null" })

    companion object {
        fun paramsTo(p: LookParams) = JSONObject().apply {
            put("ex", p.exposure.toDouble()); put("co", p.contrast.toDouble()); put("sa", p.saturation.toDouble()); put("vi", p.vibrance.toDouble())
            put("wa", p.warmth.toDouble()); put("ti", p.tint.toDouble()); put("fa", p.fade.toDouble()); put("hr", p.highlightRoll.toDouble())
            put("sh", p.shadowHue.toDouble()); put("sA", p.shadowAmt.toDouble()); put("hh", p.highlightHue.toDouble()); put("hA", p.highlightAmt.toDouble())
            put("mo", p.mono.toDouble()); put("mr", p.monoRed.toDouble()); put("mg", p.monoGreen.toDouble()); put("mb", p.monoBlue.toDouble())
            put("gr", p.grain.toDouble()); put("vg", p.vignette.toDouble())
            put("sh2", p.sharpness.toDouble()); put("sk", p.skinProtect.toDouble())
            put("skh", p.skinHue.toDouble()); put("sks", p.skinSat.toDouble()); put("skb", p.skinBright.toDouble())
            put("sd", p.shadows.toDouble()); put("hl", p.highlights.toDouble())
            if (!p.bands.isIdentity) put("hsl", JSONObject().apply {
                put("h", org.json.JSONArray(p.bands.hue.map { it.toDouble() }))
                put("s", org.json.JSONArray(p.bands.sat.map { it.toDouble() }))
                put("l", org.json.JSONArray(p.bands.lum.map { it.toDouble() }))
            })
        }

        private fun bandsFrom(o: JSONObject?): HueBands {
            if (o == null) return HueBands()
            fun arr(k: String) = o.optJSONArray(k)?.let { a ->
                if (a.length() != 8) null else List(8) { i -> a.optDouble(i, 0.0).toFloat().takeIf { it.isFinite() } ?: 0f }
            } ?: HueBands.ZERO
            return HueBands(arr("h"), arr("s"), arr("l"))
        }

        fun paramsFrom(o: JSONObject): LookParams {
            fun f(k: String, d: Float) = o.optDouble(k, d.toDouble()).toFloat().takeIf { it.isFinite() } ?: d
            val d = LookParams()
            return LookParams(f("ex", d.exposure), f("co", d.contrast), f("sa", d.saturation), f("vi", d.vibrance), f("wa", d.warmth),
                f("ti", d.tint), f("fa", d.fade), f("hr", d.highlightRoll), f("sh", d.shadowHue), f("sA", d.shadowAmt), f("hh", d.highlightHue),
                f("hA", d.highlightAmt), f("mo", d.mono), f("mr", d.monoRed), f("mg", d.monoGreen), f("mb", d.monoBlue), f("gr", d.grain), f("vg", d.vignette),
                f("sh2", d.sharpness), f("sk", d.skinProtect), f("skh", d.skinHue), f("sks", d.skinSat), f("skb", d.skinBright),
                f("sd", d.shadows), f("hl", d.highlights), bandsFrom(o.optJSONObject("hsl")))
        }
    }
}
