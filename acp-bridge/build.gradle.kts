plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.tamimarafat.ferngeist.acp.bridge"
    compileSdk = 36

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget("17")
    }
}

dependencies {
    // === ACP Kotlin SDK ===
    api(libs.acp)
    api(libs.acp.model)
    implementation(libs.acp.ktor.client)

    // === Ktor ===
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websockets)

    // === Android-specific ===
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // === JSON ===
    implementation(libs.kotlinx.serialization.json)

    // === Internal ===
    implementation(project(":core:model"))

    // === Testing ===
    testImplementation(libs.turbine)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

