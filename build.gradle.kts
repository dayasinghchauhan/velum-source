plugins {
    alias(libs.plugins.android.application) apply false
    // Puts a newer KGP on the classpath; AGP 9 built-in Kotlin picks it up.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
