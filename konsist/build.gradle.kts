plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget("17")
    }
}

dependencies {
    testImplementation(libs.konsist)
    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    useJUnit()
    // Konsist scans every module, so a change outside this module must not leave the task green.
    outputs.upToDateWhen { false }
}
