plugins {
    id("com.android.application")
    id("com.android.legacy-kapt")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jlleitschuh.gradle.ktlint")
}

fun quoteBuildConfigString(rawValue: String): String = "\"${rawValue.replace("\\", "\\\\").replace("\"", "\\\"")}\""

val synapseBuildGitSha =
    providers
        .environmentVariable("GITHUB_SHA")
        .orNull
        ?.take(12)
        ?: "local"

val synapseVersionCode =
    providers
        .environmentVariable("SYNAPSE_VERSION_CODE")
        .orNull
        ?.toIntOrNull()
        ?: providers
            .environmentVariable("GITHUB_RUN_NUMBER")
            .orNull
            ?.toIntOrNull()
            ?.let { runNumber -> 2_000 + runNumber }
        ?: 3

val synapseVersionName =
    providers
        .environmentVariable("SYNAPSE_VERSION_NAME")
        .orNull
        ?: "0.1.$synapseVersionCode"

android {
    namespace = "app.synapse.localllm"
    compileSdk = 36
    ndkVersion = "29.0.13113456"

    defaultConfig {
        applicationId = "app.synapse.localllm"
        minSdk = 31
        targetSdk = 36
        versionCode = synapseVersionCode
        versionName = synapseVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "SYNAPSE_BUILD_GIT_SHA", quoteBuildConfigString(synapseBuildGitSha))
        buildConfigField("String", "SYNAPSE_APK_CHANNEL", quoteBuildConfigString("apk-latest"))

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                arguments += "-DCMAKE_BUILD_TYPE=Release"
                arguments += "-DBUILD_SHARED_LIBS=ON"
                arguments += "-DLLAMA_BUILD_APP=OFF"
                arguments += "-DLLAMA_BUILD_COMMON=ON"
                arguments += "-DLLAMA_OPENSSL=OFF"
                arguments += "-DGGML_NATIVE=OFF"
                arguments += "-DGGML_BACKEND_DL=ON"
                arguments += "-DGGML_CPU_ALL_VARIANTS=ON"
                arguments += "-DGGML_LLAMAFILE=OFF"
            }
        }
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            allWarningsAsErrors.set(true)
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes +=
                setOf(
                    "/META-INF/{AL2.0,LGPL2.1}",
                    "/META-INF/*.version",
                    "DebugProbesKt.bin",
                    "kotlin-tooling-metadata.json",
                )
        }
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
        disable +=
            setOf(
                "AndroidGradlePluginVersion",
                // Exception scope: Synapse phone APK builds only for the primary ARM64 Android target.
                // Reason: bundling x86_64 llama.cpp doubles native build/output cost for no target device.
                // Owner: Synapse Local LLM app. Removal: add x86_64 when ChromeOS/emulator distribution is required.
                "ChromeOsAbiSupport",
                "GradleDependency",
                "KaptUsageInsteadOfKsp",
                "NewerVersionAvailable",
                // Exception scope: CI/debug APK builds while Android API 37 is visible to lint
                // but unavailable from the public command-line SDK package feed.
                // Owner: Synapse Local LLM app. Removal: delete once platforms;android-37 installs in CI.
                "OldTargetApi",
            )
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kapt {
    correctErrorTypes = true
}

val publishDebugSynapseApk by tasks.registering(Copy::class) {
    dependsOn("packageDebug")
    from(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
    into(layout.buildDirectory.dir("outputs/apk/synapse"))
    rename { "Synapse-AI.apk" }
}

afterEvaluate {
    tasks.named("assembleDebug") {
        finalizedBy(publishDebugSynapseApk)
    }
}

ktlint {
    android.set(true)
    ignoreFailures.set(false)
    filter {
        exclude("**/build/**")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.02.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.12.2")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.datastore:datastore-preferences:1.2.1")

    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.room:room-ktx:2.8.4")
    implementation("androidx.room:room-runtime:2.8.4")
    kapt("androidx.room:room-compiler:2.8.4")

    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.3.2")
    testImplementation("androidx.room:room-testing:2.8.4")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("io.mockk:mockk:1.14.6")
    testImplementation("org.robolectric:robolectric:4.16.1")
}
