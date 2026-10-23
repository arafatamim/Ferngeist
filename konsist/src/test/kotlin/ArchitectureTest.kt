package architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.architecture.KoArchitectureCreator.assertArchitecture
import com.lemonappdev.konsist.api.architecture.Layer
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchitectureTest {
    @Test
    fun modules_dependOnlyOnLowerLayers() {
        Konsist.scopeFromProduction().assertArchitecture {
            val model = Layer("Core model", "com.tamimarafat.ferngeist.core.model..")
            val common = Layer("Core common", "com.tamimarafat.ferngeist.core.common..")
            val gateway = Layer("Gateway client", "com.tamimarafat.ferngeist.gateway..")
            val data = Layer("Data", "com.tamimarafat.ferngeist.data..")
            val acp = Layer("ACP bridge", "com.tamimarafat.ferngeist.acp..")
            val features = Layer("Features", "com.tamimarafat.ferngeist.feature..")

            model.dependsOnNothing()
            common.dependsOn(model)
            gateway.dependsOn(model)
            data.dependsOn(model)
            acp.dependsOn(model, gateway)
            features.dependsOn(model, common, gateway, acp)
        }
    }

    @Test
    fun features_doNotDependOnEachOther() {
        val prefix = "com.tamimarafat.ferngeist.feature."
        val violations = Konsist.scopeFromProduction().files
            .filter { it.packagee?.name?.startsWith(prefix) == true }
            .flatMap { file ->
                val owner = file.packagee!!.name.removePrefix(prefix).substringBefore(".")
                file.imports
                    .map { it.name }
                    .filter { it.startsWith(prefix) }
                    .filter { it.removePrefix(prefix).substringBefore(".") != owner }
                    .map { "${file.path} imports $it" }
            }
        assertTrue("Feature modules must not depend on each other:\n${violations.joinToString("\n")}", violations.isEmpty())
    }

    @Test
    fun onlyAppImportsAppDeclarations() {
        // Layers only police declared packages, so a dependency on the app module (which no
        // layer covers) would otherwise pass unnoticed. `app` and its test harness are the
        // only places allowed to reach into app-only packages.
        val internal = "com.tamimarafat.ferngeist."
        val moduleRoots = setOf("core", "feature", "acp", "gateway", "data")
        val violations = Konsist.scopeFromProduction().files
            .filterNot { it.path.contains("/app/") || it.path.contains("/e2e/") }
            .flatMap { file ->
                file.imports
                    .map { it.name }
                    .filter { it.startsWith(internal) }
                    .filter { it.removePrefix(internal).substringBefore(".") !in moduleRoots }
                    .map { "${file.path} imports $it" }
            }
        assertTrue("Only app may depend on app packages:\n${violations.joinToString("\n")}", violations.isEmpty())
    }

    @Test
    fun coreModel_hasNoAndroidOrComposeImports() {
        val violations = Konsist.scopeFromProduction().files
            .filter { it.path.contains("/core/model/") }
            .flatMap { file ->
                file.imports
                    .map { it.name }
                    .filter { it.startsWith("android.") || it.startsWith("androidx.") }
                    .map { "${file.path} imports $it" }
            }
        assertTrue("core:model must stay platform-free:\n${violations.joinToString("\n")}", violations.isEmpty())
    }
}
