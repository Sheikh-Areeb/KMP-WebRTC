import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.cocoapods)        // JetBrains CocoaPods integration
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.android.application)
}

kotlin {
    // Silence the "expect/actual classes are in Beta" warning for every
    // target — the feature is stable enough for KMP UI code and the warning
    // floods every build.
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    // iOS targets — declared here; framework config moves into cocoapods {}
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    // ── CocoaPods plugin ───────────────────────────────────────────────────
    // This plugin does three things:
    //  1. Generates a .podspec so the KMP framework can be consumed as a Pod
    //  2. Integrates each pod() entry as a cinterop dependency in iosMain
    //  3. Manages `pod install` via ./gradlew :composeApp:podInstall
    cocoapods {
        summary  = "WebRTC KMP — peer-to-peer calling demo"
        homepage = "https://github.com/areeb/webrtc-kmp"
        version  = "1.0"
        ios.deploymentTarget = "14.0"
        podfile = project.file("../iosApp/Podfile")

        // StreamWebRTC — Stream's actively-maintained WebRTC iOS binary
        // (mirrors stream-webrtc-android on the Android side). Ships as an
        // xcframework with proper arm64-simulator / arm64-device / x86_64
        // slices, so we no longer need EXCLUDED_ARCHS=arm64 for simulators.
        // The framework module name is still `WebRTC` (intentional drop-in
        // for the old GoogleWebRTC pod at the ObjC level).
        pod("StreamWebRTC") {
            version = "~> 137.0"
            moduleName = "WebRTC"
            packageName = "cocoapods.StreamWebRTC"
            // -fmodules lets the compiler find the WebRTC umbrella module
            extraOpts += listOf("-compiler-option", "-fmodules")
        }

        framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    // ── Source sets ───────────────────────────────────────────────────────
    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.components.resources)

            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
            implementation(libs.ktor.client.logging)
            implementation(libs.ktor.serialization.json)
            implementation(libs.ktor.client.content.negotiation)

            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }

        androidMain.dependencies {
            implementation(compose.preview)
            implementation(libs.androidx.core)
            implementation(libs.androidx.activity.compose)
            implementation(libs.lifecycle.viewmodel)
            implementation(libs.lifecycle.runtime)

            implementation(libs.ktor.client.okhttp)
            implementation(libs.kotlinx.coroutines.android)

            implementation(libs.webrtc.android)
        }

        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
            // cocoapods.GoogleWebRTC is automatically available — no explicit dep needed
        }
    }
}

android {
    namespace = "com.webrtckmp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.webrtckmp"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
        jniLibs {
            // Store .so files uncompressed so the OS can mmap them at page-aligned
            // offsets — required for 16KB page size compatibility (Android 15+).
            useLegacyPackaging = false
        }
    }

    buildTypes {
        getByName("release") { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
