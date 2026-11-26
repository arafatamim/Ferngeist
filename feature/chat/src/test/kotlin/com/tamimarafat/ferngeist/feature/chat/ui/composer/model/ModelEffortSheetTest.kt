package com.tamimarafat.ferngeist.feature.chat.ui.composer.model

import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.tamimarafat.ferngeist.acp.bridge.facade.HubChatPresence
import com.tamimarafat.ferngeist.core.model.ChatConfigChoice
import com.tamimarafat.ferngeist.core.model.ChatConfigChoiceGroup
import com.tamimarafat.ferngeist.core.model.ChatConfigOption
import com.tamimarafat.ferngeist.core.model.ChatConfigValue
import com.tamimarafat.ferngeist.feature.chat.ChatIntent
import com.tamimarafat.ferngeist.feature.chat.ChatViewModel
import com.tamimarafat.ferngeist.feature.chat.DataStoreRecentSelectionStore
import com.tamimarafat.ferngeist.feature.chat.FakeGatewayRepository
import com.tamimarafat.ferngeist.feature.chat.TestFacade
import com.tamimarafat.ferngeist.feature.chat.TestFacadeFactory
import com.tamimarafat.ferngeist.feature.chat.configOptionRecentsKey
import com.tamimarafat.ferngeist.feature.chat.newTestHub
import com.tamimarafat.ferngeist.feature.chat.testRepositories
import com.tamimarafat.ferngeist.feature.chat.testStores
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ModelEffortSheetTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun pickCurrentModel_addsItToRecents() {
        val model = ChatConfigChoice(id = "opus", label = "Opus", value = "opus")
        val recent = mutableStateOf(emptyList<String>())
        compose.setContent {
            MaterialExpressiveTheme {
                ModelEffortSheet(
                    modelOption =
                        ChatConfigOption.Select(
                            id = "model",
                            name = "Model",
                            choices = listOf(model),
                            currentValue = model.value,
                        ),
                    effortOption = ChatConfigOption.Select(id = "effort", name = "Effort"),
                    recentModelValues = recent.value,
                    onSetConfigOption = { _, value -> recent.value = listOf(value) },
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithText("Opus").performClick()

        compose.onNodeWithText("Recent").assertIsDisplayed()
    }

    @Test
    fun pickDifferentModels_beforeAgentReplies_accumulatesRecentsInOpenSheet() {
        val labels = listOf("Haiku", "Sonnet", "Opus")
        val option =
            ChatConfigOption.Select(
                id = "model",
                name = "Model",
                groups =
                    listOf(
                        ChatConfigChoiceGroup("claude", "Claude", labels.map { ChatConfigChoice(it, it, it) }),
                        ChatConfigChoiceGroup(
                            "other",
                            "Other",
                            (1..7).map { ChatConfigChoice("$it", "Model $it", "$it") },
                        ),
                    ),
            )
        val recents = DataStoreRecentSelectionStore(RuntimeEnvironment.getApplication(), Json)
        val historyKey = configOptionRecentsKey("test-server", option.id)
        runBlocking { recents.addSelection(historyKey, "Haiku") }
        val viewModel = viewModelWithSilentAgent(recents)
        val history = viewModel.recentSelectionStore.getRecentSelections(historyKey)
        try {
            compose.setContent {
                val recent by history.collectAsStateWithLifecycle(emptyList())
                MaterialExpressiveTheme {
                    ModelEffortSheet(
                        modelOption = option,
                        effortOption = ChatConfigOption.Select(id = "effort", name = "Effort"),
                        recentModelValues = recent,
                        onSetConfigOption = { id, value ->
                            viewModel.dispatch(ChatIntent.SetConfigOption(id, ChatConfigValue.StringValue(value)))
                        },
                        onDismiss = {},
                    )
                }
            }

            labels.drop(1).forEach { label ->
                compose.onNodeWithText("Search Model").performTextInput(label)
                compose.onNode(hasText(label) and !hasSetTextAction()).performClick()
                compose.waitUntil(5_000) {
                    compose.onAllNodes(hasText("Recent")).fetchSemanticsNodes().isNotEmpty() &&
                        compose.onAllNodes(hasText(label)).fetchSemanticsNodes().isNotEmpty()
                }
            }
            labels.forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun viewModelWithSilentAgent(recents: DataStoreRecentSelectionStore): ChatViewModel {
        val repositories = testRepositories()
        return ChatViewModel(
            sessionFacadeFactory =
                TestFacadeFactory {
                    object : TestFacade() {
                        override suspend fun setConfigOption(
                            optionId: String,
                            value: ChatConfigValue,
                        ): Unit = awaitCancellation()
                    }
                },
            presence =
                HubChatPresence(
                    newTestHub(),
                    repositories.launchableTargetRepository,
                    FakeGatewayRepository(),
                    turnStatuses = MutableStateFlow(emptyMap()),
                ),
            repositories = repositories,
            stores = testStores(recentSelectionStore = recents),
            ioDispatcher = UnconfinedTestDispatcher(),
            savedStateHandle =
                SavedStateHandle(
                    mapOf("serverId" to "test-server", "sessionId" to "test-session", "cwd" to "/"),
                ),
        )
    }
}
