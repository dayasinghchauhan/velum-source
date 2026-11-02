package com.daya.velum

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.OrientationEventListener
import android.view.Surface
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.daya.velum.ui.CameraViewModel
import com.daya.velum.ui.camera.CameraScreen
import com.daya.velum.ui.gallery.GalleryScreen
import com.daya.velum.ui.settings.DiagnosticsScreen
import com.daya.velum.ui.settings.SettingsScreen
import com.daya.velum.ui.theme.V
import com.daya.velum.ui.theme.VType
import com.daya.velum.ui.theme.VelumTheme
import kotlin.math.atan2

class MainActivity : ComponentActivity() {
    private val vm: CameraViewModel by viewModels()
    private var uiRotation by mutableFloatStateOf(0f)
    private var roll by mutableFloatStateOf(0f)
    private var screen by mutableStateOf("camera")

    private val orientation by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(deg: Int) {
                if (deg == ORIENTATION_UNKNOWN) return
                // Hysteresis: only switch when clearly inside a new quadrant.
                val q = when (deg) { in 55..125 -> 270; in 145..215 -> 180; in 235..305 -> 90; in 0..35, in 325..360 -> 0; else -> return }
                vm.deviceRotation = when (q) { 90 -> Surface.ROTATION_90; 180 -> Surface.ROTATION_180; 270 -> Surface.ROTATION_270; else -> Surface.ROTATION_0 }
                uiRotation = when (q) { 270 -> -90f; 90 -> 90f; else -> q.toFloat() }
            }
        }
    }
    private val sensors by lazy { getSystemService(SensorManager::class.java) }
    private val gravity = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val r = Math.toDegrees(atan2(e.values[0].toDouble(), e.values[1].toDouble())).toFloat()
            roll = if (kotlin.math.abs(r - roll) > 180f) r else roll * 0.8f + r * 0.2f
            vm.engine.setRoll(roll)
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { VelumTheme { Root() } }
    }

    override fun onResume() {
        super.onResume()
        orientation.enable()
        sensors.getDefaultSensor(Sensor.TYPE_GRAVITY)?.let { sensors.registerListener(gravity, it, SensorManager.SENSOR_DELAY_UI) }
    }

    override fun onPause() {
        orientation.disable()
        sensors.unregisterListener(gravity)
        super.onPause()
    }

    /**
     * Debug builds only: `am start -f 0x20000000 -n com.daya.velum/.MainActivity --ef debugRoll 10` injects a fixed
     * roll for checking horizon lock; `--ez debugRollOff true` clears it.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        if (intent.hasExtra("debugRoll")) vm.engine.debugRoll = intent.getFloatExtra("debugRoll", 0f)
        if (intent.getBooleanExtra("debugRollOff", false)) vm.engine.debugRoll = null
        // `--es debugMode VIDEO` selects a mode directly (test automation; the mode rail animates under taps).
        intent.getStringExtra("debugMode")?.let { m -> runCatching { com.daya.velum.settings.CaptureMode.valueOf(m) }.getOrNull()?.let(vm::setMode) }
        // `--es debugSet stab=OFF` style switches for repeatable tests.
        intent.getStringExtra("debugSet")?.let { kv ->
            val (k, v) = kv.split("=", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            when (k) {
                "stab" -> runCatching { com.daya.velum.settings.Stabilization.valueOf(v) }.getOrNull()?.let { st -> vm.update { it.copy(video = it.video.copy(stabilization = st)) } }
                "fps" -> v.toIntOrNull()?.let { f -> vm.update { it.copy(video = it.video.copy(fps = f)) } }
                "look" -> vm.looks.byId(v)?.let(vm::selectLook)
                "hdr" -> vm.update { it.copy(autoHdr = v == "1") }
                "detail" -> runCatching { com.daya.velum.settings.Detail.valueOf(v) }.getOrNull()?.let { d -> vm.update { it.copy(detail = d) } }
            }
        }
    }

    /** Volume keys act as the shutter, as on most cameras. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val shutterKey = keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_CAMERA
        if (shutterKey && screen == "camera" && vm.ready.value) {
            if (event.repeatCount == 0) vm.shutter()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    @Composable
    private fun Root() {
        val ctx = LocalContext.current
        fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
        var camOk by remember { mutableStateOf(granted(Manifest.permission.CAMERA)) }
        var asked by remember { mutableStateOf(false) }
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
            camOk = res[Manifest.permission.CAMERA] == true || granted(Manifest.permission.CAMERA)
            asked = true
        }
        if (!camOk) {
            Permission(asked, permanentlyDenied = asked && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA),
                onAsk = { launcher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) },
                onSettings = { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))) })
            return
        }
        when (screen) {
            "gallery" -> GalleryScreen(vm.media) { screen = "camera"; vm.refreshThumbLatest() }
            "settings" -> SettingsScreen(vm, { screen = "camera" }, { screen = "diagnostics" })
            "diagnostics" -> DiagnosticsScreen(vm) { screen = "settings" }
            else -> CameraScreen(vm, uiRotation, roll, onGallery = { screen = "gallery" }, onSettings = { screen = "settings" })
        }
    }

    @Composable
    private fun Permission(asked: Boolean, permanentlyDenied: Boolean, onAsk: () -> Unit, onSettings: () -> Unit) {
        Column(Modifier.fillMaxSize().background(V.Black).windowInsetsPadding(WindowInsets.systemBars).padding(28.dp), verticalArrangement = Arrangement.Center) {
            Text("Velum", style = VType.display)
            Spacer(Modifier.height(16.dp))
            Text("Velum needs the camera to show the viewfinder and take photos and videos.", style = VType.body)
            Spacer(Modifier.height(8.dp))
            Text("The microphone is used only for sound in videos; you can decline it and still record silent video. " +
                "Everything stays on this phone in DCIM/Velum. Velum has no internet access.", style = VType.body.copy(color = V.Ash))
            Spacer(Modifier.height(24.dp))
            if (permanentlyDenied) {
                Text("Camera access is turned off for Velum. Turn it on in Settings > Apps > Velum > Permissions.", style = VType.body.copy(color = V.Amber))
                TextButton(onSettings) { Text("Open app settings", color = V.Amber, style = VType.label) }
            } else {
                TextButton(onAsk) { Text(if (asked) "Allow camera access" else "Continue", color = V.Amber, style = VType.label) }
            }
        }
    }
}
