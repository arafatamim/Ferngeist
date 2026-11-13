package com.tamimarafat.ferngeist.feature.chat.artifact.ui

import android.app.Activity
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import com.tamimarafat.ferngeist.core.model.artifact.ChatArtifactMetadata
import com.tamimarafat.ferngeist.core.model.artifact.ChatArtifactReader
import com.tamimarafat.ferngeist.core.model.artifact.ChatArtifactStat
import com.tamimarafat.ferngeist.feature.chat.artifact.storage.ArtifactCache
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.OutputStream

/** Picker results must retain their host context and never leave an interrupted document behind. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArtifactHostTest {
    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val compose = createComposeRule(dispatcher)

    @get:Rule
    val temp = TemporaryFolder()

    private val registry = PickerRegistry()
    private val visible = mutableStateOf(true)
    private lateinit var host: ArtifactHost
    private lateinit var context: Context
    private lateinit var scope: CoroutineScope
    private var activeReader: ChatArtifactReader? = null
    private val gate = CompletableDeferred<Unit>().apply { complete(Unit) }
    private val started = CompletableDeferred<Unit>()
    private val bytes = byteArrayOf(0, 7, 8, -1)
    private val reader =
        object : ChatArtifactReader {
            override val identity = "http://original-host\n/work"

            override suspend fun write(
                path: String,
                destination: OutputStream,
                ifNoneMatch: String?,
            ): ChatArtifactMetadata {
                started.complete(Unit)
                gate.await()
                destination.write(bytes)
                return ChatArtifactMetadata("application/pdf", bytes.size.toLong(), null)
            }

            override suspend fun stat(paths: List<String>): List<ChatArtifactStat> = error("Unused")
        }

    @Test
    fun `a picker result after saved state restoration writes the original file`() {
        val restore = show()
        val destination = destination()
        compose.runOnIdle { host.download("/tmp/report.pdf", "report.pdf") }

        restore.emulateSavedInstanceStateRestore()
        compose.runOnIdle { registry.finish(destination.first) }
        compose.waitForIdle()

        dispatcher.scheduler.advanceUntilIdle()
        assertArrayEquals(bytes, destination.second.readBytes())
    }

    @Test
    fun `a restored download refuses a different host and removes the empty document`() {
        val restore = show()
        val destination = destination()
        compose.runOnIdle { host.download("/tmp/report.pdf", "report.pdf") }
        restore.emulateSavedInstanceStateRestore()
        compose.runOnIdle {
            activeReader =
                object : ChatArtifactReader by reader {
                    override val identity = "http://other-host\n/work"
                }
            registry.finish(destination.first)
        }
        compose.waitForIdle()
        dispatcher.scheduler.advanceUntilIdle()

        assertFalse(destination.second.exists())
    }

    @Test
    fun `leaving during a download removes the created document`() {
        val waiting = CompletableDeferred<Unit>()
        val slow =
            object : ChatArtifactReader by reader {
                override suspend fun write(
                    path: String,
                    destination: OutputStream,
                    ifNoneMatch: String?,
                ): ChatArtifactMetadata {
                    started.complete(Unit)
                    waiting.await()
                    return reader.write(path, destination, ifNoneMatch)
                }
            }
        show(slow)
        val destination = destination()
        compose.runOnIdle {
            host.download("/tmp/report.pdf", "report.pdf")
            registry.finish(destination.first)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            check(started.isCompleted)
            visible.value = false
        }
        compose.waitForIdle()

        dispatcher.scheduler.advanceUntilIdle()
        assertFalse(destination.second.exists())
    }

    @Test
    fun `a viewer can still read its URI after the cache evicts the downloaded file`() {
        show(budget = 0)
        compose.runOnIdle { scope.launch { host.open("/tmp/report.pdf") } }
        compose.waitForIdle()
        val activity =
            generateSequence(context) { (it as? ContextWrapper)?.baseContext }
                .filterIsInstance<Activity>()
                .first()
        val intent = checkNotNull(shadowOf(activity).nextStartedActivity)

        val viewed = context.contentResolver.openInputStream(checkNotNull(intent.data))!!.use { it.readBytes() }

        assertArrayEquals(bytes, viewed)
    }

    private fun show(
        bound: ChatArtifactReader = reader,
        budget: Long = Long.MAX_VALUE,
    ): StateRestorationTester {
        val cache =
            ArtifactCache(
                File(RuntimeEnvironment.getApplication().cacheDir, "artifacts"),
                budgetBytes = budget,
                io = dispatcher,
            )
        activeReader = bound
        return StateRestorationTester(compose).also { restore ->
            restore.setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry) {
                    if (visible.value) {
                        val current = LocalContext.current
                        val currentScope = rememberCoroutineScope()
                        val hosted =
                            rememberArtifactHost({ activeReader }, cache, dispatcher, SnackbarHostState(), true)
                        SideEffect {
                            context = current
                            scope = currentScope
                            host = checkNotNull(hosted)
                        }
                    }
                }
            }
            compose.waitForIdle()
        }
    }

    private fun destination(): Pair<Uri, File> {
        val file = temp.newFile("destination.pdf")
        val provider = DocumentProvider(file)
        provider.attachInfo(context, ProviderInfo().apply { authority = "review.documents" })
        ShadowContentResolver.registerProviderInternal("review.documents", provider)
        val uri = Uri.parse("content://review.documents/document/report")
        return uri to file
    }

    private class PickerRegistry :
        ActivityResultRegistry(),
        ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry get() = this
        private var requestCode = 0

        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            this.requestCode = requestCode
        }

        fun finish(uri: Uri) {
            dispatchResult(requestCode, Activity.RESULT_OK, Intent().setData(uri))
        }
    }

    private class DocumentProvider(
        private val file: File,
    ) : ContentProvider() {
        override fun onCreate() = true

        override fun getType(uri: Uri) = "application/pdf"

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            args: Array<out String>?,
            sort: String?,
        ): Cursor? = null

        override fun insert(
            uri: Uri,
            values: ContentValues?,
        ): Uri? = null

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            args: Array<out String>?,
        ) = 0

        override fun delete(
            uri: Uri,
            selection: String?,
            args: Array<out String>?,
        ) = if (file.delete()) 1 else 0

        override fun openFile(
            uri: Uri,
            mode: String,
        ): ParcelFileDescriptor =
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE)

        override fun call(
            method: String,
            arg: String?,
            extras: Bundle?,
        ): Bundle {
            check(method == "android:deleteDocument")
            file.delete()
            return Bundle()
        }
    }
}
