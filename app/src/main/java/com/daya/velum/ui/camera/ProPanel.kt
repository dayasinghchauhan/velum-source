package com.daya.velum.ui.camera

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.daya.velum.camera.ExposureFormat
import com.daya.velum.camera.LiveMeta
import com.daya.velum.settings.*
import com.daya.velum.ui.components.DialHeader
import com.daya.velum.ui.components.ScaleDial
import com.daya.velum.ui.theme.V
import com.daya.velum.ui.theme.VType
import kotlin.math.abs
import kotlin.math.roundToInt

enum class Param { EV, ISO, SHUTTER, FOCUS, WB, ZOOM }

private fun <T : Comparable<T>> nearestIndex(list: List<T>, v: T, dist: (T, T) -> Double) =
    list.indices.minByOrNull { dist(list[it], v) } ?: 0

@Composable
fun ParamDial(
    p: Param,
    s: CameraSettings,
    a: Availability,
    meta: LiveMeta,
    update: ((CameraSettings) -> CameraSettings) -> Unit,
) {
    val e = s.exposure
    val auto: (() -> Unit)? = when (p) {
        Param.ISO, Param.SHUTTER -> if (e.program != ExposureProgram.P) ({ update { it.copy(exposure = it.exposure.copy(program = ExposureProgram.P)) } }) else null
        Param.FOCUS -> if (s.focus.manual) ({ update { it.copy(focus = it.focus.copy(manual = false)) } }) else null
        Param.WB -> if (s.wb.mode != WbMode.AUTO) ({ update { it.copy(wb = it.wb.copy(mode = WbMode.AUTO)) } }) else null
        Param.EV -> if (e.evIndex != 0) ({ update { it.copy(exposure = it.exposure.copy(evIndex = 0)) } }) else null
        Param.ZOOM -> null
    }
    val trailing: @Composable () -> Unit = {
        if (auto != null) Text("Auto", style = VType.label.copy(color = V.Amber),
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = auto).padding(horizontal = 10.dp, vertical = 6.dp))
    }
    Column(Modifier.fillMaxWidth()) {
        when (p) {
            Param.EV -> {
                val steps = (a.evRange.last - a.evRange.first) + 1
                val explain = if (e.program == ExposureProgram.P) null else "EV shifts the shutter-priority target" + if (e.program == ExposureProgram.M) " (no effect in M)" else ""
                DialHeader("Exposure", "%+.1f EV".format(e.evIndex * a.evStep), trailing)
                ScaleDial(steps, e.evIndex - a.evRange.first, { i -> update { it.copy(exposure = it.exposure.copy(evIndex = i + a.evRange.first)) } },
                    { i -> "%+.0f".format((i + a.evRange.first) * a.evStep) }, majorEvery = (1f / a.evStep).roundToInt().coerceAtLeast(1),
                    stepWidthDp = 7f, enabled = e.program != ExposureProgram.M, contentDescription = "Exposure compensation")
                explain?.let { Text(it, style = VType.micro.copy(color = V.Ash), modifier = Modifier.padding(horizontal = 20.dp)) }
            }
            Param.ISO -> {
                val stops = ExposureFormat.isoStops(a.isoRange)
                val cur = if (e.program == ExposureProgram.M) e.iso else meta.iso ?: e.iso
                DialHeader("ISO", if (e.program == ExposureProgram.M) "$cur" else "Auto $cur", trailing)
                ScaleDial(stops.size, nearestIndex(stops, cur) { x, y -> abs(Math.log(x.toDouble() / y)) },
                    { i -> update { it.copy(exposure = it.exposure.copy(program = ExposureProgram.M, iso = stops[i],
                        shutterNs = if (it.exposure.program == ExposureProgram.P) (meta.exposureNs ?: it.exposure.shutterNs) else it.exposure.shutterNs)) } },
                    { i -> stops[i].toString() }, majorEvery = 3, contentDescription = "ISO")
                Text(if (e.program == ExposureProgram.M) "Manual: shutter and ISO fixed." else "Turning the ISO dial switches to manual exposure.",
                    style = VType.micro.copy(color = V.Ash), modifier = Modifier.padding(horizontal = 20.dp))
            }
            Param.SHUTTER -> {
                val max = if (s.mode.isVideo) 1_000_000_000L / s.video.fps else a.shutterRangeNs.last
                val stops = ExposureFormat.shutterStops(a.shutterRangeNs.first..max)
                val cur = if (e.program == ExposureProgram.P) meta.exposureNs ?: e.shutterNs else e.shutterNs
                val angle = if (s.mode.isVideo) " · ${(360.0 * s.video.fps * cur / 1e9).roundToInt()}°" else ""
                DialHeader("Shutter", (if (e.program == ExposureProgram.P) "Auto " else "") + ExposureFormat.shutter(cur) + angle, trailing)
                ScaleDial(stops.size, nearestIndex(stops, cur) { x, y -> abs(Math.log(x.toDouble() / y)) },
                    { i -> update { it.copy(exposure = it.exposure.copy(
                        program = if (it.exposure.program == ExposureProgram.M) ExposureProgram.M else ExposureProgram.S, shutterNs = stops[i])) } },
                    { i -> ExposureFormat.shutter(stops[i]) }, majorEvery = 3, stepWidthDp = 16f, contentDescription = "Shutter speed")
                Text(when (e.program) {
                    ExposureProgram.P -> "Turning the shutter dial switches to shutter priority (ISO follows the meter)."
                    ExposureProgram.S -> "Shutter priority: ISO is adjusted by the app's meter."
                    ExposureProgram.M -> "Manual: shutter and ISO fixed."
                }, style = VType.micro.copy(color = V.Ash), modifier = Modifier.padding(horizontal = 20.dp))
            }
            Param.FOCUS -> {
                val steps = 61
                val maxD = a.minFocusDiopters.coerceAtLeast(0.1f)
                val cur = if (s.focus.manual) s.focus.diopters else meta.focusDiopters ?: 0f
                // Non-linear mapping gives more travel to distant focus where depth of field changes slowly.
                fun d(i: Int) = maxD * (i / (steps - 1f)).let { it * it }
                fun idx(v: Float) = (kotlin.math.sqrt((v / maxD).coerceIn(0f, 1f)) * (steps - 1)).roundToInt()
                DialHeader("Focus", (if (s.focus.manual) "" else "AF ") + ExposureFormat.diopterToDistance(cur), trailing)
                ScaleDial(steps, idx(cur), { i -> update { it.copy(focus = it.focus.copy(manual = true, diopters = d(i))) } },
                    { i -> ExposureFormat.diopterToDistance(d(i)) }, majorEvery = 10, stepWidthDp = 8f, enabled = a.manualSensor, contentDescription = "Focus distance")
            }
            Param.WB -> {
                DialHeader("White balance", if (s.wb.mode == WbMode.KELVIN) "${s.wb.kelvin} K" else s.wb.mode.label, trailing)
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (m in WbMode.entries) {
                        val enabled = m != WbMode.KELVIN || a.manualWb
                        ToggleChip(m.label, s.wb.mode == m, { update { it.copy(wb = it.wb.copy(mode = m)) } }, enabled, "needs manual post-processing")
                    }
                }
                if (s.wb.mode == WbMode.KELVIN) {
                    val steps = 81
                    ScaleDial(steps, ((s.wb.kelvin - 2000) / 100).coerceIn(0, steps - 1),
                        { i -> update { it.copy(wb = it.wb.copy(kelvin = 2000 + i * 100)) } }, { i -> "${(2000 + i * 100) / 1000.0}k".replace(".0k", "k") },
                        majorEvery = 10, stepWidthDp = 7f, contentDescription = "Colour temperature")
                    Text("Kelvin is estimated from a daylight calibration; the camera has no native colour-temperature control.",
                        style = VType.micro.copy(color = V.Ash), modifier = Modifier.padding(horizontal = 20.dp))
                }
            }
            Param.ZOOM -> {
                val lo = a.zoomRange.start; val hi = a.zoomRange.endInclusive
                val steps = ((hi - lo) * 10).roundToInt() + 1
                DialHeader("Zoom", "%.1f×".format(s.zoom))
                ScaleDial(steps, ((s.zoom - lo) * 10).roundToInt(), { i -> update { it.copy(zoom = lo + i / 10f) } },
                    { i -> "%.0f×".format(lo + i / 10f) }, majorEvery = 10, stepWidthDp = 6f, contentDescription = "Zoom")
            }
        }
    }
}

@Composable
fun ProgramSwitch(s: CameraSettings, a: Availability, update: ((CameraSettings) -> CameraSettings) -> Unit) {
    val opts = ExposureProgram.entries
    val hlg = s.mode.isVideo && s.video.dynamicRange == DynamicRangeMode.HLG
    Segmented(opts.map { it.label }, opts.indexOf(s.exposure.program), { i ->
        update { it.copy(exposure = it.exposure.copy(program = opts[i])) }
    }, enabled = { i -> opts[i] == ExposureProgram.P || (a.manualSensor && !(opts[i] == ExposureProgram.S && hlg)) })
}

