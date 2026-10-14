import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
    // JVM target compiles the common UI code locally (no Android SDK needed);
    // the standard CMP app-module shape.
    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.components.resources)
        }
        androidMain.dependencies {
            implementation(compose.preview)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.datastore.preferences)
            implementation(libs.koin.core)
            implementation(libs.ktor.client.okhttp)
        }
    }
}

android {
    namespace = "io.orangerabbit.ticker"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.orangerabbit.ticker"
        minSdk = 28
        targetSdk = 36
        // CI (release.yml) passes the released tag's version in via these
        // properties so the APK matches the GitHub release. Local dev falls
        // back to these defaults.
        versionCode = (project.findProperty("tickerVersionCode") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("tickerVersionName") as String?) ?: "0.1.0"
    }

    buildFeatures {
        compose = true
    }

    signingConfigs {
        // Release signing driven by environment variables (see CI release.yml).
        // Falls back to debug signing when env vars absent (local dev).
        val keystoreFile = System.getenv("TICKER_KEYSTORE")
        if (keystoreFile != null) {
            create("release") {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("TICKER_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("TICKER_KEY_ALIAS")
                keyPassword = System.getenv("TICKER_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
