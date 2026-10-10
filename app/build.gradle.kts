import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
}

// Release version. Bump versionName and versionCode together when cutting a
// release, and tag the release commit as "v$versionName" — the tag, these
// literals, and the GitHub release asset names ("Ferngeist-$versionName.apk")
// must all agree. F-Droid's checkupdates parses these literal values, so
// they MUST NOT be computed (e.g. via git describe).
val appVersionName = "1.1.0"
val appVersionCode = 1605

val releaseKeystorePath = System.getenv("ANDROID_KEYSTORE_PATH")?.trim()?.replaceFirst(Regex("[\\\\/]+$"), "")
val releaseKeystorePassword: String? = System.getenv("ANDROID_KEYSTORE_PASSWORD")
val releaseKeyAlias: String? = System.getenv("ANDROID_KEY_ALIAS")
val releaseKeyPassword: String? = System.getenv("ANDROID_KEY_PASSWORD")
val hasReleaseSigning =
    !releaseKeystorePath.isNullOrBlank() &&
        !releaseKeystorePassword.isNullOrBlank() &&
        !releaseKeyAlias.isNullOrBlank() &&
        !releaseKeyPassword.isNullOrBlank()

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.fromTarget("17")
        allWarningsAsErrors = true
    }
}

android {
    namespace = "com.tamimarafat.ferngeist"
    compileSdk = 37

    flavorDimensions += "distribution"
    productFlavors {
        create("google") {
            dimension = "distribution"
        }
        create("foss") {
            dimension = "distribution"
        }
    }

    defaultConfig {
        applicationId = "com.tamimarafat.ferngeist"
        minSdk = 30
        targetSdk = 37
        versionCode = 1604
        versionName = "1.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    androidResources {
        localeFilters += setOf("en", "es", "pt", "bn", "ru", "zh")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword!!
                keyAlias = releaseKeyAlias!!
                keyPassword = releaseKeyPassword!!
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    lint {
        lintConfig = file("lint.xml")
    }
    testOptions {
        unitTests {
            // Let stubbed Android framework calls (e.g. android.util.Log) return
            // defaults instead of throwing in JVM unit tests.
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.data.database)
    implementation(projects.acpBridge)
    implementation(projects.feature.serverlist)
    implementation(projects.feature.sessionlist)
    implementation(projects.feature.chat)
    implementation(projects.gatewayClient)

    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.compose.material3.adaptive)
    implementation(libs.androidx.compose.material3.adaptive.layout)
    implementation(libs.androidx.compose.material3.adaptive.navigation)
    implementation(libs.androidx.window)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.android)

    implementation(libs.androidx.datastore.preferences)
    ksp(libs.hilt.compiler)
    // Push notifications over UnifiedPush (Web Push from each gateway). The google flavour
    // also embeds the FCM distributor, so a phone without a UnifiedPush app (e.g. ntfy) still
    // gets pushes through Play Services; the foss flavour needs such an app.
    implementation(libs.unifiedpush.connector)
    "googleImplementation"(libs.unifiedpush.embedded.fcm.distributor)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
