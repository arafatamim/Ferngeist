import dev.detekt.gradle.Detekt
import dev.detekt.gradle.DetektCreateBaselineTask
import org.jlleitschuh.gradle.ktlint.KtlintExtension

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.google.services) apply false
}

subprojects {
    plugins.withId("com.android.library") {
        configureKtlintAndDetekt()
    }
    plugins.withId("com.android.application") {
        configureKtlintAndDetekt()
    }
}

fun Project.configureKtlintAndDetekt() {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "dev.detekt")

    configure<KtlintExtension> {
        android.set(true)
        verbose.set(true)
        outputToConsole.set(true)
        ignoreFailures.set(false)
        filter {
            exclude("**/build/**")
            exclude("**/generated/**")
        }
    }

    tasks.withType<Detekt>().configureEach {
        buildUponDefaultConfig = true
        allRules = false
        config.setFrom(file("$rootDir/detekt.yml"))
        exclude("**/build/**")
        exclude("**/generated/**")
    }

    tasks.withType<DetektCreateBaselineTask>().configureEach {
        exclude("**/build/**")
        exclude("**/generated/**")
    }

    // Lint warnings fail the build — same gate as detekt/ktlint/allWarningsAsErrors.
    // Generates lint-results-debug.html for inspection, but exit code is non-zero on any
    // warning/error so CI and the pre-commit hook catch them.
    plugins.withId("com.android.application") {
        extensions.configure<com.android.build.api.dsl.ApplicationExtension> {
            lint {
                warningsAsErrors = true
                abortOnError = true
            }
        }
    }
    plugins.withId("com.android.library") {
        extensions.configure<com.android.build.api.dsl.LibraryExtension> {
            lint {
                warningsAsErrors = true
                abortOnError = true
            }
        }
    }

}

// Hilt 2.59.2's annotation processor bundles org.jetbrains.kotlin:kotlin-metadata-jvm:2.2.20,
// which only supports reading Kotlin metadata up to v2.3.0. Dependencies compiled with
// Kotlin 2.4.0 (e.g. kotlinx-collections-immutable 0.5.0) ship class files with v2.4.0
// metadata, causing hiltJavaCompileDebug to fail with:
//   "Provided Metadata instance has version 2.4.0, while maximum supported version is 2.3.0"
// Force the latest stable kotlin-metadata-jvm (2.4.0) onto every project's annotation
// processor classpath so the shaded copy inside dagger-spi can read newer metadata.
allprojects {
    configurations.matching { it.name.contains("kapt", ignoreCase = true) || it.name.contains("Ksp", ignoreCase = true) || it.name.contains("AnnotationProcessor", ignoreCase = true) }
        .configureEach {
            resolutionStrategy {
                force("org.jetbrains.kotlin:kotlin-metadata-jvm:2.4.0")
            }
        }
}

val sqliteTmpDir: File = layout.projectDirectory.dir(".gradle/sqlite-tmp").asFile
if (!sqliteTmpDir.exists()) {
    sqliteTmpDir.mkdirs()
}
if (sqliteTmpDir.isDirectory) {
    System.setProperty("org.sqlite.tmpdir", sqliteTmpDir.absolutePath)
}
