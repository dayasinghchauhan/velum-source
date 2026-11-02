package com.daya.velum.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.daya.velum.capability.CapabilityReport
import com.daya.velum.capability.ProbeResult
import com.daya.velum.settings.CameraSettings
import com.daya.velum.ui.CameraViewModel
import com.daya.velum.ui.camera.IconBtn
import com.daya.velum.ui.camera.Segmented
import com.daya.velum.ui.components.G
import com.daya.velum.ui.components.Glyph
import com.daya.velum.ui.theme.V
import com.daya.velum.ui.theme.VType

@Composable
fun SettingsScreen(vm: CameraViewModel, onBack: () -> Unit, onDiagnostics: () -> Unit) {
    val s by vm.settings.collectAsState()
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(V.Black).windowInsetsPadding(WindowInsets.systemBars).verticalScroll(rememberScrollState())) {
        Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(G.BACK, "Back to camera", 0f, onBack); Text("Settings", style = VType.title)
        }
        Section("Image quality")
        SwitchRow("Face priority", "Focus, exposure and colour follow detected faces in auto modes.", s.faceDetect) { vm.update { it.copy(faceDetect = !it.faceDetect) } }
        SwitchRow("Auto HDR", "Asks the camera for its HDR scene processing in auto exposure. Tested indoors on this phone it made no measurable difference to photos; it may help with bright windows and skies.", s.autoHdr) {
            vm.update { it.copy(autoHdr = !it.autoHdr) }
        }
        Row(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Detail", style = VType.label)
                Text("Natural asks the camera for lighter edge sharpening. Measured on this phone, photos keep the camera's own sharpening either way; looks use Velum's halo-free sharpening.", style = VType.small.copy(color = V.Ash))
            }
            val opts = com.daya.velum.settings.Detail.entries
            Segmented(opts.map { it.label }, opts.indexOf(s.detail), { i -> vm.update { it.copy(detail = opts[i]) } })
        }
        Section("Shooting")
        SwitchRow("Show pro readouts in every mode", "ISO, shutter, focus and active sensor under the viewfinder.", s.proControls) { vm.update { it.copy(proControls = !it.proControls) } }
        SwitchRow("Shutter sound", "Android may play the sound regardless where the law requires it.", s.shutterSound) { vm.update { it.copy(shutterSound = !it.shutterSound) } }
        SwitchRow("Haptics", "Ticks when dials move and on capture.", s.haptics) { vm.update { it.copy(haptics = !it.haptics) } }
        SwitchRow("Lock to physical lens", "Pins the stream to the chosen sensor instead of letting the camera switch lenses as you zoom. Experimental; rebinds the camera.", s.lockPhysicalLens) {
            vm.update { it.copy(lockPhysicalLens = !it.lockPhysicalLens) }
        }
        Row(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("JPEG quality", style = VType.label)
                Text("Applies to camera JPEGs and graded copies.", style = VType.small.copy(color = V.Ash))
            }
            val q = listOf(85, 92, 95, 100)
            Segmented(q.map { "$it" }, q.indexOf(s.jpegQuality).coerceAtLeast(0), { i -> vm.update { it.copy(jpegQuality = q[i]) } })
        }
        Section("Storage")
        Text("Photos and videos are saved to DCIM/Velum on this device. Nothing is uploaded; the app has no internet permission.",
            style = VType.small.copy(color = V.Ash), modifier = Modifier.padding(horizontal = 20.dp))
        Text("Free space: %.1f GB".format(vm.media.freeBytes() / 1e9), style = VType.small, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
        Section("About this camera")
        Row(Modifier.fillMaxWidth().clickable(onClick = onDiagnostics).padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Camera diagnostics", style = VType.label)
                Text("What this phone's cameras report, what was tested, and why options are disabled.", style = VType.small.copy(color = V.Ash))
            }
            Glyph(G.CHEVRON, tint = V.Ash)
        }
        Row(Modifier.fillMaxWidth().clickable { vm.update { CameraSettings(lensKey = it.lensKey) }; vm.notify("Settings reset") }.padding(horizontal = 20.dp, vertical = 14.dp)) {
            Text("Reset camera settings", style = VType.label.copy(color = V.Tally))
        }
        Text("Velum ${com.daya.velum.BuildConfig.VERSION_NAME}. Typeface: IBM Plex Sans Condensed (SIL Open Font License).",
            style = VType.micro.copy(color = V.Dim), modifier = Modifier.padding(20.dp))
    }
}

@Composable
private fun Section(t: String) = Text(t, style = VType.small.copy(color = V.Amber), modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 4.dp))

@Composable
private fun SwitchRow(title: String, sub: String, on: Boolean, toggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = toggle).padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, style = VType.label); Text(sub, style = VType.small.copy(color = V.Ash)) }
        Switch(on, { toggle() }, colors = SwitchDefaults.colors(checkedThumbColor = V.Black, checkedTrackColor = V.Amber, uncheckedTrackColor = V.GraphiteHi))
    }
}

@Composable
fun DiagnosticsScreen(vm: CameraViewModel, onBack: () -> Unit) {
    val caps by vm.caps.collectAsState()
    val meta by vm.engine.meta.collectAsState()
    val status by vm.engine.status.collectAsState()
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(V.Black).windowInsetsPadding(WindowInsets.systemBars).verticalScroll(rememberScrollState())) {
        Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(G.BACK, "Back", 0f, onBack); Text("Camera diagnostics", style = VType.title)
        }
        val c = caps
        if (c == null) { Text("Capabilities not read yet. Open the camera first.", style = VType.body.copy(color = V.Ash), modifier = Modifier.padding(20.dp)); return@Column }
        Mono(CapabilityReport.summary(c))
        Section("Live")
        Mono("Session: $status\nActive physical sensor: ${meta.activePhysicalId ?: "not reported"}\nZoom ratio: ${meta.zoomRatio}\n" +
            "OIS: ${meta.ois}  Video stab mode: ${meta.videoStab}\nAE/AF/AWB state: ${meta.aeState}/${meta.afState}/${meta.awbState}")
        Section("Lenses offered")
        for (l in c.lenses) Mono("${l.label}  ${l.role.label}  camera ${l.logicalId}${l.physicalId?.let { " / sensor $it" } ?: ""}  zoom ${"%.2f".format(l.zoomRatio)}\n  evidence: ${l.evidence}")
        Section("Stream combinations tested with CameraX")
        for (x in c.cameraX) {
            Text("Camera ${x.cameraId}", style = VType.label, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            for (p in x.sessionProbes) Row(Modifier.padding(horizontal = 20.dp, vertical = 1.dp)) {
                Text(if (p.result == ProbeResult.SUPPORTED) "Yes" else if (p.result == ProbeResult.UNSUPPORTED) "No" else p.result.name.lowercase(),
                    style = VType.small.copy(color = if (p.result == ProbeResult.SUPPORTED) V.Ok else V.Tally), modifier = Modifier.width(48.dp))
                Text(p.name + if (p.detail.isNotEmpty()) " (${p.detail})" else "", style = VType.small)
            }
        }
        Section("Camera2 devices")
        for (i in c.camera2) Mono(buildString {
            append("${i.id}${if (i.isPhysicalOnly) " (sensor of ${i.parentLogicalId})" else ""}  ${i.facing}  ${i.hardwareLevel}\n")
            append("  focal ${i.lens.focalLengthsMm} mm (${i.lens.equivFocalMm?.let { "%.0f".format(it) }} mm-eq)  f/${i.lens.apertures.firstOrNull()}  OIS ${i.lens.ois}\n")
            append("  ISO ${i.sensor.isoRange}  exposure ${i.sensor.exposureNsRange?.let { "${it.lower / 1000}µs–${it.upper / 1_000_000}ms" }}\n")
            append("  array ${i.sensor.pixelArray}  max-res ${i.sensor.pixelArrayMaxRes}  RAW ${i.streams.raw.firstOrNull()}\n")
            append("  zoom ${i.zoomRatioRange}  fps ${i.aeFpsRanges.joinToString { "${it.lower}-${it.upper}" }}\n")
            append("  high-speed ${i.streams.highSpeed.joinToString { "${it.size}@${it.fps.maxOfOrNull { r -> r.upper }}" }}\n")
            append("  dynamic range ${i.dynamicRangeProfiles}  colour spaces ${i.colorSpaces}\n")
            append("  extensions ${i.extensions}  stab ${i.videoStabModes}\n  caps ${i.capabilities.joinToString()}")
        })
        Section("Encoders")
        for (e in c.encoders.filter { it.hardware }) Mono("${e.name}  ${e.mime}  max ${e.maxSize}  ${e.profiles.joinToString()}  4K fps ${e.fps4k?.let { "%.0f".format(it.upper) }}")
        Text("Full JSON report: app files/capabilities.json", style = VType.micro.copy(color = V.Dim), modifier = Modifier.padding(20.dp))
    }
}

@Composable
private fun Mono(t: String) = Text(t, style = VType.small, modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 4.dp))
