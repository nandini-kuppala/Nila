plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.nila"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nila"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "1.0.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    }

    /**
     * One APK per architecture rather than one fat one.
     *
     * arm64-v8a is every real phone. x86_64 exists so the app can be handed to
     * someone who has only a browser -- Appetize, BrowserStack and a desktop
     * Android Studio emulator on an Intel machine all want it, and a judge who
     * cannot install the app cannot judge it.
     *
     * Split rather than universal because the native libraries are most of the
     * download: one fat APK is 212 MB, while each split is far smaller and
     * nobody fetches the half they cannot run.
     *
     * This replaces the old `ndk.abiFilters`, which cannot coexist with an ABI
     * split -- AGP refuses the build rather than picking one. armeabi-v7a is
     * excluded either way: a third copy of the MediaPipe, ONNX and ML Kit
     * native libraries, for handsets from 2015.
     */
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }

    signingConfigs {
        // Only created when a key is actually supplied, so a local build keeps
        // using AGP's debug config and needs no setup at all.
        if (System.getenv("NILA_KEYSTORE_FILE") != null) {
            create("release") { applyNilaReleaseKey(project) }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"),
                          "proguard-rules.pro")
            // Signed with the debug key so `assembleRelease` produces something
            // installable for a demo without a keystore ceremony -- but from a
            // *named* file when one is given, because the implicit location is
            // not the same on a CI runner. See applyNilaReleaseKey.
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }

    // TFLite models are already compressed; letting aapt deflate them again
    // means the runtime cannot mmap them and has to copy to the heap first.
    androidResources { noCompress += listOf("tflite", "task", "bin", "litertlm", "onnx") }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/DEPENDENCY_INFO",
        )
    }

    testOptions { unitTests { isReturnDefaultValues = true } }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

/**
 * Unit tests exercise the real shipped corpus, not a copy that can drift from
 * it. Copying at build time means a change to knowledge.json is tested by the
 * next `gradlew test` without anyone remembering to sync a fixture.
 */
val syncKnowledgeFixture by tasks.registering(Copy::class) {
    from("src/main/assets/knowledge.json")
    into("src/test/resources")
}
// Matched lazily: AGP registers these per-variant tasks after this block runs,
// so tasks.named() would not find them yet.
tasks.matching { it.name.matches(Regex("process.*UnitTestJavaRes")) }
    .configureEach { dependsOn(syncKnowledgeFixture) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    implementation(libs.tensorflow.lite)
    implementation(libs.tensorflow.lite.gpu)
    implementation(libs.tensorflow.lite.gpu.api)

    implementation(libs.mediapipe.vision)
    implementation(libs.mediapipe.genai)
    implementation(libs.play.services.wearable)
    implementation(libs.mlkit.text.recognition)
    implementation(libs.mlkit.text.devanagari)
    implementation(libs.onnxruntime.android)
    implementation(libs.litertlm)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
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
