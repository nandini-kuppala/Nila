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
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Every device this ships to is arm64. Shipping x86 and armeabi-v7a
        // copies of the MediaPipe and ML Kit native libraries triples the APK
        // for emulators and phones from 2015.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"),
                          "proguard-rules.pro")
            // Signed with the debug key so `assembleRelease` produces something
            // installable for a demo without a keystore ceremony.
            signingConfig = signingConfigs.getByName("debug")
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
