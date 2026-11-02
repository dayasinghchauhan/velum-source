package com.daya.velum.ui.camera

import android.graphics.Bitmap
import android.graphics.Color as AColor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.daya.velum.imaging.*
import com.daya.velum.settings.LookTarget
import com.daya.velum.ui.CameraViewModel
import com.daya.velum.ui.components.G
import com.daya.velum.ui.components.Glyph
import com.daya.velum.ui.theme.V
import com.daya.velum.ui.theme.VType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Procedural reference chart: hue sweep, neutral ramp and a skin patch. Generated, not a photograph. */
object ReferenceChart {
    val bitmap: Bitmap by lazy {
        val w = 120; val h = 80
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val hsv = FloatArray(3)
        for (y in 0 until h) for (x in 0 until w) {
            val c = when {
                y < 34 -> { hsv[0] = x * 360f / w; hsv[1] = 0.75f - y / 80f; hsv[2] = 0.9f - y / 120f; AColor.HSVToColor(hsv) }
                y < 60 -> if (x in 40..79) AColor.rgb(203, 156, 128) else {
                    val v = (x / (w - 1f) * 255).toInt(); AColor.rgb(v, v, v)
                }
                else -> { hsv[0] = 210f - x * 0.6f; hsv[1] = 0.45f; hsv[2] = 0.15f + (y - 60) / 40f; AColor.HSVToColor(hsv) }
            }
            b.setPixel(x, y, c)
        }
        b
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LookSheet(vm: CameraViewModel, onDismiss: () -> Unit) {
    val s by vm.settings.collectAsState()
    val custom by vm.looks.custom.collectAsState()
    val favs by vm.looks.favorites.collectAsState()
    val recents by vm.looks.recents.collectAsState()
    val all = remember(custom) { vm.looks.all() }
    val current = all.firstOrNull { it.id == s.look.lookId } ?: LookCatalog.NEUTRAL
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(if (current.brand != null) "Signature" else "Essentials") }
    var brand by remember { mutableStateOf(current.brand ?: LookCatalog.BRANDS.first()) }
    var editing by remember { mutableStateOf(false) }
    val thumbs = remember { mutableStateMapOf<String, Bitmap>() }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { vm.looks.importCube(uri) }
                .onSuccess { vm.selectLook(it); vm.notify("Imported ${it.name}") }
                .onFailure { vm.notify("LUT rejected: ${it.message}") }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = V.Graphite, contentColor = V.Bone, scrimColor = V.Scrim) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(current.name, style = VType.title, modifier = Modifier.weight(1f))
                    if (!current.isNeutral) Box(Modifier.clip(RoundedCornerShape(20.dp)).clickable { scope.launch { vm.looks.toggleFavorite(current.id) } }.padding(8.dp)) {
                        Glyph(if (current.id in favs) G.STAR_FILLED else G.STAR, tint = V.Bone)
                    }
                }
                Text(current.description, style = VType.small.copy(color = V.Ash))
                current.credit?.let { Text(it, style = VType.micro.copy(color = V.Dim)) }
                Spacer(Modifier.height(4.dp))
                Text("Press and hold the viewfinder to see the image without the look.", style = VType.micro.copy(color = V.Dim))
            }
            if (!current.isNeutral) {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Intensity", style = VType.small.copy(color = V.Ash), modifier = Modifier.width(76.dp))
                    Slider(s.look.intensity, { vm.setLookIntensity(it) }, Modifier.weight(1f),
                        colors = SliderDefaults.colors(thumbColor = V.Amber, activeTrackColor = V.Amber, inactiveTrackColor = V.Line))
                    Text("${(s.look.intensity * 100).toInt()}%", style = VType.label, modifier = Modifier.width(44.dp))
                }
                Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ToggleChip("Reset strength", false, { vm.setLookIntensity(current.defaultIntensity) })
                    ToggleChip("No look", false, { vm.selectLook(LookCatalog.NEUTRAL) })
                }
            }
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                if (s.mode.isVideo) {
                    Segmented(listOf("Monitor only", "Baked into video"), if (s.videoLook.target == LookTarget.BAKED) 1 else 0,
                        { i -> vm.setLookTarget(if (i == 1) LookTarget.BAKED else LookTarget.MONITOR) })
                } else {
                    Segmented(listOf("Viewfinder only", "Save graded copy"), if (s.photoLook.target == LookTarget.BAKED) 1 else 0,
                        { i -> vm.setLookTarget(if (i == 1) LookTarget.BAKED else LookTarget.MONITOR) })
                }
            }
            Text(
                when {
                    s.mode.isVideo && s.videoLook.target == LookTarget.BAKED -> "The look is rendered into the recorded file on the GPU. Not available with HLG."
                    s.mode.isVideo -> "The look is shown in the viewfinder only; the recording stays ungraded."
                    s.photoLook.target == LookTarget.BAKED -> "Photos are saved as shot, then a graded JPEG copy is written. RAW and Ultra HDR originals stay ungraded."
                    else -> "The look is shown in the viewfinder only; photos are saved as shot."
                }, style = VType.micro.copy(color = V.Ash), modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
            if (!s.mode.isVideo && s.photoLook.target == LookTarget.BAKED) {
                Row(Modifier.padding(horizontal = 20.dp)) {
                    ToggleChip("Keep ungraded original", s.photoLook.keepOriginal, { vm.update { it.copy(photoLook = it.photoLook.copy(keepOriginal = !it.photoLook.keepOriginal)) } })
                }
            }

            val tabs = listOf("Essentials", "Signature", "All", "Styles", "Favourites", "Recent", "Natural", "Mood", "Film", "Mono", "Cinema", "Custom")
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (t in tabs) ToggleChip(t, t == tab, { tab = t })
            }
            if (tab == "Signature") {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (b in LookCatalog.BRANDS) ToggleChip(b, b == brand, { brand = b })
                }
                Text((LookCatalog.BRAND_NOTES[brand]?.let { "$it " } ?: "") + "Velum's own grades, rendered on this camera's image. " +
                    "Not affiliated with or endorsed by these companies; their in-camera processing is not available to other apps.",
                    style = VType.micro.copy(color = V.Dim), modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            }
            val shown = when (tab) {
                "Favourites" -> all.filter { it.id in favs }
                "Recent" -> recents.mapNotNull { id -> all.firstOrNull { it.id == id } }
                "Signature" -> all.filter { it.brand == brand }
                "Essentials" -> LookCatalog.ESSENTIALS.mapNotNull { id -> all.firstOrNull { it.id == id } }
                "Styles" -> all.filter { it.category == LookCategory.STYLE }
                "Natural" -> all.filter { it.category == LookCategory.NATURAL }
                "Mood" -> all.filter { it.category == LookCategory.MOOD }
                "Film" -> all.filter { it.category == LookCategory.FILM }
                "Mono" -> all.filter { it.category == LookCategory.MONO }
                "Cinema" -> all.filter { it.category == LookCategory.CINEMA }
                "Custom" -> all.filter { it.category == LookCategory.CUSTOM }
                else -> all
            }
            if (shown.isEmpty()) Text(
                when (tab) { "Favourites" -> "Star a look to keep it here."; "Recent" -> "Looks you use appear here."; else -> "Import a .cube LUT or save an adjusted look." },
                style = VType.small.copy(color = V.Ash), modifier = Modifier.padding(20.dp),
            )
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(shown, key = { it.id }) { look ->
                    LaunchedEffect(look.id) {
                        if (thumbs[look.id] == null) {
                            val lut = vm.looks.lutFor(look)
                            thumbs[look.id] = withContext(Dispatchers.Default) { CpuLookApplier.apply(ReferenceChart.bitmap, lut, 1f, look.params) }
                        }
                    }
                    val sel = look.id == current.id
                    Column(
                        Modifier.width(96.dp).clip(RoundedCornerShape(10.dp)).clickable(role = Role.RadioButton) { vm.selectLook(look) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(Modifier.size(96.dp, 64.dp).clip(RoundedCornerShape(10.dp)).border(if (sel) 2.dp else 1.dp, if (sel) V.Amber else V.Line, RoundedCornerShape(10.dp))) {
                            thumbs[look.id]?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                        }
                        Text(look.name, style = VType.micro.copy(color = if (sel) V.Amber else V.Bone), maxLines = 2, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToggleChip("Import .cube", false, { importer.launch(arrayOf("*/*")) })
                ToggleChip("Adjust and save", editing, { editing = !editing })
                if (current.category == LookCategory.CUSTOM) ToggleChip("Delete look", false, {
                    scope.launch { vm.looks.deleteCustom(current.id); vm.selectLook(LookCatalog.NEUTRAL) }
                })
            }
            if (editing) LookEditor(vm, current) { editing = false }
        }
    }
}

@Composable
private fun LookEditor(vm: CameraViewModel, base: Look, onDone: () -> Unit) {
    var p by remember(base.id) { mutableStateOf(base.params) }
    var name by remember(base.id) { mutableStateOf(if (base.isNeutral) "My look" else "${base.name} edit") }
    val scope = rememberCoroutineScope()
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var band by remember { mutableStateOf(Band.RED) }
    LaunchedEffect(p) {
        val lut = vm.looks.lutFor(base.copy(params = p))
        preview = withContext(Dispatchers.Default) { CpuLookApplier.apply(ReferenceChart.bitmap, lut, 1f, p) }
    }
    Column(Modifier.padding(horizontal = 20.dp)) {
        preview?.let { Image(it.asImageBitmap(), "Preview of adjusted look", Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop) }
        @Composable
        fun slider(label: String, v: Float, range: ClosedFloatingPointRange<Float>, set: (Float) -> LookParams) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = VType.small.copy(color = V.Ash), modifier = Modifier.width(92.dp))
                Slider(v, { p = set(it) }, Modifier.weight(1f), valueRange = range,
                    colors = SliderDefaults.colors(thumbColor = V.Amber, activeTrackColor = V.Amber, inactiveTrackColor = V.Line))
                Text("%.2f".format(v), style = VType.micro, modifier = Modifier.width(40.dp))
            }
        }
        slider("Exposure", p.exposure, -1f..1f) { p.copy(exposure = it) }
        slider("Contrast", p.contrast, -1f..1f) { p.copy(contrast = it) }
        slider("Saturation", p.saturation, -1f..1f) { p.copy(saturation = it) }
        slider("Vibrance", p.vibrance, -1f..1f) { p.copy(vibrance = it) }
        slider("Warmth", p.warmth, -1f..1f) { p.copy(warmth = it) }
        slider("Tint", p.tint, -1f..1f) { p.copy(tint = it) }
        slider("Fade", p.fade, 0f..1f) { p.copy(fade = it) }
        slider("Shadows", p.shadows, -1f..1f) { p.copy(shadows = it) }
        slider("Highlights", p.highlights, -1f..1f) { p.copy(highlights = it) }
        slider("Highlight roll-off", p.highlightRoll, 0f..1f) { p.copy(highlightRoll = it) }
        slider("Shadow tint", p.shadowAmt, 0f..1f) { p.copy(shadowAmt = it) }
        slider("Shadow hue", p.shadowHue, 0f..360f) { p.copy(shadowHue = it) }
        slider("Highlight tint", p.highlightAmt, 0f..1f) { p.copy(highlightAmt = it) }
        slider("Highlight hue", p.highlightHue, 0f..360f) { p.copy(highlightHue = it) }
        slider("Monochrome", p.mono, 0f..1f) { p.copy(mono = it) }
        slider("Grain", p.grain, 0f..1f) { p.copy(grain = it) }
        slider("Vignette", p.vignette, 0f..1f) { p.copy(vignette = it) }
        slider("Sharpness", p.sharpness, -1f..1f) { p.copy(sharpness = it) }
        slider("Skin protect", p.skinProtect, 0f..1f) { p.copy(skinProtect = it) }
        slider("Skin undertone", p.skinHue, -20f..20f) { p.copy(skinHue = it) }
        slider("Skin richness", p.skinSat, -1f..1f) { p.copy(skinSat = it) }
        slider("Skin brightness", p.skinBright, -1f..1f) { p.copy(skinBright = it) }
        Text("Colour mixer", style = VType.small, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (b in Band.entries) ToggleChip(b.label, b == band, { band = b })
        }
        val i = band.ordinal
        slider("${band.label} hue", p.bands.hue[i], -30f..30f) { p.copy(bands = p.bands.with(band, h = it)) }
        slider("${band.label} saturation", p.bands.sat[i], -1f..1f) { p.copy(bands = p.bands.with(band, s = it)) }
        slider("${band.label} brightness", p.bands.lum[i], -1f..1f) { p.copy(bands = p.bands.with(band, l = it)) }
        OutlinedTextField(name, { name = it.take(32) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ToggleChip("Save look", true, {
                scope.launch {
                    val l = vm.saveCustomLook(name, p, base.takeIf { !it.isNeutral })
                    vm.selectLook(l); vm.notify("Saved look ${l.name}"); onDone()
                }
            })
            ToggleChip("Reset", false, { p = base.params })
        }
    }
}

