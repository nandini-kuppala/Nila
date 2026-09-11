plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.nila.wear"
    compileSdk = 35

    defaultConfig {
        // Must match the phone app: the Data Layer pairs a watch app to a phone
        // app by application id and signature, and a mismatch fails silently.
        applicationId = "com.nila"
        minSdk = 30
        targetSdk = 35
        versionCode = 4
        versionName = "1.0.3"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        if (System.getenv("NILA_KEYSTORE_FILE") != null) {
            create("release") { applyNilaReleaseKey(project) }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // The watch APK shares the phone's application id, so it has to
            // share its signature too or a paired install is two different
            // apps as far as Android is concerned.
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.play.services.wearable)
    implementation(libs.androidx.wear)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.wear.compose.material)
    implementation(libs.wear.compose.foundation)
    debugImplementation(libs.compose.ui.tooling)
}

/**
 * The key the release APKs are signed with.
 *
 * `assembleRelease` is signed with the *debug* key on purpose, so a demo build
 * is installable without a keystore ceremony. That worked on one machine and
 * silently did the wrong thing in CI.
 *
 * The reason is that `signingConfigs.getByName("debug")` does not name a file,
 * it names AGP's *implicit* debug keystore location -- resolved at build time
 * through `ANDROID_USER_HOME`, then `ANDROID_SDK_HOME`, then the JVM's
 * `user.home`. On a GitHub runner that is not `$HOME/.android/debug.keystore`,
 * so a workflow that carefully restored the real key to that path built an APK
 * signed with a throwaway keystore AGP generated somewhere else -- and said
 * nothing, because generating one is normal behaviour. v1.0.3 shipped that way.
 *
 * So the path is stated instead of inferred. With `NILA_KEYSTORE_FILE` set,
 * that file is the key and a missing file fails the build; without it, nothing
 * changes for anyone building locally.
 *
 * The credentials are the Android debug-keystore constants, which are the same
 * on every machine and documented by Google. They are not a secret and pasting
 * them here costs nothing -- the keystore file is the only thing that has to be
 * carried, and it is carried in a repository secret.
 */
fun com.android.build.api.dsl.ApkSigningConfig.applyNilaReleaseKey(
    project: org.gradle.api.Project,
): Boolean {
    val path = System.getenv("NILA_KEYSTORE_FILE") ?: return false
    val file = project.file(path)
    require(file.isFile) {
        "NILA_KEYSTORE_FILE points at ${file.absolutePath}, which is not a file"
    }
    storeFile = file
    storePassword = "android"
    keyAlias = "androiddebugkey"
    keyPassword = "android"
    return true
}
