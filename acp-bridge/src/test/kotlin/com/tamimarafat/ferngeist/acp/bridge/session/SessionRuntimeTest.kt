package com.tamimarafat.ferngeist.acp.bridge.session

import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.ToolCallContent
import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.ToolKind
import com.tamimarafat.ferngeist.core.model.AssistantSegment
import com.tamimarafat.ferngeist.core.model.ChatImageData
import com.tamimarafat.ferngeist.core.model.ChatMessage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRuntimeTest {
    @Test
    fun hydrating_buffers_events_until_complete() =
        runTest {
            val runtime = SessionRuntime(sessionId = "ses_test")

            runtime.beginHydration()
            assertEquals(SessionLoadState.HYDRATING, runtime.snapshot.value.loadState)
            assertTrue(
                runtime.snapshot.value.messages
                    .isEmpty(),
            )

            runtime.onEvent(AppSessionEvent.UserMessage(text = "he", append = true))
            runtime.onEvent(AppSessionEvent.UserMessage(text = "y", append = true))

            // No partial transcript should leak before hydration completes.
            assertTrue(
                runtime.snapshot.value.messages
                    .isEmpty(),
            )

            runtime.completeHydration()

            val snapshot = runtime.snapshot.value
            assertEquals(SessionLoadState.READY, snapshot.loadState)
            assertEquals(1, snapshot.messages.size)
            assertEquals(ChatMessage.Role.USER, snapshot.messages.first().role)
            assertEquals("hey", snapshot.messages.first().content)
        }

    @Test
    fun large_replay_preserves_all_agent_chunks_in_order() =
        runTest {
            val runtime = SessionRuntime(sessionId = "ses_test")
            runtime.beginHydration()

            val expected =
                buildString {
                    for (i in 0 until 1200) {
                        val chunk = "[$i]"
                        append(chunk)
                        runtime.onEvent(AppSessionEvent.AgentMessage(chunk))
                    }
                }

            runtime.completeHydration()

            val snapshot = runtime.snapshot.value
            assertEquals(SessionLoadState.READY, snapshot.loadState)
            assertEquals(1, snapshot.messages.size)
            val assistant = snapshot.messages.single()
            assertEquals(ChatMessage.Role.ASSISTANT, assistant.role)
            assertEquals(expected, assistant.content)
            assertFalse(assistant.isStreaming)
        }

    @Test
    fun interleaved_tool_calls_keep_segment_order_and_updates() =
        runTest {
            val runtime = SessionRuntime(sessionId = "ses_test")
            runtime.beginHydration()

            runtime.onEvent(AppSessionEvent.AgentMessage("hello"))
            runtime.onEvent(
                AppSessionEvent.ToolCallStarted(
                    toolCallId = "tool_1",
                    title = "Read",
                    kind = ToolKind.READ,
                    status = ToolCallStatus.IN_PROGRESS,
                ),
            )
            runtime.onEvent(AppSessionEvent.AgentMessage(" world"))
            runtime.onEvent(
                AppSessionEvent.ToolCallUpdated(
                    toolCallId = "tool_1",
                    status = ToolCallStatus.COMPLETED,
                    title = null,
                    kind = null,
                    content = listOf(ToolCallContent.Content(ContentBlock.Text("ok"))),
                ),
            )
            runtime.onEvent(AppSessionEvent.TurnComplete("end_turn"))

            runtime.completeHydration()

            val message =
                runtime.snapshot.value.messages
                    .single()
            val segments = message.segments
            assertEquals(3, segments.size)
            assertEquals(AssistantSegment.Kind.MESSAGE, segments[0].kind)
            assertEquals("hello", segments[0].text)
            assertEquals(AssistantSegment.Kind.TOOL_CALL, segments[1].kind)
            assertEquals(AssistantSegment.Kind.MESSAGE, segments[2].kind)
            assertEquals(" world", segments[2].text)

            val tool = segments[1].toolCall
            assertEquals(ToolCallStatus.COMPLETED, tool?.status)
            assertEquals(
                "ok",
                (tool?.content?.first() as? ToolCallContent.Content)?.content?.let {
                    (it as? ContentBlock.Text)?.text
                },
            )
            assertFalse(message.isStreaming)
        }

    @Test
    fun post_hydration_events_append_after_committed_history() =
        runTest {
            val runtime = SessionRuntime(sessionId = "ses_test")
            runtime.beginHydration()
            runtime.onEvent(AppSessionEvent.UserMessage(text = "hey", append = true))
            runtime.completeHydration()

            runtime.onEvent(AppSessionEvent.AgentMessage("hello there"))
            runtime.onEvent(AppSessionEvent.TurnComplete("end_turn"))

            val snapshot = runtime.snapshot.value
            assertEquals(2, snapshot.messages.size)
            assertEquals("hey", snapshot.messages[0].content)
            assertEquals("hello there", snapshot.messages[1].content)
            assertEquals(ChatMessage.Role.ASSISTANT, snapshot.messages[1].role)
            assertFalse(snapshot.isStreaming)
        }

    @Test
    fun commands_and_usage_survive_hydration_boundary() =
        runTest {
            val runtime = SessionRuntime(sessionId = "ses_test")
            runtime.beginHydration()

            runtime.onEvent(AppSessionEvent.CommandsUpdated(listOf(CommandInfo("init"), CommandInfo("status"))))
            runtime.onEvent(
                AppSessionEvent.UsageUpdated(
                    totalTokens = 99,
                    contextWindowTokens = 4096,
                    costAmount = 0.12,
                    costCurrency = "USD",
                ),
            )

            runtime.completeHydration()

            val snapshot = runtime.snapshot.value
            assertTrue(snapshot.commandsAdvertised)
            assertEquals(listOf(CommandInfo("init"), CommandInfo("status")), snapshot.availableCommands)
            assertEquals(99, snapshot.usage?.totalTokens)
            assertEquals(4096, snapshot.usage?.contextWindowTokens)
            assertEquals(0.12, snapshot.usage?.costAmount ?: 0.0, 0.0001)
        }

    @Test
    fun reload_without_usage_update_keeps_last_known_context_usage() =
        runTest {
            val runtime = SessionRuntime(sessionId = "ses_test")

            // A completed turn publishes the context reading for this session.
            runtime.onEvent(
                AppSessionEvent.UsageUpdated(totalTokens = 21000, contextWindowTokens = 200000),
            )
            runtime.onEvent(AppSessionEvent.TurnComplete("end_turn"))
            val afterTurn = runtime.snapshot.value
            assertEquals(21000, afterTurn.usage?.totalTokens)

            // Agents emit `usage_update` only at end of turn, so a reconnect/load replay
            // carries no usage: the last known reading must survive it.
            runtime.beginHydration()
            runtime.onEvent(AppSessionEvent.UserMessage(text = "hey", append = true))
            runtime.completeHydration()

            val snapshot = runtime.snapshot.value
            assertEquals(21000, snapshot.usage?.totalTokens)
            assertEquals(200000, snapshot.usage?.contextWindowTokens)
        }

    @Test
    fun fail_hydration_sets_failed_state_and_error() =
        runTest {
            val runtime = SessionRuntime(sessionId = "ses_test")
            runtime.beginHydration()
            runtime.onEvent(AppSessionEvent.AgentMessage("partial"))

            runtime.failHydration("load failed")

            val snapshot = runtime.snapshot.value
            assertEquals(SessionLoadState.FAILED, snapshot.loadState)
            assertEquals("load failed", snapshot.error)
            assertFalse(snapshot.isStreaming)
        }

    @Test
    fun config_option_updates_replace_native_option_set() =
        runTest {
            val runtime = SessionRuntime(sessionId = "ses_test")
            runtime.onEvent(
                AppSessionEvent.ConfigOptionsUpdated(
                    listOf(
                        SessionConfigOption.Select(
                            id = "model",
                            name = "Model",
                            currentValue = "gpt-4",
                            choices =
                                listOf(
                                    SessionConfigChoice(id = "gpt-4", label = "GPT-4", value = "gpt-4"),
                                ),
                        ),
                        SessionConfigOption.BooleanOption(
                            id = "safe_mode",
                            name = "Safe Mode",
                            currentValue = true,
                        ),
                    ),
                ),
            )

            runtime.onEvent(
                AppSessionEvent.ConfigOptionsUpdated(
                    listOf(
                        SessionConfigOption.Select(
                            id = "model",
                            name = "Model",
                            currentValue = "gpt-5",
                            choices =
                                listOf(
                                    SessionConfigChoice(id = "gpt-5", label = "GPT-5", value = "gpt-5"),
                                ),
                        ),
                    ),
                ),
            )

            val snapshot = runtime.snapshot.value
            assertEquals(1, snapshot.configOptions.size)
            assertEquals("model", snapshot.configOptions.single().id)
            assertEquals("GPT-5", snapshot.configOptions.single().displayValueLabel())
        }

    @Test
    fun config_option_value_changed_updates_existing_option_without_replacing_set() =
        runTest {
            val runtime = SessionRuntime(sessionId = "ses_test")
            runtime.onEvent(
                AppSessionEvent.ConfigOptionsUpdated(
                    listOf(
                        SessionConfigOption.Select(
                            id = "temperature",
                            name = "Temperature",
                            currentValue = "balanced",
                            choices =
                                listOf(
                                    SessionConfigChoice(id = "balanced", label = "Balanced", value = "balanced"),
                                    SessionConfigChoice(id = "precise", label = "Precise", value = "precise"),
                                ),
                        ),
                        SessionConfigOption.BooleanOption(
                            id = "safe_mode",
                            name = "Safe Mode",
                            currentValue = false,
                        ),
                    ),
                ),
            )

            runtime.onEvent(
                AppSessionEvent.ConfigOptionValueChanged(
                    optionId = "safe_mode",
                    value = SessionConfigValue.BoolValue(true),
                ),
            )

            val snapshot = runtime.snapshot.value
            assertEquals(2, snapshot.configOptions.size)
            val safeMode =
                snapshot.configOptions
                    .filterIsInstance<SessionConfigOption.BooleanOption>()
                    .first { it.id == "safe_mode" }
            assertTrue(safeMode.currentValue)
        }

    @Test
    fun session_info_updated_stores_title_in_snapshot() =
        runTest {
            val runtime = SessionRuntime(sessionId = "ses_test")

            runtime.onEvent(
                AppSessionEvent.SessionInfoUpdated(
                    title = "My Chat Session",
                    updatedAt = "2026-07-19T12:00:00Z",
                ),
            )

            val snapshot = runtime.snapshot.value
            assertEquals("My Chat Session", snapshot.title)
        }

    @Test
    fun session_info_updated_with_null_title_preserves_existing_title() =
        runTest {
            val runtime = SessionRuntime(sessionId = "ses_test")

            runtime.onEvent(
                AppSessionEvent.SessionInfoUpdated(
                    title = "Original Title",
                    updatedAt = null,
                ),
            )
            runtime.onEvent(
                AppSessionEvent.SessionInfoUpdated(
                    title = null,
                    updatedAt = null,
                ),
            )

            val snapshot = runtime.snapshot.value
            assertEquals("Original Title", snapshot.title)
        }

    @Test
    fun session_info_updated_survives_hydration_boundary() =
        runTest {
            val runtime = SessionRuntime(sessionId = "ses_test")

            runtime.beginHydration()
            runtime.onEvent(
                AppSessionEvent.SessionInfoUpdated(
                    title = "Hydrated Chat",
                    updatedAt = null,
                ),
            )
            runtime.completeHydration()

            val snapshot = runtime.snapshot.value
            assertEquals(SessionLoadState.READY, snapshot.loadState)
            assertEquals("Hydrated Chat", snapshot.title)
        }

    @Test
    fun title_in_live_preserved_when_buffered_title_is_null_on_rehydration() =
        runTest {
            val runtime = SessionRuntime(sessionId = "ses_test")

            // First session: title established via SessionInfoUpdated
            runtime.onEvent(
                AppSessionEvent.SessionInfoUpdated(
                    title = "Existing Title",
                    updatedAt = null,
                ),
            )
            assertEquals("Existing Title", runtime.snapshot.value.title)

            // Reconnect: beginHydration -> completeHydration (buffered has no title)
            runtime.beginHydration()
            assertEquals(SessionLoadState.HYDRATING, runtime.snapshot.value.loadState)
            runtime.completeHydration()

            val snapshot = runtime.snapshot.value
            assertEquals(SessionLoadState.READY, snapshot.loadState)
            assertEquals("Existing Title", snapshot.title)
        }

    @Test
    fun restored_user_message_with_image_yields_chat_message_with_images() =
        runTest {
            val runtime = SessionRuntime(sessionId = "ses_test")
            runtime.beginHydration()

            val sampleImage =
                ChatImageData(
                    base64 =
                        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42m" +
                            "P8/5+hHgAHggJ/PchI7wAAAABJRU5ErkJggg==",
                    mimeType = "image/png",
                )

            // Simulate chunked restore: text chunk first, then image chunk
            runtime.onEvent(
                AppSessionEvent.UserMessage(
                    text = "Describe this image:",
                    append = true,
                ),
            )
            runtime.onEvent(
                AppSessionEvent.UserMessage(
                    text = "",
                    images = listOf(sampleImage),
                    append = true,
                ),
            )

            runtime.completeHydration()

            val snapshot = runtime.snapshot.value
            assertEquals(SessionLoadState.READY, snapshot.loadState)
            assertEquals(1, snapshot.messages.size)

            val message = snapshot.messages.single()
            assertEquals(ChatMessage.Role.USER, message.role)
            assertEquals("Describe this image:", message.content)
            assertEquals(1, message.images.size)
            assertEquals(sampleImage.base64, message.images[0].base64)
            assertEquals(sampleImage.mimeType, message.images[0].mimeType)
        }

    /** Regression: live-send image echo must not duplicate the user bubble or kill the stream. */
    @Test
    fun live_send_image_echo_dedups_and_preserves_stream() {
        val sampleImage =
            ChatImageData(
                base64 =
                    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8/5+hHgAHggJ/PchI7wAAAABJRU5ErkJggg==",
                mimeType = "image/png",
            )

        // Simulate onLocalPromptStarted: optimistic user bubble + streaming assistant placeholder
        var messages =
            SessionMessageReducer.appendLocalUserMessage(emptyList(), "hello", listOf(sampleImage), emptyList())
        assertEquals(1, messages.size)
        assertEquals("hello", messages[0].content)
        assertEquals(1, messages[0].images.size)

        messages = SessionMessageReducer.startStreaming(messages)
        assertEquals(2, messages.size)
        assertEquals(ChatMessage.Role.ASSISTANT, messages[1].role)
        assertTrue(messages[1].isStreaming)

        // Server echoes text chunk — must dedup, not duplicate
        val afterTextEcho =
            SessionMessageReducer.handleEvent(
                messages,
                emptyMap(),
                AppSessionEvent.UserMessage(text = "hello", append = true),
            )
        assertEquals(2, afterTextEcho.messages.size)
        assertTrue(afterTextEcho.messages[1].isStreaming)

        // Server echoes image chunk (empty text, image data) — must NOT create a duplicate,
        // must NOT kill the streaming placeholder
        val afterImageEcho =
            SessionMessageReducer.handleEvent(
                afterTextEcho.messages,
                afterTextEcho.toolCallIndex,
                AppSessionEvent.UserMessage(
                    text = "",
                    images = listOf(sampleImage),
                    append = true,
                ),
            )

        assertEquals("only one user bubble + one assistant placeholder", 2, afterImageEcho.messages.size)

        val userMsg = afterImageEcho.messages[0]
        assertEquals(ChatMessage.Role.USER, userMsg.role)
        assertEquals("hello", userMsg.content)
        assertEquals(1, userMsg.images.size)
        assertEquals(sampleImage.base64, userMsg.images[0].base64)

        val assistantMsg = afterImageEcho.messages[1]
        assertEquals(ChatMessage.Role.ASSISTANT, assistantMsg.role)
        assertTrue("streaming placeholder must still be streaming", assistantMsg.isStreaming)
    }

    /** Regression: a file echo (empty-text blob resource chunk) must not duplicate the user bubble. */
    @Test
    fun live_send_file_echo_dedups_and_preserves_file() {
        val sampleFile =
            com.tamimarafat.ferngeist.core.model.ChatFileData(
                name = "report.pdf",
                base64 = "QUJD",
                mimeType = "application/pdf",
                sizeBytes = 3L,
            )

        var messages =
            SessionMessageReducer.appendLocalUserMessage(emptyList(), "see attached", emptyList(), listOf(sampleFile))
        assertEquals(1, messages.size)
        assertEquals(1, messages[0].files.size)
        assertEquals("report.pdf", messages[0].files[0].name)

        messages = SessionMessageReducer.startStreaming(messages)
        assertEquals(2, messages.size)

        val afterFileEcho =
            SessionMessageReducer.handleEvent(
                messages,
                emptyMap(),
                AppSessionEvent.UserMessage(
                    text = "",
                    files = listOf(sampleFile),
                    append = true,
                ),
            )

        assertEquals("only one user bubble + one assistant placeholder", 2, afterFileEcho.messages.size)
        val userMsg = afterFileEcho.messages[0]
        assertEquals(ChatMessage.Role.USER, userMsg.role)
        assertEquals(1, userMsg.files.size)
        assertEquals("report.pdf", userMsg.files[0].name)
        assertTrue(afterFileEcho.messages[1].isStreaming)
    }
}
