package com.daya.velum

import android.app.Application
import com.daya.velum.camera.CameraEngine
import com.daya.velum.capability.DefaultCameraCapabilityRepository
import com.daya.velum.imaging.LookRepository
import com.daya.velum.imaging.PhotoGrader
import com.daya.velum.media.MediaRepository
import com.daya.velum.presets.PresetStore
import com.daya.velum.settings.DataStoreSettingsRepository

/** Manual DI: one instance of each long-lived component for the process. */
class VelumApp : Application() {
    val caps by lazy { DefaultCameraCapabilityRepository(this) }
    val media by lazy { MediaRepository(this) }
    val settingsRepo by lazy { DataStoreSettingsRepository(this) }
    val looks by lazy { LookRepository(this) }
    val presets by lazy { PresetStore(this) }
    val grader by lazy { PhotoGrader(this, media) }
    val engine by lazy { CameraEngine(this, caps, media) }
    val slowMo by lazy { com.daya.velum.video.SlowMotionEngine(this, media) }
}
