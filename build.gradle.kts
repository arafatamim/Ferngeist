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

// Hilt's annotation processor bundles org.jetbrains.kotlin:kotlin-metadata-jvm, which only
// supports reading Kotlin metadata up to some version. Dependencies compiled with a newer
// Kotlin (e.g. kotlinx-collections-immutable) ship class files with newer metadata, causing
// hiltJavaCompileDebug to fail with:
//   "Provided Metadata instance has version X, while maximum supported version is Y"
// Force the latest kotlin-metadata-jvm onto every project's annotation processor
// classpath so the shaded copy inside dagger-spi can read newer metadata.
allprojects {
    configurations.matching { it.name.contains("kapt", ignoreCase = true) || it.name.contains("Ksp", ignoreCase = true) || it.name.contains("AnnotationProcessor", ignoreCase = true) }
        .configureEach {
            resolutionStrategy {
                force("org.jetbrains.kotlin:kotlin-metadata-jvm:2.4.21")
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
