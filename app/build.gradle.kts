import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing: local.properties `signing.properties=<path>` points at a keystore.properties kept outside the repo
// (storeFile, storePassword, keyAlias, keyPassword). Without it, assembleRelease stays unsigned.
val signingProps = Properties().apply {
    val local = rootProject.file("local.properties")
    val path = if (local.exists()) Properties().apply { local.inputStream().use { load(it) } }.getProperty("signing.properties") else null
    if (path != null && file(path).exists()) file(path).inputStream().use { load(it) }
}

android {
    namespace = "com.daya.velum"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.daya.velum"
        minSdk = 31
        targetSdk = 37
        versionCode = 4
        versionName = "0.1.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Personal arm64 device; LiteRT otherwise ships native code for every ABI.
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        if (signingProps.getProperty("storeFile") != null) create("release") {
            storeFile = file(signingProps.getProperty("storeFile"))
            storePassword = signingProps.getProperty("storePassword")
            keyAlias = signingProps.getProperty("keyAlias")
            keyPassword = signingProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            // No code shrinking: size is not a concern and R8 stripped classes that libraries load by reflection
            // (WorkManager's Room database) — the minified build crashed at startup on the device.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    androidResources {
        // Models are memory-mapped by LiteRT, which needs them stored uncompressed.
        noCompress += "tflite"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.exifinterface)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.video)
    implementation(libs.camera.view)
    implementation(libs.camera.extensions)

    implementation(libs.litert)
    implementation(libs.zxing.core)
    implementation(libs.litert.gpu)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)

    testImplementation(libs.junit)
    testImplementation(libs.mockito.core)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
