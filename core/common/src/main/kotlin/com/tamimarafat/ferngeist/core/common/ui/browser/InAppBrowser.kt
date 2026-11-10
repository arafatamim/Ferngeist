package com.tamimarafat.ferngeist.core.common.ui.browser

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler

/**
 * Half the display, which is the smallest sheet Chrome honours — it clamps anything smaller —
 * so the chat stays visible behind the page.
 */
private const val SHEET_HEIGHT_FRACTION = 0.5f

/**
 * Sends link taps to the default browser as a partial Custom Tab rather than switching tasks to
 * the full browser app: the browser's own engine and cookies, in a bottom sheet over this app,
 * with back returning here.
 *
 * Installed once at the composition root, so every `LocalUriHandler.current` caller — markdown
 * links in chat, privacy policy, git web links — opens the same way instead of each call site
 * choosing for itself.
 */
@Composable
fun ProvideInAppBrowser(content: @Composable () -> Unit) {
    val context = LocalContext.current
    // Chrome only honours the sheet height for a tab started for result (or a warm
    // `CustomTabsServiceConnection`); a plain `launchUrl` drops the height and spans the screen.
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    val handler =
        remember(context, launcher) {
            InAppBrowserUriHandler(context, launcher::launch)
        }
    CompositionLocalProvider(LocalUriHandler provides handler, content = content)
}

/**
 * `mailto:`, `tel:`, `content:` and friends are not web pages a tab can render, so they keep
 * going to whatever the system resolves — the same placeholder the default handler used.
 *
 * shortcut: a browser that predates partial tabs ignores the height and opens full-screen;
 * there is no fallback that would make the sheet smaller than the browser allows.
 */
private class InAppBrowserUriHandler(
    private val context: Context,
    private val launchForResult: (Intent) -> Unit,
) : UriHandler {
    override fun openUri(uri: String) {
        val parsed = Uri.parse(uri)
        when (parsed.scheme) {
            "http", "https" -> launchForResult(sheet(parsed))
            else -> context.startActivity(Intent(Intent.ACTION_VIEW, parsed))
        }
    }

    private fun sheet(uri: Uri): Intent =
        CustomTabsIntent
            .Builder()
            .setShowTitle(true)
            .setInitialActivityHeightPx(
                (context.resources.displayMetrics.heightPixels * SHEET_HEIGHT_FRACTION).toInt(),
                CustomTabsIntent.ACTIVITY_HEIGHT_ADJUSTABLE,
            ).build()
            .intent
            .setData(uri)
}
