plugins {
    alias(libs.plugins.android.application)
    // No kotlin-android: AGP 9's built-in Kotlin replaces it, and applying it
    // fails against the new DSL.
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.twentygx.tgxcode"
    // 37, not 36: the current AndroidX and OkHttp 5.5.0 releases declare a minimum
    // compileSdk of 37. Independent of targetSdk, which stays at 36 — compiling
    // against newer APIs is not the same as opting in to new runtime behaviour.
    compileSdk = 37
    compileSdkMinor = 1
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.twentygx.tgxcode"
        // 30, not 26/29: IME insets are only reliably animated and measurable from
        // API 30, and the composer-over-keyboard is the fiddliest surface in this
        // app. 30 also makes java.time native, so no core library desugaring.
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        debug {
            // The debug source set overlays res/xml/network_security_config.xml to
            // permit cleartext to the dev bridge. <debug-overrides> cannot do this —
            // it configures trust anchors only.
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    testOptions {
        unitTests {
            // `android.util.Log` is a stub that throws on the JVM, so anything under
            // test that logs dies with RuntimeException("Stub!") rather than failing an
            // assertion. That went unnoticed while the tests were all pure decode
            // checks; the moment one drives the repository — which logs every
            // `/api/events` attempt — it matters.
            isReturnDefaultValues = true
        }
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

// NOT `android { kotlinOptions { } }` — that DSL does not exist in AGP 9.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    debugImplementation(libs.okhttp.logging)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.markdown.renderer.m3)
    implementation(libs.jetbrains.markdown)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.okhttp.mockwebserver)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
