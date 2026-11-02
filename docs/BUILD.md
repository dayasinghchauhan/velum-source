# Building Velum

Requirements: Android SDK at `D:/Projects/android-sdk` (platform android-37.0, build-tools 36.1.0), JDK 21 (Android
Studio's JBR), Windows or any OS with Gradle 9.

```
# local.properties (not committed)
sdk.dir=D\:/Projects/android-sdk

# debug build, unit tests, install
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew.bat --no-daemon assembleDebug testDebugUnitTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Release: `./gradlew.bat assembleRelease` produces an **unminified** APK (minification is off on purpose: R8 stripped
classes loaded by reflection and the minified build crashed at startup). It is signed with the release key when
`local.properties` has `signing.properties=<path to keystore.properties>` (keys: `storeFile`, `storePassword`,
`keyAlias`, `keyPassword`); otherwise it comes out unsigned (`app-release-unsigned.apk`). The keystore and its
properties live outside the repo and must never be committed (`*.keystore`, `*.jks` and `keystore.properties` are
git-ignored). Every published update must be signed with the same key, or Android refuses to install it over the
previous version. Check a build with `apksigner verify --print-certs app-release.apk`.

The APK is arm64-v8a only (the phone) and about 117 MB, mostly the MiDaS depth model (66 MB) and the five bundled
slay LUTs (5 MB).
