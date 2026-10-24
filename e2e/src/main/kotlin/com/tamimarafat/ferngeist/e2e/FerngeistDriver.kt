@file:Suppress("MagicNumber") // UI timings and screen coordinates

package com.tamimarafat.ferngeist.e2e

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until

const val APP = "com.tamimarafat.ferngeist"
internal const val WAIT_MS = 15_000L

/** Drives the installed app through UiAutomator; the app has no test tags, so it selects by text and content-desc. */
class FerngeistDriver {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    val device: UiDevice = UiDevice.getInstance(instrumentation)

    fun freshLaunch() {
        // The soft keyboard resizes the page and pushes the action buttons off-screen.
        device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 0")
        device.executeShellCommand("pm clear $APP")
        device.executeShellCommand("pm grant $APP android.permission.POST_NOTIFICATIONS")
        device.executeShellCommand("pm grant $APP android.permission.CAMERA")
        device.pressHome()
        launch()
    }

    fun launch() {
        device.executeShellCommand("am start -n $APP/.MainActivity")
        device.wait(Until.hasObject(By.pkg(APP).depth(0)), WAIT_MS)
    }

    fun find(
        selector: BySelector,
        timeoutMs: Long = WAIT_MS,
    ): UiObject2 =
        device.wait(Until.findObject(selector), timeoutMs) ?: treeSearch(selector)
            ?: error("Not found: $selector\n${dump()}")

    private fun present(selector: BySelector) = device.hasObject(selector) || treeSearch(selector) != null

    // A direct selector query sometimes misses nodes that a walk of the tree still sees.
    private fun treeSearch(selector: BySelector): UiObject2? =
        device.findObjects(By.pkg(APP)).firstNotNullOfOrNull { runCatching { it.findObject(selector) }.getOrNull() }

    fun tapText(text: String) = find(By.text(text)).click()

    /** Taps [tap] until [expect] shows; a tap that lands mid-transition is silently lost otherwise. */
    fun tapUntil(
        tap: BySelector,
        expect: BySelector,
    ) {
        repeat(4) {
            if (present(expect)) return
            (device.wait(Until.findObject(tap), 3_000) ?: treeSearch(tap))?.click()
            if (device.wait(Until.hasObject(expect), 6_000) || present(expect)) return
        }
        error("Never reached $expect after tapping $tap\n${dump()}")
    }

    /** The dialog checkbox is an unlabeled node that sits just left of its text. */
    fun tapLeftOf(text: String) {
        val bounds = find(By.text(text)).visibleBounds
        device.click(bounds.left - 60, bounds.centerY())
    }

    fun tapDesc(desc: String) = find(By.desc(desc)).click()

    fun typeInto(
        field: BySelector,
        value: String,
    ) {
        find(field).text = value
    }

    fun hasObject(
        selector: BySelector,
        timeoutMs: Long = 0,
    ): Boolean = device.wait(Until.hasObject(selector), timeoutMs) || treeSearch(selector) != null

    fun hasText(
        text: String,
        timeoutMs: Long = 0,
    ): Boolean = device.wait(Until.hasObject(By.textContains(text)), timeoutMs)

    // Nodes go stale while the UI recomposes mid-dump; skip those.
    fun dump(): String =
        device
            .findObjects(By.pkg(APP))
            .mapNotNull {
                runCatching {
                    "${it.className.substringAfterLast(
                        '.',
                    )} text=${it.text} desc=${it.contentDescription} ${it.visibleBounds}"
                }.getOrNull()
            }.joinToString("\n")
}
