import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.tamimarafat.ferngeist.feature.chat"
    compileSdk = 37

    defaultConfig {
        minSdk = 30
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        multiDexEnabled = true
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.fromTarget("17")
        allWarningsAsErrors = true
    }
}

dependencies {
    // === Core ===
    implementation(projects.core.model)
    implementation(projects.core.common)
    // ACP model types surface in the chat domain itself (`ToolCallContent.Diff` for git
    // diffs, `ToolKind`/`ToolCallStatus` for tool rendering), so the SDK model module is a
    // direct dependency rather than one inherited from the ACP bridge.
    implementation(libs.acp.model)

    // === Android ===
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    debugImplementation(libs.androidx.ui.tooling)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.compose.material3.adaptive)
    implementation(libs.androidx.compose.material3.adaptive.layout)
    implementation(libs.androidx.compose.material3.adaptive.navigation)
    implementation(libs.androidx.window)
    implementation(libs.androidx.material.icons.extended)
    // Incremental markdown: stable block identity across appends is what lets settled blocks
    // stay mounted while only the trailing one animates. Supersedes multiplatform-markdown-renderer.
    implementation(libs.compose.markdown.core)
    implementation(libs.compose.markdown.renderer)

    // Syntax highlighting: pure-Kotlin tokenizer, no WebView/native/JS runtime. Only the
    // tokenizer artifact — the Compose helper would drag in JetBrains Compose for a theme
    // we resolve from the app palette ourselves.
    implementation(libs.syntaxmp.tokenizer)

    // Diff
    implementation(libs.diff.kotlin)

    // Commit author avatars; core/common supplies the network fetcher.
    implementation(libs.coil.compose)

    // Hilt Setup
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.kotlinx.serialization.json)

    // Unit tests
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    // Test doubles build the real hub / gateway repositories; production code reaches both
    // only through the core-model ports.
    testImplementation(projects.acpBridge)
    testImplementation(projects.gatewayClient)
}
