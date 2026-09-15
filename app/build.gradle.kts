import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
}

// Firebase is optional. The google-services plugin only runs when a real
// google-services.json is present, so the project still builds (and CI passes)
// without one — FCM simply stays inert until the file is added. See docs/fcm-setup.md.
if (file("google-services.json").exists()) {
    apply(
        plugin =
            libs.plugins.google.services
                .get()
                .pluginId,
    )
}

// Release version. Bump versionName and versionCode together when cutting a
// release, and tag the release commit as "v$versionName" — the tag, these
// literals, and the GitHub release asset names ("Ferngeist-$versionName.apk")
// must all agree. F-Droid's checkupdates parses these literal values, so
// they MUST NOT be computed (e.g. via git describe).
val appVersionName = "0.14.2"
val appVersionCode = 1402

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
        versionCode = 1402
        versionName = "0.14.2"

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
    // Firebase Cloud Messaging (push notifications). Google-only: F-Droid's FOSS
    // flavour omits this entirely, so no proprietary push code ships there.
    "googleImplementation"(platform(libs.firebase.bom))
    "googleImplementation"(libs.firebase.messaging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)

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
