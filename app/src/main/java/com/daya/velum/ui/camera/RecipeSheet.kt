package com.daya.velum.ui.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.daya.velum.presets.RecipeEngine
import com.daya.velum.presets.RecipeKind
import com.daya.velum.presets.UserPreset
import com.daya.velum.settings.*
import com.daya.velum.ui.CameraViewModel
import com.daya.velum.ui.components.G
import com.daya.velum.ui.components.Glyph
import com.daya.velum.ui.theme.V
import com.daya.velum.ui.theme.VType
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeSheet(vm: CameraViewModel, onDismiss: () -> Unit) {
    val s by vm.settings.collectAsState()
    val presets by vm.presets.presets.collectAsState()
    val hidden by vm.presets.hiddenBuiltIns.collectAsState()
    var tab by remember { mutableIntStateOf(if (s.mode.isVideo) 1 else 0) }
    var naming by remember { mutableStateOf<UserPreset?>(null) }
    var newName by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = V.Graphite, contentColor = V.Bone, scrimColor = V.Scrim) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Recipes", style = VType.title, modifier = Modifier.weight(1f))
                Segmented(listOf("Photo", "Video", "Mine"), tab, { tab = it })
            }
            Text(
                when (tab) {
                    0, 1 -> "A recipe sets a look plus the capture settings that suit a situation. Anything this camera can't do stays automatic, and you're told which."
                    else -> "Your presets store mode, look, format and exposure. Lens and zoom are left as they are."
                }, style = VType.small.copy(color = V.Ash), modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            if (tab < 2) {
                val list = (if (tab == 0) RecipeEngine.photo else RecipeEngine.video).filter { it.id !in hidden }
                for (r in list) {
                    Row(
                        Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Apply ${r.name}") { vm.applyRecipe(r); onDismiss() }
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(Color(r.swatch)))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(r.name, style = VType.label)
                            Text(r.description, style = VType.small.copy(color = V.Ash))
                            Text(r.tip, style = VType.micro.copy(color = V.Dim))
                        }
                        Box(Modifier.clip(CircleShape).clickable(onClickLabel = "Hide ${r.name}") { scope.launch { vm.presets.hideBuiltIn(r.id) } }.padding(8.dp)) {
                            Glyph(G.CLOSE, tint = V.Dim, size = 16.dp)
                        }
                    }
                }
                if (hidden.isNotEmpty()) Row(Modifier.padding(16.dp)) {
                    ToggleChip("Restore ${hidden.size} hidden recipes", false, { scope.launch { vm.presets.restoreBuiltIns() } })
                }
            } else {
                val qrPicker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
                    if (uri != null) vm.importPresetQr(uri)
                }
                Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ToggleChip("Save current settings", false, { newName = "Preset ${presets.size + 1}" })
                    ToggleChip("Import from QR image", false, { qrPicker.launch("image/*") })
                }
                if (presets.isEmpty()) Text("No presets yet.", style = VType.small.copy(color = V.Ash), modifier = Modifier.padding(20.dp))
                for (p in presets.sortedByDescending { it.favorite }) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).clickable(role = Role.Button) { vm.applyPreset(p); onDismiss() }) {
                                Text(p.name, style = VType.label)
                                Text("${p.settings.mode.label}, ${vm.looks.byId(p.settings.look.lookId)?.name ?: "Neutral"}" +
                                    if (p.settings.mode.isVideo) ", ${p.settings.video.quality.label} ${p.settings.video.fps} fps" else ", ${p.settings.photoFormat.label}",
                                    style = VType.small.copy(color = V.Ash))
                            }
                            Box(Modifier.clip(CircleShape).clickable { scope.launch { vm.presets.toggleFavorite(p.id) } }.padding(8.dp)) {
                                Glyph(if (p.favorite) G.STAR_FILLED else G.STAR, size = 18.dp)
                            }
                        }
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ToggleChip("Rename", false, { naming = p })
                            ToggleChip("QR code", false, { vm.exportPresetQr(p) })
                            ToggleChip("Duplicate", false, { scope.launch { vm.presets.duplicate(p.id) } })
                            ToggleChip("Update from current", false, { scope.launch { vm.presets.overwrite(p.id, s); vm.notify("Updated ${p.name}") } })
                            ToggleChip("Delete", false, { scope.launch { vm.presets.delete(p.id) } })
                        }
                    }
                }
            }
        }
    }
    newName?.let { n ->
        NameDialog("Save preset", n, onCancel = { newName = null }) { vm.savePreset(it); newName = null }
    }
    naming?.let { p ->
        NameDialog("Rename preset", p.name, onCancel = { naming = null }) { scope.launch { vm.presets.rename(p.id, it) }; naming = null }
    }
}

@Composable
fun NameDialog(title: String, initial: String, onCancel: () -> Unit, onOk: (String) -> Unit) {
    var t by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onCancel, containerColor = V.Graphite,
        title = { Text(title, style = VType.title) },
        text = { OutlinedTextField(t, { t = it.take(40) }, singleLine = true) },
        confirmButton = { TextButton({ onOk(t.trim()) }, enabled = t.isNotBlank()) { Text("Save", color = V.Amber) } },
        dismissButton = { TextButton(onCancel) { Text("Cancel", color = V.Bone) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormatSheet(vm: CameraViewModel, onDismiss: () -> Unit) {
    val s by vm.settings.collectAsState()
    val a = vm.availability
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = V.Graphite, contentColor = V.Bone, scrimColor = V.Scrim) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (s.mode.isVideo) {
                Text("Video format", style = VType.title)
                val v = s.video
                Labeled("Resolution") {
                    val opts = listOf(VideoQuality.UHD, VideoQuality.FHD, VideoQuality.HD)
                    val list = if (v.dynamicRange == DynamicRangeMode.HLG) a.hlgQualities else a.videoQualities
                    Segmented(opts.map { it.label }, opts.indexOf(v.quality), { i -> vm.update { it.copy(video = it.video.copy(quality = opts[i])) } }, enabled = { opts[it] in list })
                }
                Labeled("Frame rate") {
                    val fpsAll = listOf(24, 25, 30, 60)
                    val ok = a.fpsFor(v.quality, v.dynamicRange)
                    Segmented(fpsAll.map { "$it" }, fpsAll.indexOf(v.fps), { i -> vm.update { it.copy(video = it.video.copy(fps = fpsAll[i])) } }, enabled = { fpsAll[it] in ok })
                    val missing = fpsAll.filter { it !in ok }
                    if (missing.isNotEmpty()) Text("${missing.joinToString(", ")} fps not supported at ${v.quality.label} ${v.dynamicRange.label} on this lens (checked with the camera).",
                        style = VType.micro.copy(color = V.Ash))
                }
                Labeled("Dynamic range") {
                    Segmented(listOf("SDR", "HLG 10-bit"), if (v.dynamicRange == DynamicRangeMode.HLG) 1 else 0,
                        { i -> vm.update { it.copy(video = it.video.copy(dynamicRange = if (i == 1) DynamicRangeMode.HLG else DynamicRangeMode.SDR)) } },
                        enabled = { it == 0 || a.hlgQualities.isNotEmpty() })
                    Text("HLG records 10-bit HEVC. Looks, zebra, peaking, false colour and shutter priority use the SDR GPU path and are off in HLG.",
                        style = VType.micro.copy(color = V.Ash))
                }
                Labeled("Stabilisation") {
                    val opts = Stabilization.entries
                    Segmented(opts.map { it.label }, opts.indexOf(v.stabilization), { i -> vm.update { it.copy(video = it.video.copy(stabilization = opts[i])) } }, enabled = { opts[it] in a.stabilization })
                    Text("Stabilisation needs spare pixels around the frame, so it zooms in. Measured on this phone at 1x: Standard shows " +
                        "about 1/1.26 of the photo's width (Samsung Camera's own video: about 1/1.2). Off keeps the full width; for wider " +
                        "steady video use 0.6x. Enhanced also stabilises the viewfinder.", style = VType.micro.copy(color = V.Ash))
                }
                Labeled("Bitrate") {
                    val opts = listOf(0, 20, 40, 80, 120)
                    Segmented(opts.map { if (it == 0) "Auto" else "$it" }, opts.indexOf(v.bitrateMbps).coerceAtLeast(0), { i -> vm.update { it.copy(video = it.video.copy(bitrateMbps = opts[i])) } })
                    Text("Mbit/s target. Auto uses the encoder profile default. Codec: H.264/HEVC chosen by the encoder profile (HLG is always HEVC).",
                        style = VType.micro.copy(color = V.Ash))
                }
                ToggleChip("Record audio", v.audio, { vm.update { it.copy(video = it.video.copy(audio = !it.video.audio)) } })
            } else {
                Text("Photo format", style = VType.title)
                Labeled("File") {
                    val opts = PhotoFormat.entries
                    Segmented(opts.map { it.label }, opts.indexOf(s.photoFormat), { i -> vm.update { it.copy(photoFormat = opts[i]) } },
                        enabled = { when (opts[it]) { PhotoFormat.RAW_JPEG -> a.raw; PhotoFormat.ULTRA_HDR -> a.ultraHdr; else -> true } })
                    Text(when (s.photoFormat) {
                        PhotoFormat.RAW_JPEG -> "Saves a DNG from the sensor plus the processed JPEG."
                        PhotoFormat.ULTRA_HDR -> "JPEG with an HDR gain map for HDR displays."
                        PhotoFormat.JPEG -> "Processed JPEG."
                    }, style = VType.micro.copy(color = V.Ash))
                }
                Labeled("Frame") {
                    val opts = com.daya.velum.imaging.PhotoFrame.entries
                    Segmented(opts.map { it.label }, opts.indexOf(s.photoFrame), { i -> vm.update { it.copy(photoFrame = opts[i]) } })
                    Text("Saves an extra copy with an information bar: device, focal length, aperture, shutter and ISO from the photo's own data.",
                        style = VType.micro.copy(color = V.Ash))
                }
                Labeled("Aspect") {
                    val opts = PhotoAspect.entries
                    Segmented(opts.map { it.label }, opts.indexOf(s.photoAspect), { i -> vm.update { it.copy(photoAspect = opts[i]) } })
                    Text("4:3 uses the full sensor; other ratios crop it. 65:24 is a panoramic film frame: hold the phone sideways.", style = VType.micro.copy(color = V.Ash))
                }
                if (s.mode.extension != null) Text("${s.mode.label} uses the camera's own multi-frame processing (vendor extension). It saves JPEG; " +
                    "RAW, Ultra HDR, manual exposure and viewfinder looks are off in this mode. Photo looks are still applied to a graded copy.",
                    style = VType.micro.copy(color = V.Ash))
            }
        }
    }
}

@Composable
fun Labeled(label: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = VType.small.copy(color = V.Ash))
        content()
    }
}
