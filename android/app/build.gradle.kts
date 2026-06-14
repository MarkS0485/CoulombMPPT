import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
}

android {
    namespace   = "app.coulombmppt"
    compileSdk  = 35

    defaultConfig {
        applicationId = "app.coulombmppt"
        minSdk        = 31              // Android 12 — required for new BLE permission model
        targetSdk     = 35
        versionCode   = 1
        versionName   = "0.1.0"
    }

    buildTypes {
        release {
            // Unsigned release build — no signing config is wired up.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
        }
    }

    // Redirect Gradle output away from C: on the dev box (insufficient disk on C:).
    // Guarded on the D: drive actually existing, so CI and any machine without it
    // fall back to the default build dir instead of leaking "D:/" into the Kotlin
    // compile classpath (which trips allWarningsAsErrors).
    if (file("D:/").exists()) {
        layout.buildDirectory.set(file("D:/builds/CoulombMPPT"))
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        warningsAsErrors = true
        abortOnError     = true
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/INDEX.LIST")
        }
    }

    // Sources live under app/src/main/kotlin to match coulombmonitor.
    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        allWarningsAsErrors = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.vico.compose.m3)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Self-contained QR scanner for PC pairing (no Google Play Services dep).
    implementation(libs.zxing.android.embedded)
}
