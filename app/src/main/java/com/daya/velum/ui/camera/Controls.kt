package com.daya.velum.ui.camera

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.daya.velum.capability.LensOption
import com.daya.velum.settings.CaptureMode
import com.daya.velum.ui.components.G
import com.daya.velum.ui.components.Glyph
import com.daya.velum.ui.theme.V
import com.daya.velum.ui.theme.VType
import kotlinx.coroutines.withTimeoutOrNull

/** A round icon button with a 48 dp touch target; content rotates with the device. */
@Composable
fun IconBtn(g: G, desc: String, rotation: Float, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = V.Bone, enabled: Boolean = true) {
    Box(
        modifier.size(48.dp).clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = desc, onClick = onClick)
            .semantics { contentDescription = desc },
        contentAlignment = Alignment.Center,
    ) { Glyph(g, Modifier.rotate(rotation), tint = if (enabled) tint else V.Dim) }
}

@Composable
fun ModeRail(current: CaptureMode, enabled: Boolean, onMode: (CaptureMode) -> Unit, modifier: Modifier = Modifier, onMore: () -> Unit = {}) {
    val scroll = rememberScrollState()
    val items: List<CaptureMode?> = com.daya.velum.settings.RAIL_MODES +
        (if (current !in com.daya.velum.settings.RAIL_MODES) listOf(current) else emptyList()) + listOf(null)
    // Keep the active mode centred, like a physical mode dial.
    val centres = remember { mutableStateMapOf<CaptureMode, Int>() }
    var railWidth by remember { mutableIntStateOf(0) }
    LaunchedEffect(current, railWidth, centres[current]) {
        val c = centres[current] ?: return@LaunchedEffect
        scroll.animateScrollTo((c - railWidth / 2).coerceAtLeast(0))
    }
    Row(
        modifier.fillMaxWidth().onSizeChanged { railWidth = it.width }.horizontalScroll(scroll).padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (m in items) {
            if (m == null) {
                Column(
                    Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled, role = Role.Button, onClickLabel = "More modes", onClick = onMore)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("More", style = VType.body.copy(color = if (enabled) V.Ash else V.Dim))
                    Spacer(Modifier.height(4.dp)); Box(Modifier.size(width = 0.dp, height = 2.dp))
                }
                continue
            }
            val sel = m == current
            val col by animateColorAsState(if (sel) V.Bone else V.Ash, label = "mode")
            Column(
                Modifier.onGloballyPositioned { lc -> centres[m] = (lc.positionInParent().x + lc.size.width / 2f).toInt() }
                    .clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled && !sel, role = Role.Tab) { onMode(m) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(m.label, style = (if (sel) VType.label else VType.body).copy(color = if (enabled || sel) col else V.Dim))
                Spacer(Modifier.height(4.dp))
                Box(Modifier.size(width = if (sel) 14.dp else 0.dp, height = 2.dp).background(V.Amber))
            }
        }
    }
}

@Composable
fun LensChips(lenses: List<LensOption>, selectedKey: String, zoom: Float, rotation: Float, onLens: (LensOption) -> Unit, modifier: Modifier = Modifier) {
    if (lenses.size <= 1) return
    Row(
        modifier.background(V.Scrim, RoundedCornerShape(24.dp)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // The active chip is the lens whose zoom ratio is the largest one not exceeding the current zoom.
        val active = lenses.filter { it.zoomRatio <= zoom + 0.01f }.maxByOrNull { it.zoomRatio } ?: lenses.first()
        for (l in lenses) {
            val sel = l.key == active.key
            val size by animateDpAsState(if (sel) 44.dp else 38.dp, label = "lens")
            val text = if (sel && kotlin.math.abs(zoom - l.zoomRatio) > 0.05f) "%.1f×".format(zoom).replace(".0×", "×") else l.label
            Box(
                Modifier.size(size).clip(CircleShape).background(if (sel) V.GraphiteHi else Color.Transparent)
                    .clickable(role = Role.Button, onClickLabel = "${l.label} lens") { onLens(l) }
                    .semantics { contentDescription = "Lens " + l.label.replace("×", "x") },
                contentAlignment = Alignment.Center,
            ) {
                Text(text, style = VType.small.copy(color = if (sel) V.Amber else V.Bone), modifier = Modifier.rotate(rotation), textAlign = TextAlign.Center)
            }
        }
    }
}

/**
 * Shutter. Photo: tap captures, hold for burst. Video: tap starts/stops; inner disc morphs to a tally square while
 * recording.
 */
@Composable
fun ShutterButton(video: Boolean, recording: Boolean, busy: Boolean, onTap: () -> Unit, onHoldStart: () -> Unit, onHoldEnd: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }
    val inner by animateDpAsState(if (recording) 26.dp else if (pressed) 54.dp else 60.dp, label = "inner")
    val corner by animateDpAsState(if (recording) 6.dp else 30.dp, label = "corner")
    val col by animateColorAsState(if (video) V.Tally else V.Bone, label = "col")
    val ring by animateFloatAsState(if (busy) 0.5f else 1f, label = "ring")
    Box(
        Modifier.size(78.dp).border(3.dp, V.Bone.copy(alpha = ring), CircleShape).padding(6.dp)
            .semantics { contentDescription = if (video) (if (recording) "Stop recording" else "Start recording") else "Take photo" }
            .pointerInput(video) {
                awaitEachGesture {
                    awaitFirstDown()
                    pressed = true
                    val up = withTimeoutOrNull(420) { waitForUpOrCancellation() }
                    if (up != null) {
                        pressed = false
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                        onTap()
                    } else if (!video) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onHoldStart()
                        waitForUpOrCancellation()
                        pressed = false
                        onHoldEnd()
                    } else {
                        waitForUpOrCancellation(); pressed = false
                        onTap()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) { Box(Modifier.size(inner).clip(RoundedCornerShape(corner)).background(col)) }
}

@Composable
fun Thumb(bmp: Bitmap?, rotation: Float, onClick: () -> Unit) {
    Box(
        Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(V.GraphiteHi)
            .border(1.dp, V.Line, RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClickLabel = "Open gallery", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (bmp != null) Image(bmp.asImageBitmap(), "Last capture", Modifier.fillMaxSize().rotate(rotation), contentScale = ContentScale.Crop)
    }
}

/** A labelled value chip used in the pro strip. Amber when manual, bone when automatic. */
@Composable
fun ParamChip(name: String, value: String, manual: Boolean, selected: Boolean, enabled: Boolean, rotation: Float, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(10.dp))
            .background(if (selected) V.GraphiteHi else Color.Transparent)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = "Adjust $name", onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(name, style = VType.micro.copy(color = if (enabled) V.Ash else V.Dim))
        Text(value, style = VType.label.copy(color = when { !enabled -> V.Dim; manual -> V.Amber; else -> V.Bone }))
    }
}

@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, enabled: (Int) -> Boolean = { true }) {
    Row(modifier.background(V.Graphite, RoundedCornerShape(10.dp)).padding(3.dp)) {
        options.forEachIndexed { i, o ->
            val sel = i == selected
            Box(
                Modifier.clip(RoundedCornerShape(8.dp)).background(if (sel) V.GraphiteHi else Color.Transparent)
                    .clickable(enabled = enabled(i) && !sel, role = Role.RadioButton) { onSelect(i) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) { Text(o, style = VType.small.copy(color = when { !enabled(i) -> V.Dim; sel -> V.Amber; else -> V.Bone })) }
        }
    }
}

@Composable
fun ToggleChip(label: String, on: Boolean, onClick: () -> Unit, enabled: Boolean = true, reason: String? = null) {
    Box(
        Modifier.clip(RoundedCornerShape(16.dp))
            .border(1.dp, if (on) V.Amber else V.Line, RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, role = Role.Switch, onClickLabel = label, onClick = onClick)
            .semantics { if (!enabled && reason != null) contentDescription = "$label unavailable: $reason" }
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) { Text(label, style = VType.small.copy(color = when { !enabled -> V.Dim; on -> V.Amber; else -> V.Bone })) }
}
