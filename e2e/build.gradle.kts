import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.test)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.fromTarget("17")
        allWarningsAsErrors = true
    }
}

android {
    namespace = "com.tamimarafat.ferngeist.e2e"
    compileSdk {
        version =
            release(37) {
                minorApiLevel = 1
            }
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("google") { dimension = "distribution" }
        create("foss") { dimension = "distribution" }
    }

    defaultConfig {
        minSdk = 30
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    targetProjectPath = ":app"

    // Instrumenting the app in-process would give it a foreground-grade process priority, so
    // "backgrounded" would be a lie. A separate test process drives the real app via UiAutomator.
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

dependencies {
    implementation(libs.androidx.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.uiautomator)
}
