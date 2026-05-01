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
        ignoreFailures.set(true)
        filter {
            exclude("**/build/**")
            exclude("**/generated/**")
        }
    }

    tasks.withType<Detekt>().configureEach {
        buildUponDefaultConfig = true
        allRules = false
        baseline = file("$rootDir/detekt-baseline.xml")
        config.setFrom(file("$rootDir/detekt.yml"))
        exclude("**/build/**")
        exclude("**/generated/**")
    }

    tasks.withType<DetektCreateBaselineTask>().configureEach {
        exclude("**/build/**")
        exclude("**/generated/**")
    }

    tasks.named("check").configure {
        setDependsOn(dependsOn.filterNot { dep ->
            dep.toString().contains("detekt")
        })
    }
}

val sqliteTmpDir: File = layout.projectDirectory.dir(".gradle/sqlite-tmp").asFile
if (!sqliteTmpDir.exists()) {
    sqliteTmpDir.mkdirs()
}
if (sqliteTmpDir.isDirectory) {
    System.setProperty("org.sqlite.tmpdir", sqliteTmpDir.absolutePath)
}
