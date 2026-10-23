package conventions

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.KoModifier
import com.lemonappdev.konsist.api.verify.assertFalse
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.Test

/**
 * Kotlin coding conventions and general code-quality rules, asserted against the whole
 * production codebase. These are aspirational: they describe what the code should be,
 * not what it currently is.
 */
class ConventionsTest {
    private val upperCamel = Regex("[A-Z][a-zA-Z0-9]*")
    private val lowerCamel = Regex("_?[a-z][a-zA-Z0-9]*")
    private val upperSnake = Regex("[A-Z][A-Z0-9_]*")
    private val sourceRoot = Regex("/src/(main|foss|google)/kotlin/")

    @Test
    fun files_liveInPackageMatchingTheirDirectory() {
        Konsist.scopeFromProduction().files.assertTrue {
            val marker = sourceRoot.find(it.path) ?: return@assertTrue true
            val expected = it.path
                .substring(marker.range.last + 1)
                .substringBeforeLast("/")
                .replace('/', '.')
            it.packagee?.name == expected
        }
    }

    @Test
    fun imports_areNeverWildcards() {
        Konsist.scopeFromProduction().imports.assertFalse { it.isWildcard }
    }

    @Test
    fun classes_useUpperCamelCase() {
        Konsist.scopeFromProduction().classes().assertTrue { it.hasNameMatching(upperCamel) }
    }

    @Test
    fun functions_useLowerCamelCase() {
        Konsist.scopeFromProduction().functions()
            .filterNot { it.annotations.any { a -> a.name == "Composable" } }
            .assertTrue { it.hasNameMatching(lowerCamel) }
    }

    @Test
    fun properties_useLowerCamelOrUpperSnakeForConstants() {
        Konsist.scopeFromProduction().properties().assertTrue {
            it.hasNameMatching(lowerCamel) ||
                (it.hasModifier(KoModifier.CONST) && it.hasNameMatching(upperSnake))
        }
    }

    @Test
    fun enumConstants_useUpperSnakeCase() {
        Konsist.scopeFromProduction().classes()
            .filter { it.hasModifier(KoModifier.ENUM) }
            .flatMap { it.enumConstants }
            .assertTrue { it.hasNameMatching(upperSnake) }
    }

    @Test
    fun interfaces_areNotHungarianPrefixed() {
        Konsist.scopeFromProduction().interfaces().assertFalse { it.hasNameMatching(Regex("I[A-Z][a-zA-Z0-9]*")) }
    }

    @Test
    fun productionCode_hasNoSuppressAnnotations() {
        val analysisSuppression = Regex("""@Suppress\(\s*"[^"]*(detekt|ktlint|[Ll]int)[^"]*"""")
        Konsist.scopeFromProduction().files.assertFalse { analysisSuppression.containsMatchIn(it.text) }
    }

    @Test
    fun productionCode_avoidsPrintlnAndPrintStackTrace() {
        Konsist.scopeFromProduction().files.assertFalse {
            it.text.contains("println(") || it.text.contains("printStackTrace()")
        }
    }

    @Test
    fun functions_stayWithinSixtyLines() {
        Konsist.scopeFromProduction().functions()
            .filterNot { it.annotations.any { a -> a.name == "Composable" } }
            .assertTrue { it.text.lines().size <= 60 }
    }

    @Test
    fun dataClasses_declareTheirPropertiesInTheConstructor() {
        Konsist.scopeFromProduction().classes()
            .filter { it.hasModifier(KoModifier.DATA) }
            .flatMap { it.properties(includeNested = false) }
            // Interface implementations may compute the value; mutable body state may not.
            .filterNot { it.hasModifier(KoModifier.OVERRIDE) }
            .assertTrue { it.isConstructorDefined }
    }

    @Test
    fun testClasses_containingTests_endWithTest() {
        Konsist.scopeFromTest().classes()
            .filter { it.hasFunction { fn -> fn.annotations.any { a -> a.name == "Test" } } }
            .assertTrue { it.name.endsWith("Test") }
    }
}
