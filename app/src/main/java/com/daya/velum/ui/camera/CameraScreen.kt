package com.daya.velum.ui.camera

import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.daya.velum.camera.EngineEvent
import com.daya.velum.camera.EngineStatus
import com.daya.velum.camera.ExposureFormat
import com.daya.velum.imaging.LookCatalog
import com.daya.velum.settings.*
import com.daya.velum.ui.CameraViewModel
import com.daya.velum.ui.Notice
import com.daya.velum.ui.components.G
import com.daya.velum.ui.theme.V
import com.daya.velum.ui.theme.VType
import com.daya.velum.video.RecState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.runtime.snapshotFlow

@Composable
fun CameraScreen(vm: CameraViewModel, uiRotation: Float, rollDeg: Float, onGallery: () -> Unit, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val s by vm.settings.collectAsState()
    val caps by vm.caps.collectAsState()
    val ready by vm.ready.collectAsState()
    val meta by vm.engine.meta.collectAsState()
    val stats by vm.engine.stats.collectAsState()
    val rec by vm.engine.rec.collectAsState()
    val status by vm.engine.status.collectAsState()
    val thumb by vm.thumb.collectAsState()
    val countdown by vm.countdown.collectAsState()
    val inFlight by vm.engine.capturesInFlight.collectAsState()
    val grading by vm.grading.collectAsState()
    val flashStamp by vm.shutterFlash.collectAsState()
    val a = remember(s, caps) { vm.availability }
    val rot by animateFloatAsState(uiRotation, tween(260), label = "rot")

    val previewView = remember {
        PreviewView(ctx).apply {
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
            scaleType = PreviewView.ScaleType.FIT_CENTER
            setBackgroundColor(android.graphics.Color.BLACK)
        }
    }
    DisposableEffect(owner) {
        vm.engine.attach(owner, previewView.surfaceProvider)
        if (ready) vm.engine.apply(vm.settings.value) else vm.onProviderReady()
        onDispose { vm.engine.detach() }
    }

    // Smooth switches: while the camera rebinds (mode, lens, front/back), the last frame stays up, blurred, and fades
    // out once the new stream is live, instead of a black flash.
    var held by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var streaming by remember { mutableStateOf(false) }
    DisposableEffect(owner) {
        val obs = androidx.lifecycle.Observer<PreviewView.StreamState> { streaming = it == PreviewView.StreamState.STREAMING }
        previewView.previewStreamState.observe(owner, obs)
        onDispose { previewView.previewStreamState.removeObserver(obs) }
    }
    val holdFrame: () -> Unit = { held = runCatching { previewView.bitmap?.asImageBitmap() }.getOrNull() }
    val heldAlpha = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(held) {
        if (held == null) return@LaunchedEffect
        heldAlpha.snapTo(1f)
        // Wait for the stream to drop (a rebind started) and come back, with limits in case no rebind happens.
        withTimeoutOrNull(500) { snapshotFlow { streaming }.first { !it } }
        withTimeoutOrNull(3000) { snapshotFlow { streaming }.first { it } }
        delay(120)
        heldAlpha.animateTo(0f, tween(320))
        held = null
    }

    var param by remember { mutableStateOf<Param?>(null) }
    var assists by remember { mutableStateOf(false) }
    var colour by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<String?>(null) }
    var focusAt by remember { mutableStateOf<Offset?>(null) }
    var focusStamp by remember { mutableLongStateOf(0L) }
    var focusOk by remember { mutableStateOf<Boolean?>(null) }
    var notice by remember { mutableStateOf<Notice?>(null) }
    LaunchedEffect(Unit) { vm.notices.collect { notice = it } }
    LaunchedEffect(notice) { notice?.let { delay(3200); if (notice == it) notice = null } }
    LaunchedEffect(Unit) { vm.engine.events.collect { if (it is EngineEvent.Focused) focusOk = it.success } }
    LaunchedEffect(s.mode) { param = null }

    val recording = rec is RecState.Recording || rec is RecState.Paused
    val video = s.mode.isVideo
    val vfAspect = if (video) 9f / 16f else when (s.photoAspect) { PhotoAspect.R4_3 -> 3f / 4f; PhotoAspect.R16_9 -> 9f / 16f; PhotoAspect.R1_1 -> 1f; PhotoAspect.XPAN -> 24f / 65f }
    // The 65:24 strip is taller than the screen when held upright, so it sits centred inside the 3:4 area.
    val vfModifier = if (!video && s.photoAspect == PhotoAspect.XPAN)
        Modifier.fillMaxWidth().aspectRatio(3f / 4f).wrapContentWidth().aspectRatio(vfAspect, matchHeightConstraintsFirst = true)
    else Modifier.fillMaxWidth().aspectRatio(vfAspect)
    // Macro has its own lens (the closest-focusing one), so the lens chips would only switch away from it.
    val lensList = if (s.mode == CaptureMode.MACRO) emptyList() else caps?.lenses.orEmpty().filter { l -> l.facing == a.lens?.facing }

    Box(Modifier.fillMaxSize().background(V.Black)) {
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            TopBar(vm, s, a, rot, recording, onSettings, onFormat = { sheet = "format" }, onLooks = { sheet = "looks" }, onRecipes = { sheet = "recipes" })
            // ---- viewfinder
            Box(vfModifier) {
                if (s.mode == CaptureMode.SLOW_MO) SlowMoViewfinder(vm, s.slowMoFps) else AndroidView({ previewView }, Modifier.fillMaxSize())
                held?.let { h ->
                    androidx.compose.foundation.Image(h, null, Modifier.fillMaxSize().alpha(heldAlpha.value).blur(18.dp),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                }
                val cine = if (s.mode == CaptureMode.CINEMA) s.cine.ratio.ratio else null
                GuidesOverlay(s.monitor.grid, s.monitor.centerMark, s.monitor.safeArea, cine)
                if (s.monitor.level) LevelOverlay(rollDeg)
                FocusRing(focusAt, focusStamp, focusOk)
                if (s.faceDetect && meta.faces.isNotEmpty() && s.mode != CaptureMode.MOVIE) FaceBoxes(meta.faces, previewView)
                // Low light: suggest the real multi-frame Night mode instead of a noisy single frame.
                val dim = s.mode == CaptureMode.PHOTO && (meta.iso ?: 0) >= 1600 && (meta.exposureNs ?: 0) >= 33_000_000L && a.nightExtension
                if (dim) Text("Low light \u00b7 tap for Night", style = VType.small.copy(color = V.Black),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp).clip(RoundedCornerShape(16.dp))
                        .background(V.Amber).clickable { vm.setMode(CaptureMode.NIGHT) }.padding(horizontal = 14.dp, vertical = 7.dp))
                // Too close for the main lens (focus at its near limit): suggest Macro on the ultra-wide.
                val close = s.mode == CaptureMode.PHOTO && !dim && a.minFocusDiopters > 0f && (meta.focusDiopters ?: 0f) >= a.minFocusDiopters * 0.85f
                if (close) Text("Close-up \u00b7 tap for Macro", style = VType.small.copy(color = V.Black),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp).clip(RoundedCornerShape(16.dp))
                        .background(V.Amber).clickable { vm.setMode(CaptureMode.MACRO) }.padding(horizontal = 14.dp, vertical = 7.dp))
                if (s.mode == CaptureMode.CLONE) {
                    val cl by vm.clone.collectAsState()
                    cl.ghost?.let { g ->
                        androidx.compose.foundation.Image(g.asImageBitmap(), "First shot, shown faintly", Modifier.fillMaxSize().alpha(0.4f),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                    }
                }
                if (s.mode == CaptureMode.MOVIE) {
                    val mu by vm.movie.ui.collectAsState()
                    MovieReticle(mu)
                    // Keep the lens focused on a tracked subject.
                    LaunchedEffect(mu.tracking) {
                        while (mu.tracking) {
                            vm.movie.ui.value.focusView?.let { (x, y) -> vm.engine.focusAt(previewView.meteringPointFactory.createPoint(x, y)) }
                            delay(1200)
                        }
                    }
                }
                Box(
                    Modifier.fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, _, zoom, _ ->
                                if (zoom != 1f) vm.setZoom((vm.settings.value.zoom * zoom).coerceIn(a.zoomRange.start, a.zoomRange.endInclusive))
                            }
                        }
                        .pointerInput(s.mode) {
                            val movieMode = s.mode == CaptureMode.MOVIE
                            detectTapGestures(
                                onTap = { o ->
                                    if (movieMode) {
                                        vm.movie.calibrate(previewView.meteringPointFactory, size.width.toFloat(), size.height.toFloat())
                                        vm.movie.tap(o.x, o.y, track = false)
                                    } else { focusAt = o; focusStamp = System.nanoTime(); focusOk = null }
                                    vm.engine.focusAt(previewView.meteringPointFactory.createPoint(o.x, o.y))
                                },
                                onDoubleTap = if (!movieMode) null else { o ->
                                    vm.movie.calibrate(previewView.meteringPointFactory, size.width.toFloat(), size.height.toFloat())
                                    vm.movie.tap(o.x, o.y, track = true)
                                    vm.engine.focusAt(previewView.meteringPointFactory.createPoint(o.x, o.y))
                                },
                                onPress = {
                                    // Hold-to-compare: show the ungraded image while pressed (after the long-press delay).
                                    val released = withTimeoutOrNullCompat(450) { tryAwaitRelease() }
                                    if (released == null) { vm.engine.setCompare(true); tryAwaitRelease(); vm.engine.setCompare(false) }
                                },
                            )
                        },
                )
                // HUD
                Column(Modifier.align(Alignment.TopStart).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (s.mode.isPro || s.proControls) Readout(meta, s, Modifier)
                    if (s.monitor.histogram && !(video && s.video.dynamicRange == DynamicRangeMode.HLG)) Histogram(stats, Modifier.rotate(rot))
                }
                if (recording || rec is RecState.Finalizing) RecBadge(rec, vm, Modifier.align(Alignment.TopEnd).padding(10.dp))
                if (countdown > 0) Text("$countdown", style = VType.display.copy(fontSize = VType.display.fontSize * 3), modifier = Modifier.align(Alignment.Center).rotate(rot))
                ShutterBlink(flashStamp)
                val busy by vm.busy.collectAsState()
                busy?.let { b ->
                    Text(b, style = VType.small, modifier = Modifier.align(Alignment.Center).background(V.Scrim, RoundedCornerShape(12.dp)).padding(horizontal = 16.dp, vertical = 10.dp))
                }
                val le by vm.longExposureProgress.collectAsState()
                le?.let { p ->
                    p.preview?.let { bmp ->
                        androidx.compose.foundation.Image(bmp.asImageBitmap(), "Long exposure in progress", Modifier.fillMaxSize(),
                            contentScale = androidx.compose.ui.layout.ContentScale.Fit)
                    }
                    Column(Modifier.align(Alignment.Center).background(V.Scrim, RoundedCornerShape(14.dp)).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("%.1f / %d s".format(p.elapsedMs / 1000f, p.totalMs / 1000), style = VType.title)
                        Text("${p.frames} frames", style = VType.small.copy(color = V.Ash))
                        Text("Hold still", style = VType.micro.copy(color = V.Amber))
                    }
                }
                when (val st = status) {
                    is EngineStatus.Error -> Column(Modifier.align(Alignment.Center).background(V.Scrim, RoundedCornerShape(12.dp)).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(st.message, style = VType.body, textAlign = TextAlign.Center)
                        TextButton({ vm.engine.retry(vm.settings.value) }) { Text("Try again", color = V.Amber) }
                    }
                    else -> {}
                }
                if (!ready) Text("Starting camera", style = VType.small.copy(color = V.Ash), modifier = Modifier.align(Alignment.Center))
            }
        }

        // ---- controls
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(if (video) androidx.compose.ui.graphics.Brush.verticalGradient(0f to Color.Transparent, 0.22f to V.Scrim, 1f to V.Scrim)
                    else androidx.compose.ui.graphics.SolidColor(Color.Transparent))
                .padding(bottom = 8.dp)
                .windowInsetsPadding(WindowInsets.navigationBars),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            notice?.let { n ->
                Text(n.text, style = VType.small, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp).background(V.GraphiteHi, RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 8.dp))
            }
            if (grading > 0) Text("Applying look to $grading photo${if (grading > 1) "s" else ""}", style = VType.micro.copy(color = V.Ash))
            LensChips(lensList, s.lensKey, s.zoom, rot, { l -> if (l.key != s.lensKey) holdFrame(); vm.selectLens(l.key) }, Modifier.padding(vertical = 8.dp))

            param?.let { p ->
                Box(Modifier.fillMaxWidth().padding(bottom = 4.dp)) { ParamDial(p, s, a, meta) { f -> vm.update(f) } }
            }
            AnimatedVisibility(assists && s.mode.isPro, enter = stripIn, exit = stripOut) { AssistRow(vm, s) }
            if (s.mode == CaptureMode.CINEMA) CreatorStrip(vm, s, onLooks = { sheet = "looks" }, onRecipes = { sheet = "recipes" })
            if (s.mode == CaptureMode.MOVIE) MovieStrip(vm, s)
            if (s.mode == CaptureMode.VLOG) VlogStrip(vm)
            if (s.mode == CaptureMode.SLOW_MO) {
                val sm by vm.slowMo.state.collectAsState()
                Column(Modifier.padding(horizontal = 14.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (f in listOf(120, 240)) ToggleChip("$f fps", s.slowMoFps == f, { if (sm !is com.daya.velum.video.SlowMoState.Recording) vm.update { it.copy(slowMoFps = f) } })
                    }
                    Text(when (val st = sm) {
                        is com.daya.velum.video.SlowMoState.Ready -> "${st.size.width}\u00d7${st.size.height} at ${st.fps} fps \u2192 plays ${st.fps / 30}\u00d7 slower. No sound in slow motion."
                        is com.daya.velum.video.SlowMoState.Recording -> "Recording at ${st.fps} fps"
                        is com.daya.velum.video.SlowMoState.Error -> st.message
                        else -> "Starting high-speed camera"
                    }, style = VType.micro.copy(color = V.Ash))
                }
            }
            if (s.mode == CaptureMode.DUAL) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ToggleChip(if (s.dualSwap) "Front is main" else "Back is main", true, { if (!recording) vm.update { it.copy(dualSwap = !it.dualSwap) } })
                for ((i, n) in listOf("Top left", "Top right", "Bottom left", "Bottom right").withIndex())
                    ToggleChip(n, s.dualCorner == i, { if (!recording) vm.update { it.copy(dualCorner = i) } })
            }
            // CameraX composes the two cameras without per-camera rotation: held sideways, the mirrored front picture
            // ends up upside down in the recording (verified on device). Say so instead of recording a broken clip.
            if (s.mode == CaptureMode.DUAL && uiRotation != 0f) Text("Hold the phone upright for Dual video: sideways, the small picture is recorded upside down.",
                style = VType.micro.copy(color = V.Amber), modifier = Modifier.padding(horizontal = 16.dp))
            AnimatedVisibility(s.mode == CaptureMode.LONG_EXPOSURE, enter = stripIn, exit = stripOut) { LongExposureStrip(vm, s) }
            AnimatedVisibility(s.mode in PHOTO_STRIP_MODES, enter = stripIn, exit = stripOut) { PhotoModeStrip(vm, s) }
            if (s.mode == CaptureMode.EFFECTS) EffectsStrip(vm, s, recording)
            AnimatedVisibility(colour && s.mode != CaptureMode.CINEMA, enter = stripIn, exit = stripOut) { ColourStrip(vm, s, recording, onAll = { sheet = "looks" }) }
            ParamStrip(vm, s, a, meta, param, rot, assists, onParam = { param = if (param == it) null else it }, onAssists = { assists = !assists },
                colourOpen = colour, onColour = { colour = !colour })

            ModeRail(s.mode, !recording, { m -> if (m != s.mode) holdFrame(); vm.setMode(m) }, Modifier.padding(vertical = 6.dp), onMore = { sheet = "more" })
            Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                if (recording) {
                    IconBtn(if (rec is RecState.Paused) G.PLAY else G.PAUSE, if (rec is RecState.Paused) "Resume" else "Pause", rot,
                        { if (rec is RecState.Paused) vm.engine.resumeRecording() else vm.engine.pauseRecording() })
                } else Thumb(thumb?.second, 0f, onGallery)
                val smRec = vm.slowMo.state.collectAsState().value is com.daya.velum.video.SlowMoState.Recording
                ShutterButton(video, recording || smRec, inFlight > 2, onTap = vm::shutter, onHoldStart = vm::burstStart, onHoldEnd = vm::burstStop)
                if (recording) IconBtn(G.PLUS, "Take a still", rot, { vm.snapshot() }, enabled = vm.engine.canSnapshot)
                else IconBtn(G.FLIP, "Switch camera", rot, { holdFrame(); vm.flipCamera() })
            }
        }
    }

    when (sheet) {
        "looks" -> LookSheet(vm) { sheet = null }
        "recipes" -> RecipeSheet(vm) { sheet = null }
        "format" -> FormatSheet(vm) { sheet = null }
        "more" -> MoreSheet(current = s.mode, dualOk = vm.engine.dualSupported(), onPick = { vm.setMode(it); sheet = null }) { sheet = null }
    }
}

private suspend fun <T> withTimeoutOrNullCompat(ms: Long, block: suspend () -> T): T? = kotlinx.coroutines.withTimeoutOrNull(ms) { block() }

@Composable
private fun TopBar(
    vm: CameraViewModel, s: CameraSettings, a: Availability, rot: Float, recording: Boolean,
    onSettings: () -> Unit, onFormat: () -> Unit, onLooks: () -> Unit, onRecipes: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        val flashG = when (s.flash) { FlashMode.OFF -> G.FLASH_OFF; FlashMode.AUTO -> G.FLASH_AUTO; FlashMode.ON -> G.FLASH_ON; FlashMode.TORCH -> G.TORCH }
        IconBtn(flashG, "Flash ${s.flash.label}", rot, vm::cycleFlash, enabled = a.flash, tint = if (s.flash != FlashMode.OFF) V.Amber else V.Bone)
        if (!s.mode.isVideo) {
            Box(contentAlignment = Alignment.Center) {
                IconBtn(G.TIMER, if (s.timerSec == 0) "Timer off" else "Timer ${s.timerSec} seconds", rot, vm::cycleTimer, tint = if (s.timerSec > 0) V.Amber else V.Bone)
                if (s.timerSec > 0) Text("${s.timerSec}", style = VType.micro.copy(color = V.Amber), modifier = Modifier.align(Alignment.BottomEnd).padding(end = 6.dp, bottom = 6.dp))
            }
        }
        IconBtn(G.GRID, "Grid ${s.monitor.grid.label}", rot, {
            vm.update { it.copy(monitor = it.monitor.copy(grid = GridType.entries[(it.monitor.grid.ordinal + 1) % GridType.entries.size])) }
        }, tint = if (s.monitor.grid != GridType.NONE) V.Amber else V.Bone)
        Spacer(Modifier.weight(1f))
        // Format chip: what will be recorded / saved.
        val fmt = if (s.mode.isVideo) "${s.video.quality.label} ${s.video.fps}${if (s.video.dynamicRange == DynamicRangeMode.HLG) " HLG" else ""}"
            else (if (s.mode.extension != null) "JPEG" else s.photoFormat.label) + when (s.photoAspect) { PhotoAspect.R4_3 -> ""; PhotoAspect.XPAN -> " Pano"; else -> " ${s.photoAspect.label}" }
        Text(fmt, style = VType.label, modifier = Modifier.clip(RoundedCornerShape(8.dp))
            .then(if (!recording) Modifier.clickableRole(onFormat) else Modifier)
            .border1().padding(horizontal = 10.dp, vertical = 6.dp))
        Spacer(Modifier.weight(1f))
        IconBtn(G.RECIPES, "Recipes and presets", rot, onRecipes, enabled = !recording)
        IconBtn(G.LOOKS, "Looks", rot, onLooks, tint = if (s.look.lookId != "neutral") V.Amber else V.Bone)
        IconBtn(G.GEAR, "Settings", rot, onSettings, enabled = !recording)
    }
}

private fun Modifier.clickableRole(onClick: () -> Unit) = this.clickable(onClick = onClick)
private fun Modifier.border1() = this.border(1.dp, V.Line, RoundedCornerShape(8.dp))

@Composable
private fun ParamStrip(
    vm: CameraViewModel, s: CameraSettings, a: Availability, meta: com.daya.velum.camera.LiveMeta, sel: Param?, rot: Float, assists: Boolean,
    onParam: (Param) -> Unit, onAssists: () -> Unit, colourOpen: Boolean, onColour: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
    ) {
        val e = s.exposure
        val nightLock = false
        if (s.mode.isPro) {
            // Assist first: the monitoring tools must never hide off-screen.
            ParamChip("Assist", if (assistCount(s.monitor) > 0) "${assistCount(s.monitor)} on" else "Off", assistCount(s.monitor) > 0, assists, true, rot) { onAssists() }
            ProgramSwitch(s, a) { vm.update(it) }
            Spacer(Modifier.width(6.dp))
            ParamChip("ISO", if (e.program == ExposureProgram.M) "${e.iso}" else meta.iso?.toString() ?: "—", e.program == ExposureProgram.M, sel == Param.ISO, a.manualSensor, rot) { onParam(Param.ISO) }
            ParamChip("Shutter", ExposureFormat.shutter(if (e.program == ExposureProgram.P) meta.exposureNs ?: 0 else e.shutterNs), e.program != ExposureProgram.P, sel == Param.SHUTTER, a.manualSensor, rot) { onParam(Param.SHUTTER) }
        }
        ParamChip("EV", "%+.1f".format(e.evIndex * a.evStep), e.evIndex != 0, sel == Param.EV, !nightLock && e.program != ExposureProgram.M, rot) { onParam(Param.EV) }
        // A recipe can set white balance; outside Pro there is no WB dial, so show it whenever it is not Auto.
        if (!s.mode.isPro && s.wb.mode != WbMode.AUTO) {
            ParamChip("WB", if (s.wb.mode == WbMode.KELVIN) "${s.wb.kelvin}K" else s.wb.mode.label, true, false, true, rot) {
                vm.update { it.copy(wb = it.wb.copy(mode = WbMode.AUTO)) }
                vm.notify("White balance back to Auto")
            }
        }
        if (s.mode.isPro) {
            ParamChip("Focus", if (s.focus.manual) ExposureFormat.diopterToDistance(s.focus.diopters) else "AF", s.focus.manual, sel == Param.FOCUS, a.manualSensor, rot) { onParam(Param.FOCUS) }
            ParamChip("WB", if (s.wb.mode == WbMode.KELVIN) "${s.wb.kelvin}K" else s.wb.mode.label, s.wb.mode != WbMode.AUTO, sel == Param.WB, true, rot) { onParam(Param.WB) }
        }
        ParamChip("Zoom", "%.1f×".format(s.zoom), false, sel == Param.ZOOM, true, rot) { onParam(Param.ZOOM) }
        if (s.mode == CaptureMode.PHOTO) {
            val ok = s.photoFormat == PhotoFormat.JPEG
            ParamChip("Motion", when { !ok -> "JPEG only"; s.motionPhoto -> "On"; else -> "Off" }, s.motionPhoto && ok, false, ok, rot) {
                vm.update { it.copy(motionPhoto = !it.motionPhoto) }
                vm.notify(if (!s.motionPhoto) "Motion photo on: each photo keeps about 3 s of silent video around the moment." else "Motion photo off")
            }
        }
        if (s.mode in com.daya.velum.camera.HORIZON_MODES) {
            val hlg = s.video.dynamicRange == DynamicRangeMode.HLG
            ParamChip("Horizon", when { hlg -> "Not in HLG"; s.horizonLock -> "Locked"; else -> "Off" }, s.horizonLock && !hlg, false, !hlg, rot) {
                vm.update { it.copy(horizonLock = !it.horizonLock) }
                vm.notify(if (!s.horizonLock) "Horizon lock on: the video stays level up to 20° of tilt, with a small crop." else "Horizon lock off")
            }
        }
        if (s.mode != CaptureMode.CINEMA) {
            val look = vm.looks.byId(s.look.lookId)
            ParamChip("Colour", look?.let { it.brand ?: it.name.take(10) } ?: "Neutral", look != null && !look.isNeutral, colourOpen, true, rot) { onColour() }
        }
    }
}

/**
 * Quick colour: pick a camera maker, then one of its looks; the viewfinder shows it live and, for photos, the saved
 * graded copy carries it. "All looks" opens the full browser.
 */
@Composable
private fun ColourStrip(vm: CameraViewModel, s: CameraSettings, recording: Boolean, onAll: () -> Unit) {
    val current = vm.looks.byId(s.look.lookId) ?: LookCatalog.NEUTRAL
    // Follows the selected look (picked here, in All looks, or Off), while still letting the chips browse groups.
    var brand by remember(current.id) { mutableStateOf(current.brand ?: "Essentials") }
    Column(Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ToggleChip("Off", current.isNeutral, { vm.selectLook(LookCatalog.NEUTRAL) })
            ToggleChip("Essentials", brand == "Essentials", { brand = "Essentials" })
            for (b in LookCatalog.BRANDS) ToggleChip(b, brand == b, { brand = b })
            ToggleChip("All looks", current.brand == null && !current.isNeutral, onAll)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val list = if (brand == "Essentials") LookCatalog.ESSENTIALS.drop(1).mapNotNull { LookCatalog.byId(it) } else LookCatalog.all.filter { it.brand == brand }
            for (l in list) ToggleChip(l.name, l.id == current.id, { vm.selectLook(l) })
        }
        LookCatalog.BRAND_MAKERS[brand]?.let {
            Text("Inspired by $it · not affiliated", style = VType.micro.copy(color = V.Bone), maxLines = 1,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp).clip(RoundedCornerShape(6.dp)).background(V.Scrim).padding(horizontal = 6.dp, vertical = 2.dp))
        }
        val note = when {
            current.isNeutral -> "Pick a style to see its colour live in the viewfinder."
            s.mode.isVideo && s.videoLook.target != LookTarget.BAKED -> "Shown in the viewfinder only. Turn on \"Record the look\" to put it in the video."
            s.mode.isVideo -> "Recorded into the video."
            s.photoLook.target == LookTarget.BAKED -> "Each photo is saved with this colour" + if (s.photoLook.keepOriginal) ", plus the untouched original." else "."
            else -> "Viewfinder only. Turn on \"Save graded copy\" in All looks to keep it in photos."
        }
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(note, style = VType.micro.copy(color = V.Ash), modifier = Modifier.weight(1f))
            if (s.mode.isVideo && !current.isNeutral) ToggleChip("Record the look", s.videoLook.target == LookTarget.BAKED,
                { if (!recording) vm.setLookTarget(if (s.videoLook.target == LookTarget.BAKED) LookTarget.MONITOR else LookTarget.BAKED) })
        }
    }
}

private fun assistCount(m: MonitorSettings) = listOf(m.zebra, m.peaking, m.falseColor, m.grey, m.histogram, m.level, m.centerMark, m.safeArea).count { it }

@Composable
private fun AssistRow(vm: CameraViewModel, s: CameraSettings) {
    val m = s.monitor
    val hdr = s.mode.isVideo && s.video.dynamicRange == DynamicRangeMode.HLG
    val gpu = !hdr && s.mode.extension == null
    val why = if (hdr) "off in HLG" else "off with ${s.mode.label} processing"
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ToggleChip("Zebra ${m.zebraPercent}%", m.zebra, { vm.update { it.copy(monitor = it.monitor.copy(zebra = !it.monitor.zebra)) } }, gpu, why)
            ToggleChip("Peaking", m.peaking, { vm.update { it.copy(monitor = it.monitor.copy(peaking = !it.monitor.peaking)) } }, gpu, why)
            ToggleChip("False colour", m.falseColor, { vm.update { it.copy(monitor = it.monitor.copy(falseColor = !it.monitor.falseColor)) } }, gpu, why)
            ToggleChip("Greyscale", m.grey, { vm.update { it.copy(monitor = it.monitor.copy(grey = !it.monitor.grey)) } }, gpu, why)
            ToggleChip("Histogram", m.histogram, { vm.update { it.copy(monitor = it.monitor.copy(histogram = !it.monitor.histogram)) } }, gpu, why)
            ToggleChip("Level", m.level, { vm.update { it.copy(monitor = it.monitor.copy(level = !it.monitor.level)) } })
            ToggleChip("Centre", m.centerMark, { vm.update { it.copy(monitor = it.monitor.copy(centerMark = !it.monitor.centerMark)) } })
            ToggleChip("Safe area", m.safeArea, { vm.update { it.copy(monitor = it.monitor.copy(safeArea = !it.monitor.safeArea)) } })
        }
        if (m.zebra) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (z in listOf(70, 80, 90, 95, 100)) ToggleChip("$z%", m.zebraPercent == z, { vm.update { it.copy(monitor = it.monitor.copy(zebraPercent = z)) } })
        }
        Text("Monitoring aids are drawn in the viewfinder only and never recorded.", style = VType.micro.copy(color = V.Dim), modifier = Modifier.padding(horizontal = 18.dp))
    }
}

/** Film mode: recipes (scenes), looks and framing on one quick strip. */
@Composable
private fun CreatorStrip(vm: CameraViewModel, s: CameraSettings, onLooks: () -> Unit, onRecipes: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            ToggleChip("Scenes", false, onRecipes)
            ToggleChip(vm.looks.byId(s.videoLook.lookId)?.name ?: "Neutral", s.videoLook.lookId != "neutral", onLooks)
            Text(if (s.videoLook.target == LookTarget.BAKED) "baked" else "monitor", style = VType.micro.copy(color = V.Ash))
            Spacer(Modifier.width(8.dp))
            for (r in CineRatio.entries) ToggleChip(r.label, s.cine.ratio == r, { vm.update { it.copy(cine = it.cine.copy(ratio = r)) } })
        }
        Row(Modifier.padding(horizontal = 14.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Segmented(listOf("Guide", "Crop output"), if (s.cine.framing == CineFraming.GUIDE) 0 else 1,
                { i -> vm.update { it.copy(cine = it.cine.copy(framing = if (i == 0) CineFraming.GUIDE else CineFraming.CROP)) } })
            Spacer(Modifier.width(10.dp))
            Text(if (s.cine.framing == CineFraming.GUIDE) "Records full 16:9; lines are a guide." else "Records the ${s.cine.ratio.label} crop (not anamorphic).",
                style = VType.micro.copy(color = V.Ash))
        }
    }
}

@Composable
private fun RecBadge(rec: RecState, vm: CameraViewModel, modifier: Modifier) {
    val (ns, bytes, amp) = when (rec) {
        is RecState.Recording -> Triple(rec.elapsedNs, rec.bytes, rec.audioAmplitude)
        is RecState.Paused -> Triple(rec.elapsedNs, rec.bytes, 0.0)
        else -> Triple(0L, 0L, 0.0)
    }
    val sec = ns / 1_000_000_000L
    val free = vm.media.freeBytes()
    val rate = if (sec > 2) bytes / sec else 0L
    val remainMin = if (rate > 0 && free > 0) free / rate / 60 else null
    Row(modifier.background(V.Scrim, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(if (rec is RecState.Paused) V.Ash else V.Tally))
        Spacer(Modifier.width(8.dp))
        Text("%02d:%02d:%02d".format(sec / 3600, (sec / 60) % 60, sec % 60), style = VType.label)
        if (rec is RecState.Finalizing) Text("  saving", style = VType.small.copy(color = V.Ash))
        remainMin?.let { Text("  ${it} min left", style = VType.micro.copy(color = V.Ash)) }
        Spacer(Modifier.width(8.dp))
        // Audio level: amplitude is 0..1 from CameraX audio stats.
        Box(Modifier.size(width = 36.dp, height = 4.dp).background(V.Line)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(amp.toFloat().coerceIn(0f, 1f)).background(if (amp > 0.9) V.Tally else V.Ok))
        }
    }
}

@Composable
private fun Readout(m: com.daya.velum.camera.LiveMeta, s: CameraSettings, modifier: Modifier) {
    val parts = buildList {
        m.iso?.let { add("ISO $it") }
        m.exposureNs?.let { add(ExposureFormat.shutter(it)) }
        m.aperture?.let { add("f/%.1f".format(it)) }
        m.focusDiopters?.let { add(ExposureFormat.diopterToDistance(it)) }
        if (s.mode.isVideo) m.fps?.let { add("%.0f fps".format(it)) }
        m.activePhysicalId?.let { add("sensor $it") }
        if (m.physicalSources.isNotEmpty()) add("stream ${m.physicalSources.joinToString("+")}")
    }
    Text(parts.joinToString("   "), style = VType.micro, modifier = modifier.background(V.Scrim, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp))
}

@Composable
private fun ShutterBlink(stamp: Long) {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(stamp) { if (stamp != 0L) { alpha.snapTo(0.55f); alpha.animateTo(0f, tween(220)) } }
    if (alpha.value > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = alpha.value)))
}


@Composable
private fun MovieReticle(mu: com.daya.velum.camera.MovieUi) {
    val p = mu.focusView ?: return
    val col = when { mu.lost -> V.Ash; mu.tracking -> V.Amber; else -> V.Bone }
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
        val c = Offset(p.first, p.second)
        val h = 44.dp.toPx(); val l = 12.dp.toPx(); val sw = 2.dp.toPx()
        for ((sx, sy) in listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f)) {
            val corner = Offset(c.x + sx * h, c.y + sy * h)
            drawLine(col, corner, Offset(corner.x - sx * l, corner.y), sw)
            drawLine(col, corner, Offset(corner.x, corner.y - sy * l), sw)
        }
    }
}

/** Movie mode: depth blur, focus tracking, smart framing and auto zoom. */
@Composable
private fun MovieStrip(vm: CameraViewModel, s: CameraSettings) {
    val mu by vm.movie.ui.collectAsState()
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Blur", style = VType.small.copy(color = V.Ash), modifier = Modifier.width(44.dp))
            androidx.compose.material3.Slider(s.movie.blur, { v -> vm.update { it.copy(movie = it.movie.copy(blur = v)) } }, Modifier.weight(1f),
                colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = V.Amber, activeTrackColor = V.Amber, inactiveTrackColor = V.Line))
            Text(if (s.movie.blur < 0.01f) "Off" else "f/" + "%.1f".format(16f - s.movie.blur * 14.6f), style = VType.label, modifier = Modifier.width(52.dp))
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            ToggleChip("Smart framing", s.movie.smartFraming, { vm.update { it.copy(movie = it.movie.copy(smartFraming = !it.movie.smartFraming)) } })
            ToggleChip("Auto focus", mu.autoFocus, { vm.movie.autoFocus() })
            if (mu.tracking) ToggleChip("Stop tracking", false, { vm.movie.clearTracking() })
            for ((a, b) in listOf(1f to 2f, 1f to 3f, 3f to 1f, 1f to 5f)) {
                val on = s.movie.zoomFrom == a && s.movie.zoomTo == b
                ToggleChip("${a.toInt()}×→${b.toInt()}×", on, { vm.update { it.copy(movie = it.movie.copy(zoomFrom = a, zoomTo = b)) } })
            }
            for (sec in listOf(2, 4, 8)) ToggleChip("${sec}s", s.movie.zoomSeconds == sec, { vm.update { it.copy(movie = it.movie.copy(zoomSeconds = sec)) } })
            ToggleChip(if (mu.zooming) "Zooming" else "Auto zoom", mu.zooming, { if (mu.zooming) vm.movie.cancelZoom() else vm.autoZoom() })
        }
        Text(
            when {
                mu.tracking -> if (mu.lost) "Subject lost: double-tap it again." else "Tracking. Focus and framing follow the subject."
                mu.autoFocus -> when (mu.autoTarget) {
                    "person" -> "Auto focus on the person; the background is blurred. Tap anything to focus there instead."
                    "subject" -> "Auto focus on the nearest subject in the centre. Tap to choose, double-tap to track."
                    else -> "Finding the subject..."
                }
                else -> "Manual focus. Tap to pull focus, double-tap to track, or Auto focus."
            },
            style = VType.micro.copy(color = V.Ash),
        )
    }
}

/** Movie effects: one effect at a time, each with its one meaningful control. */
@Composable
private fun EffectsStrip(vm: CameraViewModel, s: CameraSettings, recording: Boolean) {
    val fx = s.effects
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (e in com.daya.velum.settings.MovieEffect.entries) ToggleChip(e.label, fx.effect == e, {
                if (!recording) vm.update { it.copy(effects = it.effects.copy(effect = e)) } else vm.notify("Stop recording to change effect.")
            })
        }
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            when (fx.effect) {
                com.daya.velum.settings.MovieEffect.SLOW_SHUTTER -> {
                    Text("Trail", style = VType.small.copy(color = V.Ash), modifier = Modifier.width(44.dp))
                    androidx.compose.material3.Slider(fx.trail, { v -> vm.update { it.copy(effects = it.effects.copy(trail = v)) } }, Modifier.weight(1f),
                        colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = V.Amber, activeTrackColor = V.Amber, inactiveTrackColor = V.Line))
                }
                com.daya.velum.settings.MovieEffect.TIME_FREEZE -> {
                    var f by remember { mutableStateOf(vm.isFrozen) }
                    ToggleChip(if (f) "Unfreeze" else "Freeze", f, { vm.toggleFreeze(); f = vm.isFrozen })
                }
                com.daya.velum.settings.MovieEffect.TIMELAPSE, com.daya.velum.settings.MovieEffect.NIGHT_TIMELAPSE -> {
                    val speeds = if (fx.effect == com.daya.velum.settings.MovieEffect.TIMELAPSE) listOf(5, 10, 30, 60) else listOf(10, 15, 30, 60)
                    for (sp in speeds) ToggleChip("${sp}×", fx.speed == sp, { if (!recording) vm.update { it.copy(effects = it.effects.copy(speed = sp)) } })
                }
                else -> {}
            }
        }
        Text(fx.effect.description, style = VType.micro.copy(color = V.Ash))
    }
}


/** Long exposure: six named recipes and a duration. */
@Composable
private fun LongExposureStrip(vm: CameraViewModel, s: CameraSettings) {
    val m = s.longExposure
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (e in com.daya.velum.imaging.LongExposureMode.entries) ToggleChip(e.label, m == e, { vm.update { it.copy(longExposure = e, leSeconds = 0) } })
        }
        Row(Modifier.padding(top = 4.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val cur = if (s.leSeconds > 0) s.leSeconds else m.seconds
            for (sec in listOf(2, 4, 8, 15, 30, 60)) ToggleChip("${sec}s", cur == sec, { vm.update { it.copy(leSeconds = sec) } })
        }
        Text(m.tip, style = VType.micro.copy(color = V.Ash))
    }
}


private val stripIn = fadeIn(tween(180)) + expandVertically(tween(220))
private val stripOut = fadeOut(tween(140)) + shrinkVertically(tween(200))
private val PHOTO_STRIP_MODES = setOf(CaptureMode.PORTRAIT, CaptureMode.MACRO, CaptureMode.ASTRO, CaptureMode.BEST_SHOT, CaptureMode.CLONE)

/** Options and a one-line how-to for Portrait, Macro, Astro, Best shot and Clone. */
@Composable
private fun PhotoModeStrip(vm: CameraViewModel, s: CameraSettings) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
        when (s.mode) {
            CaptureMode.PORTRAIT -> if (s.zoom >= com.daya.velum.camera.PORTRAIT_TELE_ZOOM) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((label, v) in listOf("Light blur" to 0.35f, "Medium" to 0.6f, "Strong" to 0.95f))
                    ToggleChip(label, kotlin.math.abs(s.portraitBlur - v) < 0.05f, { vm.update { it.copy(portraitBlur = v) } })
            }
            CaptureMode.ASTRO -> Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (sec in listOf(30, 60, 120, 240)) ToggleChip(if (sec < 60) "${sec}s" else "${sec / 60} min", s.astroSeconds == sec, { vm.update { it.copy(astroSeconds = sec) } })
            }
            CaptureMode.BEST_SHOT -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ToggleChip("Keep best 1", s.bestKeep == 1, { vm.update { it.copy(bestKeep = 1) } })
                ToggleChip("Keep best 3", s.bestKeep == 3, { vm.update { it.copy(bestKeep = 3) } })
            }
            CaptureMode.CLONE -> {
                val cl by vm.clone.collectAsState()
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ToggleChip("First shot on the left", s.cloneFirstLeft, { if (cl.first == null) vm.update { it.copy(cloneFirstLeft = true) } })
                    ToggleChip("On the right", !s.cloneFirstLeft, { if (cl.first == null) vm.update { it.copy(cloneFirstLeft = false) } })
                    if (cl.first != null) ToggleChip("Start over", false, { vm.cloneReset() })
                }
            }
            else -> {}
        }
        val tip = when (s.mode) {
            CaptureMode.PORTRAIT -> if (s.zoom >= com.daya.velum.camera.PORTRAIT_TELE_ZOOM)
                "Telephoto portrait: the photo is saved, then a copy with background blur from depth and person detection."
            else "1x uses the camera's own portrait processing. Pick 3x or 5x for a tighter, telephoto portrait."
            CaptureMode.MACRO -> "Get 3 to 10 cm from the subject. Tap to focus. Uses the ultra-wide lens, which focuses closest."
            CaptureMode.ASTRO -> "Tripod or a steady surface, clear dark sky. Frames are aligned and stacked; the result is brightened without clipping stars."
            CaptureMode.BEST_SHOT -> "One press shoots about 1.6 s of full-resolution frames and keeps the sharpest (least shake, best focus)."
            CaptureMode.CLONE -> if (vm.clone.collectAsState().value.first == null)
                "Stand on one half of the frame and shoot. Keep the phone still between shots."
            else "Now stand on the other half (the first shot shows faintly) and shoot again."
            else -> ""
        }
        Text(tip, style = VType.micro.copy(color = V.Ash))
    }
}

/** Faces from the camera's detector, mapped from sensor coordinates to the viewfinder. */
@Composable
private fun FaceBoxes(faces: List<android.graphics.Rect>, view: androidx.camera.view.PreviewView) {
    val m = view.sensorToViewTransform ?: return
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
        for (f in faces) {
            val r = android.graphics.RectF(f)
            m.mapRect(r)
            val sw = 1.5.dp.toPx(); val l = 10.dp.toPx()
            val corners = listOf(Offset(r.left, r.top) to Offset(1f, 1f), Offset(r.right, r.top) to Offset(-1f, 1f),
                Offset(r.left, r.bottom) to Offset(1f, -1f), Offset(r.right, r.bottom) to Offset(-1f, -1f))
            for ((c, d) in corners) {
                drawLine(V.Bone, c, Offset(c.x + d.x * l, c.y), sw)
                drawLine(V.Bone, c, Offset(c.x, c.y + d.y * l), sw)
            }
        }
    }
}


/** Vlog: pick a template, shoot each directed shot, then finish (optionally with your own music). */
@Composable
private fun VlogStrip(vm: CameraViewModel) {
    val v by vm.vlog.collectAsState()
    val t = com.daya.velum.video.VlogTemplates.byId(v.templateId) ?: return
    val music = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        vm.vlogFinish(uri)
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (tp in com.daya.velum.video.VlogTemplates.all) ToggleChip(tp.name, tp.id == v.templateId, { vm.vlogTemplate(tp.id) }, !v.shooting && !v.stitching)
        }
        Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            t.shots.forEachIndexed { i, _ ->
                Box(Modifier.padding(end = 4.dp).size(width = 26.dp, height = 4.dp).background(when {
                    i < v.shot -> V.Amber; i == v.shot && v.shooting -> V.Tally; else -> V.Line
                }))
            }
            Spacer(Modifier.weight(1f))
            if (v.parts.isNotEmpty() && !v.shooting) ToggleChip("Redo last", false, { vm.vlogRedo() })
        }
        val done = v.shot >= t.shots.size
        Text(
            when {
                v.stitching -> "Joining your shots\u2026"
                done -> "All ${t.shots.size} shots ready."
                else -> "Shot ${v.shot + 1} of ${t.shots.size} \u00b7 ${"%.1f".format(t.shots[v.shot].seconds)} s \u00b7 ${t.shots[v.shot].move.label}: ${t.shots[v.shot].prompt}"
            }, style = VType.small.copy(color = if (done) V.Amber else V.Bone),
        )
        if (v.parts.isNotEmpty() && !v.shooting && !v.stitching) Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ToggleChip("Finish", true, { vm.vlogFinish(null) })
            ToggleChip("Finish with music", false, { music.launch(arrayOf("audio/mp4", "audio/aac", "audio/x-m4a", "audio/*")) })
        }
    }
}


/** More modes: a grid of the specialised modes, each with what it actually does. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun MoreSheet(current: CaptureMode, dualOk: Boolean, onPick: (CaptureMode) -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, containerColor = V.Graphite, contentColor = V.Bone, scrimColor = V.Scrim) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("More modes", style = VType.title, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
            for (row in com.daya.velum.settings.MORE_MODES.chunked(2)) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { for ((m, desc) in row) {
                val enabled = m != CaptureMode.DUAL || dualOk
                val sel = m == current
                Column(
                    Modifier.weight(1f).height(118.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (sel) V.GraphiteHi else V.Black.copy(alpha = 0.25f))
                        .clickable(enabled = enabled) { onPick(m) }.padding(14.dp),
                ) {
                    Text(m.label, style = VType.label.copy(color = when { !enabled -> V.Dim; sel -> V.Amber; else -> V.Bone }))
                    Text(if (enabled) desc else "Not available: this phone reports no back+front concurrent pair.", style = VType.small.copy(color = V.Ash))
                }
            } }
        }
    }
}


/** Slow motion viewfinder: a TextureView fed by the Camera2 high-speed session (CameraX is parked meanwhile). */
@Composable
private fun SlowMoViewfinder(vm: CameraViewModel, fps: Int) {
    key(fps) {
        AndroidView(factory = { ctx ->
            android.view.TextureView(ctx).apply {
                surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                        vm.slowMo.open("0", fps, st, vm.deviceRotationDegrees)
                    }
                    override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) {}
                    override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean { vm.slowMo.close(); return true }
                    override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
                }
            }
        }, modifier = Modifier.fillMaxSize())
    }
}
