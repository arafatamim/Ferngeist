@file:Suppress("MagicNumber") // UI timings and screen coordinates

package com.tamimarafat.ferngeist.e2e

import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import java.util.regex.Pattern

// Chat-flow and device-state steps layered over the driver's UiAutomator primitives.

/** Starts a chat from the agent's session list; only the first one asks for a directory and battery exemption. */
fun FerngeistDriver.newChat(firstChat: Boolean) {
    tapDesc("New session")
    if (firstChat) {
        typeInto(By.clazz("android.widget.EditText"), "/tmp")
        // Directory suggestions from earlier sessions can push Save off-screen.
        if (!hasObject(By.text("Save"), timeoutMs = 3_000)) device.swipe(360, 1000, 360, 400, 20)
        tapText("Save")
    }
    // The battery-optimisation prompt, when it appears, covers the chat for a while.
    repeat(30) {
        if (hasObject(By.text("Not now"))) tapText("Not now")
        if (hasObject(By.desc("Send"))) return
        Thread.sleep(500)
    }
    find(By.desc("Send"))
}

/** Opens the composer if it is collapsed, types the prompt and sends it. */
fun FerngeistDriver.send(prompt: String) {
    if (!device.hasObject(By.clazz("android.widget.EditText"))) tapDesc("Send")
    typeInto(By.clazz("android.widget.EditText"), prompt)
    tapDesc("Send")
}

private val sessionRow = By.text(Pattern.compile("""e2e_\d+_\d+"""))

/** Titles of the chats on the session list; the list reorders as chats are visited, so open them by title. */
fun FerngeistDriver.sessionTitles(waitMs: Long = WAIT_MS): List<String> {
    device.wait(Until.hasObject(sessionRow), waitMs)
    return device.findObjects(sessionRow).map { it.text }.sorted()
}

/** Forces the device into Doze (or back out), as a long unplugged idle night would. */
fun FerngeistDriver.setDoze(on: Boolean) {
    if (on) {
        device.executeShellCommand("dumpsys battery unplug")
        device.executeShellCommand("dumpsys deviceidle enable all")
        device.executeShellCommand("dumpsys deviceidle force-idle")
    } else {
        device.executeShellCommand("dumpsys deviceidle unforce")
        device.executeShellCommand("dumpsys battery reset")
    }
}

fun FerngeistDriver.foregroundServiceRunning(): Boolean =
    device.executeShellCommand("dumpsys activity services $APP").contains("isForeground=true")
