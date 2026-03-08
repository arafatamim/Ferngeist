plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}

val sqliteTmpDir = layout.projectDirectory.dir(".gradle/sqlite-tmp").asFile
if (sqliteTmpDir.exists() && !sqliteTmpDir.isDirectory) {
    sqliteTmpDir.delete()
}
sqliteTmpDir.mkdirs()
System.setProperty("org.sqlite.tmpdir", sqliteTmpDir.absolutePath)
