@file:Suppress("MagicNumber") // UI timings and screen coordinates

package com.tamimarafat.ferngeist.e2e

import androidx.test.uiautomator.By
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private const val CHUNKS = 90
private const val CHUNK_GAP_MS = 1_000
private const val BACKGROUND_MS = 25_000L
private const val LONG_DOZE_MS = 120_000L

class MultiAgentBackgroundTest {
    private val app = FerngeistDriver()

    // The mock agent keeps chats on disk across tests, so the list starts with earlier tests' chats.
    private var earlierChats = emptySet<String>()

    @Before
    fun pairAndOpenAgent() {
        app.freshLaunch()
        app.tapUntil(By.desc("Add server or gateway"), By.text("Pair Ferngeist Gateway"))
        app.tapUntil(By.text("Pair Ferngeist Gateway"), By.text("Step 1 of 3"))
        app.tapUntil(By.text("Next"), By.text("Step 2 of 3"))
        // Step 2 sometimes renders without its payload field until the step is re-entered.
        repeat(3) {
            if (app.hasObject(By.clazz("android.widget.EditText"), timeoutMs = 4_000)) return@repeat
            app.tapDesc("Back")
            app.tapUntil(By.text("Next"), By.text("Step 2 of 3"))
        }
        // The app sometimes ignores a pasted payload; retype a fresh (single-use) one until Next advances.
        repeat(3) {
            if (app.hasObject(By.text("Step 3 of 3"))) return@repeat
            app.typeInto(By.clazz("android.widget.EditText"), Gateway.newPairingPayload())
            app.tapText("Next")
            app.hasObject(By.text("Step 3 of 3"), timeoutMs = 8_000)
        }
        app.find(By.text("Step 3 of 3"))
        app.tapUntil(By.text("Pair"), By.desc("Add server or gateway"))
        app.tapUntil(By.desc("Add server or gateway"), By.text("Add using paired gateway"))
        app.tapUntil(By.text("Add using paired gateway"), By.text("http://10.0.2.2:5798"))
        app.tapUntil(By.text("http://10.0.2.2:5798"), By.text("E2E Mock"))
        app.tapText("E2E Mock")
        app.tapLeftOf("I understand this may download and execute agent binaries on my gateway host.")
        app.tapText("Add agent")
        app.tapUntil(By.desc("Back"), By.text("Your agents"))
        app.tapText("E2E Mock")
        app.tapLeftOf("I understand and want to continue.")
        app.tapText("Continue")
        // The battery-optimisation prompt can cover the list as soon as the agent opens.
        if (app.hasObject(By.text("Not now"), timeoutMs = 5_000)) app.tapText("Not now")
        app.find(By.desc("New session"))
        earlierChats = app.sessionTitles(waitMs = 3_000).toSet()
    }

    /** Starts [tokens].size chats that all stream at once, leaving the app on the session list. */
    private fun startStreamingChats(tokens: List<String>) {
        tokens.forEachIndexed { i, token ->
            app.newChat(firstChat = i == 0)
            app.send("token=$token chunks=$CHUNKS every=$CHUNK_GAP_MS")
            app.find(By.textContains("$token#1 "))
            app.tapDesc("Back")
        }
    }

    /** Opens every listed chat in turn and returns the token each one finished with, keyed by its transcript. */
    private fun finishedTokens(tokens: List<String>): Set<String> {
        val finished = mutableSetOf<String>()
        (app.sessionTitles() - earlierChats).forEach { title ->
            // The list is still refreshing after the background, which can swallow a single tap.
            app.tapUntil(By.text(title), By.desc("Options"))
            // A chat still catching up after the background shows a partial transcript, so poll for its own marker.
            val deadline = System.currentTimeMillis() + 90_000
            var found: String? = null
            while (found == null && System.currentTimeMillis() < deadline) {
                found = tokens.firstOrNull { app.hasText("$it:done", timeoutMs = 500) }
            }
            val token = found ?: error("no token finished in $title\n${app.dump()}")
            val transcript = (1..CHUNKS).joinToString("") { "$token#$it " } + "$token:done"
            assertTrue("$token lost or reordered chunks: ${app.dump()}", app.hasText(transcript))
            finished += token
            app.tapDesc("Back")
        }
        return finished
    }

    /** Streams [chats] chats at once, backgrounds the app for a while, then checks every transcript is whole. */
    private fun streamWhileBackgrounded(
        chats: Int,
        doze: Boolean = false,
        forceStop: Boolean = false,
        backgroundMs: Long = BACKGROUND_MS,
    ) {
        val tokens = ('A'..'Z').take(chats).map { it.toString() }
        startStreamingChats(tokens)

        app.device.pressHome()
        if (forceStop) app.device.executeShellCommand("am force-stop $APP")
        if (doze) app.setDoze(true)
        try {
            Thread.sleep(backgroundMs)
            if (!doze && !forceStop) {
                assertTrue(
                    "foreground service stopped while chats were streaming",
                    app.foregroundServiceRunning(),
                )
            }
        } finally {
            if (doze) app.setDoze(false)
        }

        app.launch()
        assertEquals(tokens.toSet(), finishedTokens(tokens))
    }

    @Test
    fun threeChatsFinishWhileTheAppIsBackgrounded() = streamWhileBackgrounded(chats = 3)

    @Test
    fun chatsBeyondTheHotConnectionLimitStillFinishWhileBackgrounded() = streamWhileBackgrounded(chats = 4)

    @Test
    fun threeChatsSurviveDozeWhileBackgrounded() = streamWhileBackgrounded(chats = 3, doze = true)

    @Test
    fun threeChatsSurviveLongDozeWhileBackgrounded() =
        streamWhileBackgrounded(chats = 3, doze = true, backgroundMs = LONG_DOZE_MS)

    @Test
    fun threeChatsRecoverAfterForceStop() = streamWhileBackgrounded(chats = 3, forceStop = true)
}
