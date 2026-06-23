plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.tamimarafat.ferngeist.acp.bridge"
    compileSdk = 37

    defaultConfig {
        minSdk = 30
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget =
            org.jetbrains.kotlin.gradle.dsl.JvmTarget
                .fromTarget("17")
    }
}

dependencies {
    // === ACP Kotlin SDK ===
    api(libs.acp)
    api(libs.acp.model)
    implementation(libs.acp.ktor)

    // === Ktor ===
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websockets)

    // === Coroutines ===
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // === JSON ===
    implementation(libs.kotlinx.serialization.json)

    // === Crypto (Paseo relay E2EE: NaCl box / Curve25519 + XSalsa20-Poly1305) ===
    implementation("com.goterl:lazysodium-android:5.1.0@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")

    // === Internal ===
    implementation(project(":core:model"))
    implementation(project(":gateway-client"))

    // === Testing ===
    testImplementation(libs.turbine)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
