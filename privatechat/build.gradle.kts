plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jlleitschuh.gradle.ktlint")
}

fun String.asBuildConfigString(): String = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

fun resolvePrivateChatVersionCode(rawVersionCode: String?): Int {
    if (rawVersionCode == null) return 2031
    val parsedVersionCode = rawVersionCode.toIntOrNull()
    require(parsedVersionCode != null && parsedVersionCode in 1..2_100_000_000) {
        "SYNAPSE_VERSION_CODE must be a positive supported Android version code"
    }
    return parsedVersionCode
}

val privateChatVersionCode =
    resolvePrivateChatVersionCode(
        providers.environmentVariable("SYNAPSE_VERSION_CODE").orNull,
    )
val privateChatVersionName =
    providers.environmentVariable("SYNAPSE_VERSION_NAME").orNull
        ?: "0.1.$privateChatVersionCode"
val privateChatSupabaseProjectUrl =
    providers.environmentVariable("SYNAPSE_SUPABASE_URL").orNull
        ?: "https://xqifnldqcsgefeisscgu.supabase.co"
val privateChatSupabasePublishableKey =
    providers.environmentVariable("SYNAPSE_SUPABASE_PUBLISHABLE_KEY").orNull
        ?: "sb_publishable_fLS2Qi8Dp_rG6EDMDhIoRg_R9Ky8vUk"

require(Regex("^https://[a-z0-9]+[.]supabase[.]co$").matches(privateChatSupabaseProjectUrl)) {
    "SYNAPSE_SUPABASE_URL must be a canonical HTTPS Supabase project URL"
}
require(Regex("^sb_publishable_[A-Za-z0-9_-]{20,}$").matches(privateChatSupabasePublishableKey)) {
    "SYNAPSE_SUPABASE_PUBLISHABLE_KEY must be a Supabase publishable key"
}

android {
    namespace = "app.synapse.privatechat"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.synapse.privatechat"
        minSdk = 28
        targetSdk = 36
        versionCode = privateChatVersionCode
        versionName = privateChatVersionName

        buildConfigField(
            "String",
            "SUPABASE_PROJECT_URL",
            privateChatSupabaseProjectUrl.asBuildConfigString(),
        )
        buildConfigField(
            "String",
            "SUPABASE_PUBLISHABLE_KEY",
            privateChatSupabasePublishableKey.asBuildConfigString(),
        )

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
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
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
        debug {
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
        }
        create("rolling") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            excludes += setOf("**/libsignal_jni_testing.so")
        }
        resources {
            excludes +=
                setOf(
                    "/libsignal_jni_aarch64.dylib",
                    "/libsignal_jni_amd64.dylib",
                    "/libsignal_jni_amd64.so",
                    "/libsignal_jni_testing_aarch64.dylib",
                    "/libsignal_jni_testing_amd64.dylib",
                    "/libsignal_jni_testing_amd64.so",
                    "/signal_jni_amd64.dll",
                    "/signal_jni_testing_amd64.dll",
                )
        }
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            allWarningsAsErrors.set(true)
        }
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
        disable +=
            setOf(
                "AndroidGradlePluginVersion",
                "GradleDependency",
                "NewerVersionAvailable",
                // Exception scope: API 37 is visible to lint but unavailable from the public SDK feed.
                // Owner: Synapse Private. Removal: delete once platforms;android-37 installs in CI.
                "OldTargetApi",
            )
    }
}

ktlint {
    android.set(true)
    ignoreFailures.set(false)
    additionalEditorconfig.put(
        "ktlint_function_naming_ignore_when_annotated_with",
        "Composable",
    )
    filter {
        exclude("**/build/**")
    }
}

val privateChatKotlinSources =
    fileTree("src") {
        include("**/*.kt")
    }

// AGP 9's built-in Kotlin support does not publish Android source sets to this ktlint plugin yet.
// Extend the configured script tasks so the normal ktlint lifecycle covers module sources too.
tasks.named<org.jlleitschuh.gradle.ktlint.tasks.KtLintCheckTask>("runKtlintCheckOverKotlinScripts") {
    source(privateChatKotlinSources)
}
tasks.named<org.jlleitschuh.gradle.ktlint.tasks.KtLintFormatTask>("runKtlintFormatOverKotlinScripts") {
    source(privateChatKotlinSources)
}

val privateChatRuntimeCoordinates =
    providers.provider {
        configurations
            .getByName("debugRuntimeClasspath")
            .incoming
            .resolutionResult
            .allComponents
            .map { component -> component.id.displayName }
            .sorted()
    }

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    dependsOn("assembleDebug")
    doFirst {
        systemProperty("privatechat.moduleDirectory", projectDir.absolutePath)
        systemProperty(
            "privatechat.runtimeDependencies",
            privateChatRuntimeCoordinates.get().joinToString(separator = "\n"),
        )
        systemProperty(
            "privatechat.debugApk",
            layout.buildDirectory
                .file("outputs/apk/debug/privatechat-debug.apk")
                .get()
                .asFile.absolutePath,
        )
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.02.00")
    implementation(composeBom)

    implementation("androidx.activity:activity-compose:1.12.2")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.emoji2:emoji2-emojipicker:1.6.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
    implementation("org.signal:libsignal-android:0.101.0")
    implementation("org.signal:libsignal-client:0.101.0")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
