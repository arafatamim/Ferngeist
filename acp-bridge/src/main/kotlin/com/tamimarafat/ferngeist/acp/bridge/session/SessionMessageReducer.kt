package com.tamimarafat.ferngeist.acp.bridge.session

import com.agentclientprotocol.model.PlanEntry
import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.ToolKind
import com.tamimarafat.ferngeist.core.model.AcpPermissionOption
import com.tamimarafat.ferngeist.core.model.AssistantSegment
import com.tamimarafat.ferngeist.core.model.ChatFileData
import com.tamimarafat.ferngeist.core.model.ChatImageData
import com.tamimarafat.ferngeist.core.model.ChatMessage
import com.tamimarafat.ferngeist.core.model.ToolCallDisplay
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.mutate
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap
import java.util.UUID

/**
 * Location of a tool call segment inside the message list.
 *
 * Together with a [Map] keyed by `toolCallId`, this gives O(1) lookup for tool
 * call updates/permissions instead of the O(messages × segments) scan the
 * reducer used to do.
 */
data class ToolCallLocation(
    val messageIndex: Int,
    val segmentIndex: Int,
)

/**
 * Result of reducing a single event: the updated message list plus the
 * (mostly unchanged) tool call index. Sub-handlers that don't touch tool
 * calls return the same index they were given; sub-handlers that do
 * ([upsertToolCall], [updateToolCall], [updateToolCallPermission],
 * [clearToolCallPermission]) return a precisely updated copy.
 */
data class ReducerResult(
    val messages: List<ChatMessage>,
    val toolCallIndex: Map<String, ToolCallLocation>,
)

object SessionMessageReducer {
    fun handleEvent(
        messages: List<ChatMessage>,
        toolCallIndex: Map<String, ToolCallLocation>,
        event: AppSessionEvent,
    ): ReducerResult =
        when (event) {
            is AppSessionEvent.UserMessage ->
                ReducerResult(
                    messages =
                        appendUserText(
                            messages,
                            event.text,
                            event.images,
                            event.files,
                            event.append,
                            event.timestampMs,
                        ),
                    toolCallIndex = toolCallIndex,
                )
            is AppSessionEvent.AgentMessage ->
                appendText(
                    messages,
                    toolCallIndex,
                    event.text,
                    AssistantSegment.Kind.MESSAGE,
                    event.timestampMs,
                )
            is AppSessionEvent.AgentThought ->
                appendText(
                    messages,
                    toolCallIndex,
                    event.text,
                    AssistantSegment.Kind.THOUGHT,
                    event.timestampMs,
                )
            is AppSessionEvent.ToolCallStarted ->
                upsertToolCall(messages, toolCallIndex, event)
            is AppSessionEvent.ToolCallUpdated ->
                updateToolCall(messages, toolCallIndex, event)
            is AppSessionEvent.ToolPermissionRequested ->
                updateToolCallPermission(messages, toolCallIndex, event)
            is AppSessionEvent.ToolPermissionResolved ->
                clearToolCallPermission(messages, toolCallIndex, event.toolCallId)
            is AppSessionEvent.PlanUpdated ->
                ReducerResult(
                    messages = updatePlan(messages, event.entries, event.timestampMs),
                    toolCallIndex = toolCallIndex,
                )
            is AppSessionEvent.TurnComplete ->
                ReducerResult(
                    messages = failUnfinishedToolCalls(finishStreaming(messages)),
                    toolCallIndex = toolCallIndex,
                )
            else ->
                ReducerResult(messages = messages, toolCallIndex = toolCallIndex)
        }

    fun startStreaming(messages: List<ChatMessage>): List<ChatMessage> {
        // Idempotent: avoid stacking placeholder bubbles when called repeatedly
        if (messages.isNotEmpty() &&
            messages.last().role == ChatMessage.Role.ASSISTANT &&
            messages.last().isStreaming
        ) {
            return messages
        }

        val streamingMessage =
            ChatMessage(
                id = UUID.randomUUID().toString(),
                role = ChatMessage.Role.ASSISTANT,
                content = "",
                isStreaming = true,
            )
        return messages + streamingMessage
    }

    /**
     * [steered] is a prompt pushed into the running turn: the reply so far closes, keeping
     * its running tool calls as they are, so the rest of the turn streams below the prompt.
     */
    fun appendLocalUserMessage(
        messages: List<ChatMessage>,
        text: String,
        images: List<ChatImageData>,
        files: List<ChatFileData>,
        steered: Boolean = false,
    ): List<ChatMessage> {
        // Optimistic insertion: the UI gets an immediate user bubble before the server round-trip
        if (text.isBlank() && images.isEmpty() && files.isEmpty()) return messages
        val last = messages.lastOrNull()
        val closed =
            if (steered && last?.role == ChatMessage.Role.ASSISTANT && last.isStreaming) {
                messages.dropLast(1) + last.copy(isStreaming = false)
            } else {
                messages
            }
        return closed +
            ChatMessage(
                role = ChatMessage.Role.USER,
                content = text,
                images = images,
                files = files,
                createdAt = System.currentTimeMillis(),
            )
    }

    private fun appendUserText(
        messages: List<ChatMessage>,
        text: String,
        images: List<ChatImageData>,
        files: List<ChatFileData>,
        append: Boolean,
        timestampMs: Long?,
    ): List<ChatMessage> {
        if (text.isEmpty() && images.isEmpty() && files.isEmpty()) return messages
        val mutableMessages = messages.mutableCopy()
        val lastMessage = mutableMessages.lastOrNull()

        // Dedup against the already-present user bubble (or the streaming placeholder that
        // follows it) before appending a new one. Returns non-null when the caller should
        // use the returned list instead of appending.
        deduplicateOrMergeUserMessage(mutableMessages, lastMessage, text, images, files, append)?.let {
            return it.toPersistentList()
        }

        // A new user message arriving means any running assistant turn is obsolete — close it
        val lastStreamingIndex =
            mutableMessages.indexOfLast {
                it.role == ChatMessage.Role.ASSISTANT && it.isStreaming
            }
        if (lastStreamingIndex == mutableMessages.lastIndex && lastStreamingIndex >= 0) {
            val streaming = mutableMessages[lastStreamingIndex]
            mutableMessages[lastStreamingIndex] = streaming.copy(isStreaming = false)
        }

        mutableMessages.add(
            ChatMessage(
                role = ChatMessage.Role.USER,
                content = text,
                images = images,
                files = files,
                createdAt = timestampMs ?: System.currentTimeMillis(),
            ),
        )
        return mutableMessages.build()
    }

    /**
     * When [append] is true this handles the server echo path: exact-match dedup against a
     * USER bubble, image-merge for image chunks, and placeholder dedup. When false it handles
     * the local optimistic insertion dedup. Returns null when a new message should be appended.
     */
    private fun deduplicateOrMergeUserMessage(
        mutableMessages: MutableList<ChatMessage>,
        lastMessage: ChatMessage?,
        text: String,
        images: List<ChatImageData>,
        files: List<ChatFileData>,
        append: Boolean,
    ): List<ChatMessage>? {
        if (append) {
            // Exact-match dedup when the last message is already a USER bubble.
            // Image chunks arrive as separate UserMessageChunks with empty text — merge
            // images even when the text is empty or unchanged.
            if (lastMessage?.role == ChatMessage.Role.USER) {
                val merged = mergeIntoUserBubble(mutableMessages, lastMessage, text, images, files)
                if (merged != null) return merged
            }
            return if (placeholderMatchesPreviousUser(mutableMessages, lastMessage, text, exact = false)) {
                mutableMessages
            } else {
                null
            }
        } else {
            // append=false: local optimistic insertion (or redundant source) — dedup against the
            // last USER bubble or against a streaming placeholder whose preceding USER matches
            if (lastMessage?.role == ChatMessage.Role.USER && lastMessage.content == text) {
                return mutableMessages
            }
            return if (placeholderMatchesPreviousUser(mutableMessages, lastMessage, text, exact = true)) {
                mutableMessages
            } else {
                null
            }
        }
    }

    /**
     * Returns the merged message list when [lastMessage] is a USER bubble, or null when the
     * chunk should be appended as a new message. Merges when content/images/files differ,
     * and returns the unchanged list on an exact duplicate.
     */
    private fun mergeIntoUserBubble(
        mutableMessages: MutableList<ChatMessage>,
        lastMessage: ChatMessage,
        text: String,
        images: List<ChatImageData>,
        files: List<ChatFileData>,
    ): List<ChatMessage>? {
        val dedup = lastMessage.content == text && images.isEmpty() && files.isEmpty()
        if (dedup) return mutableMessages
        mutableMessages[mutableMessages.lastIndex] =
            lastMessage.copy(
                content = lastMessage.content + text,
                images = lastMessage.images + images,
                files = lastMessage.files + files,
            )
        return mutableMessages
    }

    private fun placeholderMatchesPreviousUser(
        mutableMessages: List<ChatMessage>,
        lastMessage: ChatMessage?,
        text: String,
        exact: Boolean,
    ): Boolean {
        if (mutableMessages.size < 2) return false
        val previousMessage = mutableMessages[mutableMessages.lastIndex - 1]
        return if (exact) {
            isStreamingPlaceholderWithExactPreviousUser(lastMessage, previousMessage, text)
        } else {
            isStreamingPlaceholderWithPreviousUser(lastMessage, previousMessage, text)
        }
    }

    private fun appendText(
        messages: List<ChatMessage>,
        toolCallIndex: Map<String, ToolCallLocation>,
        text: String,
        kind: AssistantSegment.Kind,
        timestampMs: Long?,
    ): ReducerResult {
        if (text.isEmpty()) return ReducerResult(messages, toolCallIndex)
        val mutableMessages = messages.mutableCopy()

        val lastMessage = mutableMessages.lastOrNull()
        val targetIndex =
            if (lastMessage?.role == ChatMessage.Role.ASSISTANT) {
                // Reuse the last assistant bubble when chunks arrive in sequence
                mutableMessages.lastIndex
            } else {
                // First chunk of a new turn — seed a fresh assistant bubble
                val newMessage =
                    ChatMessage(
                        role = ChatMessage.Role.ASSISTANT,
                        isStreaming = true,
                        createdAt = timestampMs ?: System.currentTimeMillis(),
                    )
                mutableMessages.add(newMessage)
                mutableMessages.lastIndex
            }

        val message = mutableMessages[targetIndex]

        // One segment per chunk. Folding the chunk into the trailing segment's text would copy
        // the whole accumulated bubble on every chunk — O(n^2) bytes across a replay, which is
        // what made long transcripts slow to load. So chunks land as their own segments and
        // [finishStreaming] folds adjacent MESSAGE segments into one per turn, which is also where
        // the flat `content` view is derived. Markdown is parsed per segment, not per message, so
        // until that fold a live reply renders as one document per chunk and a construct split
        // across a chunk boundary shows as literal text — the fold is what makes the settled
        // reply a single document again.
        val newSegments: PersistentList<AssistantSegment> =
            message.segments.mutate { segments ->
                segments.add(AssistantSegment(id = UUID.randomUUID().toString(), kind = kind, text = text))
            }

        mutableMessages[targetIndex] =
            message.copy(
                segments = newSegments,
                content = "",
                isStreaming = true,
            )
        return ReducerResult(mutableMessages.build(), toolCallIndex)
    }

    private fun updatePlan(
        messages: List<ChatMessage>,
        entries: List<PlanEntry>,
        timestampMs: Long?,
    ): List<ChatMessage> {
        if (entries.isEmpty()) return messages
        val mutableMessages = messages.mutableCopy()

        val lastMessage = mutableMessages.lastOrNull()
        val targetIndex =
            if (lastMessage?.role == ChatMessage.Role.ASSISTANT) {
                mutableMessages.lastIndex
            } else {
                val newMessage =
                    ChatMessage(
                        role = ChatMessage.Role.ASSISTANT,
                        isStreaming = true,
                        createdAt = timestampMs ?: System.currentTimeMillis(),
                    )
                mutableMessages.add(newMessage)
                mutableMessages.lastIndex
            }

        val message = mutableMessages[targetIndex]

        // Plans are a log: each update replaces the preceding plan segment rather than stacking
        val newSegments: PersistentList<AssistantSegment> =
            message.segments.mutate { segments ->
                val existingPlanIndex = segments.indexOfLast { it.kind == AssistantSegment.Kind.PLAN }
                if (existingPlanIndex != -1) {
                    segments[existingPlanIndex] =
                        segments[existingPlanIndex].copy(
                            text = "",
                            planEntries = entries,
                        )
                } else {
                    segments.add(
                        AssistantSegment(
                            id = UUID.randomUUID().toString(),
                            kind = AssistantSegment.Kind.PLAN,
                            text = "",
                            planEntries = entries,
                        ),
                    )
                }
            }

        val updatedContent = derivedContent(newSegments)

        mutableMessages[targetIndex] =
            message.copy(
                segments = newSegments,
                content = updatedContent,
                isStreaming = true,
            )
        return mutableMessages.build()
    }

    private fun upsertToolCall(
        messages: List<ChatMessage>,
        toolCallIndex: Map<String, ToolCallLocation>,
        event: AppSessionEvent.ToolCallStarted,
    ): ReducerResult {
        val toolCallId = event.toolCallId.ifBlank { "tool_${UUID.randomUUID()}" }

        // Idempotent: a ToolCallStarted with an id already known to the index (replay,
        // out-of-order bootstrap from updateToolCall) must not duplicate the segment
        val existing = toolCallIndex[toolCallId]
        if (existing != null) {
            return ReducerResult(messages, toolCallIndex)
        }

        val mutableMessages = messages.mutableCopy()
        val lastMessage = mutableMessages.lastOrNull()
        val targetIndex =
            if (lastMessage?.role == ChatMessage.Role.ASSISTANT && lastMessage.isStreaming) {
                // Attach to the actively streaming bubble rather than creating a new message
                mutableMessages.lastIndex
            } else {
                mutableMessages.add(
                    ChatMessage(
                        role = ChatMessage.Role.ASSISTANT,
                        isStreaming = true,
                        createdAt = System.currentTimeMillis(),
                    ),
                )
                mutableMessages.lastIndex
            }

        val message = mutableMessages[targetIndex]
        val newSegmentIndex = message.segments.size

        val newSegment =
            AssistantSegment(
                id = UUID.randomUUID().toString(),
                kind = AssistantSegment.Kind.TOOL_CALL,
                toolCall =
                    ToolCallDisplay(
                        toolCallId = toolCallId,
                        title = event.title.ifBlank { "Tool Call" },
                        kind = event.kind,
                        status = event.status,
                        rawInput = event.rawInput,
                        locations = event.locations,
                    ),
            )
        val newSegments = message.segments.adding(newSegment)
        mutableMessages[targetIndex] = message.copy(segments = newSegments)
        val location = ToolCallLocation(targetIndex, newSegmentIndex)
        val newIndex = toolCallIndex.toPersistentMap().putting(toolCallId, location)
        return ReducerResult(mutableMessages.build(), newIndex)
    }

    private fun updateToolCall(
        messages: List<ChatMessage>,
        toolCallIndex: Map<String, ToolCallLocation>,
        event: AppSessionEvent.ToolCallUpdated,
    ): ReducerResult {
        val incomingToolCallId = event.toolCallId.ifBlank { "tool_${UUID.randomUUID()}" }
        val located = locateToolCall(messages, toolCallIndex, incomingToolCallId)
        if (located == null) {
            // Out-of-order event: ToolCallUpdated arrived before ToolCallStarted. Bootstrap a
            // ToolCallStarted entry first and then apply the update on top. The bootstrap
            // populates the index, so the recursive call resolves in O(1).
            val bootstrapped =
                upsertToolCall(
                    messages,
                    toolCallIndex,
                    AppSessionEvent.ToolCallStarted(
                        toolCallId = incomingToolCallId,
                        title = event.title ?: "Tool Call",
                        kind = event.kind,
                        status = event.status,
                        rawInput = event.rawInput,
                        locations = event.locations,
                    ),
                )
            return updateToolCall(
                bootstrapped.messages,
                bootstrapped.toolCallIndex,
                event.copy(toolCallId = incomingToolCallId),
            )
        }

        val (location, resolvedIndex) = located
        val messageIndex = location.messageIndex
        val segmentIndex = location.segmentIndex
        val mutableMessages = messages.mutableCopy()
        val message = mutableMessages[messageIndex]

        val newSegments: PersistentList<AssistantSegment> =
            message.segments.mutate { segments ->
                val oldSegment = segments[segmentIndex]
                val oldToolCall = oldSegment.toolCall
                segments[segmentIndex] =
                    oldSegment.copy(
                        toolCall =
                            oldToolCall?.copy(
                                content = event.content ?: oldToolCall.content,
                                status = event.status ?: oldToolCall.status,
                                title = event.title ?: oldToolCall.title.ifBlank { "Tool Call" },
                                kind = event.kind ?: oldToolCall.kind,
                                rawInput = event.rawInput ?: oldToolCall.rawInput,
                                rawOutput = event.rawOutput ?: oldToolCall.rawOutput,
                                locations = event.locations ?: oldToolCall.locations,
                            ),
                    )
            }
        mutableMessages[messageIndex] = message.copy(segments = newSegments)
        return ReducerResult(mutableMessages.build(), resolvedIndex)
    }

    private fun updateToolCallPermission(
        messages: List<ChatMessage>,
        toolCallIndex: Map<String, ToolCallLocation>,
        event: AppSessionEvent.ToolPermissionRequested,
    ): ReducerResult {
        // Ensure the tool call exists. Permission requests can arrive before the
        // corresponding ToolCallStarted (e.g. when a permission UI is shown immediately).
        val bootstrapped =
            if (toolCallIndex.containsKey(event.toolCallId)) {
                ReducerResult(messages, toolCallIndex)
            } else {
                upsertToolCall(
                    messages,
                    toolCallIndex,
                    AppSessionEvent.ToolCallStarted(
                        toolCallId = event.toolCallId,
                        title = event.title ?: "Permission Request",
                        kind = ToolKind.OTHER,
                        status = ToolCallStatus.PENDING,
                    ),
                )
            }

        val located =
            locateToolCall(bootstrapped.messages, bootstrapped.toolCallIndex, event.toolCallId)
                ?: return bootstrapped
        val (location, resolvedIndex) = located
        val mutableMessages = bootstrapped.messages.mutableCopy()
        val message = mutableMessages[location.messageIndex]
        val oldSegment = message.segments[location.segmentIndex]
        val oldToolCall = oldSegment.toolCall ?: return bootstrapped

        val newSegments: PersistentList<AssistantSegment> =
            message.segments.mutate { segments ->
                segments[location.segmentIndex] =
                    oldSegment.copy(
                        toolCall =
                            oldToolCall.copy(
                                title = oldToolCall.title.ifBlank { event.title ?: "Permission Request" },
                                status = ToolCallStatus.PENDING,
                                permissionRequestId = event.requestId,
                                permissionOptions =
                                    event.options.map {
                                        AcpPermissionOption(
                                            id = it.id,
                                            label = it.label,
                                            kind = it.kind ?: "unknown",
                                        )
                                    },
                            ),
                    )
            }
        mutableMessages[location.messageIndex] = message.copy(segments = newSegments)
        return ReducerResult(mutableMessages.build(), resolvedIndex)
    }

    private fun clearToolCallPermission(
        messages: List<ChatMessage>,
        toolCallIndex: Map<String, ToolCallLocation>,
        toolCallId: String,
    ): ReducerResult {
        val located =
            locateToolCall(messages, toolCallIndex, toolCallId)
                ?: return ReducerResult(messages, toolCallIndex)
        val (location, resolvedIndex) = located
        val mutableMessages = messages.mutableCopy()
        val message = mutableMessages[location.messageIndex]
        val oldSegment = message.segments[location.segmentIndex]
        val oldToolCall = oldSegment.toolCall ?: return ReducerResult(messages, resolvedIndex)

        val newSegments: PersistentList<AssistantSegment> =
            message.segments.mutate { segments ->
                segments[location.segmentIndex] =
                    oldSegment.copy(
                        toolCall =
                            oldToolCall.copy(
                                permissionOptions = null,
                                permissionRequestId = null,
                                // A PENDING tool call that had a permission request transitions to
                                // IN_PROGRESS once permission is granted (or denied)
                                status =
                                    if (oldToolCall.status == ToolCallStatus.PENDING &&
                                        oldToolCall.permissionRequestId != null
                                    ) {
                                        ToolCallStatus.IN_PROGRESS
                                    } else {
                                        oldToolCall.status
                                    },
                            ),
                    )
            }
        mutableMessages[location.messageIndex] = message.copy(segments = newSegments)
        return ReducerResult(mutableMessages.build(), resolvedIndex)
    }

    /**
     * Resolves where [toolCallId] lives right now, repairing the index when it points at some
     * other segment. The index is a cache, not a fixed address: [finishStreaming] folds adjacent
     * MESSAGE segments into one when a turn closes, which moves every tool call that follows them
     * down, and the index is not rebuilt on the runtime-owned close paths. Without the re-check a
     * late update would write to whatever segment now sits at the recorded index (or throw).
     *
     * Returns the location plus the (possibly repaired) index, or null when the id is nowhere to
     * be found — the caller's bootstrap path.
     */
    private fun locateToolCall(
        messages: List<ChatMessage>,
        toolCallIndex: Map<String, ToolCallLocation>,
        toolCallId: String,
    ): Pair<ToolCallLocation, Map<String, ToolCallLocation>>? {
        val indexed = toolCallIndex[toolCallId]
        if (indexed != null && toolCallIdAt(messages, indexed) == toolCallId) {
            return indexed to toolCallIndex
        }
        messages.forEachIndexed { messageIndex, message ->
            message.segments.forEachIndexed { segmentIndex, segment ->
                if (segment.toolCall?.toolCallId == toolCallId) {
                    val location = ToolCallLocation(messageIndex, segmentIndex)
                    return location to toolCallIndex.toPersistentMap().putting(toolCallId, location)
                }
            }
        }
        return null
    }

    /**
     * A mutable view that shares structure with [this] list. Every event edits one message near
     * the tail, so copying the whole list per event made a `session/load` replay
     * O(events x messages) - long transcripts outran the load deadline.
     */
    private fun List<ChatMessage>.mutableCopy(): PersistentList.Builder<ChatMessage> = toPersistentList().builder()

    /** The tool call id recorded at [location], or null when the location points at nothing. */
    private fun toolCallIdAt(
        messages: List<ChatMessage>,
        location: ToolCallLocation,
    ): String? =
        messages
            .getOrNull(location.messageIndex)
            ?.segments
            ?.getOrNull(location.segmentIndex)
            ?.toolCall
            ?.toolCallId

    /**
     * Clears the streaming flag on every message that carries it.
     *
     * TurnComplete can arrive for a streaming message in any position, and more than one bubble can
     * be streaming at once (the order of prompt events is not guaranteed to be sequential). The
     * runtime derives its streaming flag as an OR over all messages (SessionRuntime.reduce), and
     * the chat UI's STOP action is driven by that flag, so every streaming bubble must be cleared -
     * a single orphan left behind keeps the snapshot streaming forever and strands the user on STOP.
     *
     * Also settles what the bubble renders (see [closedByTurnClose]): chunks arrive as one segment
     * each ([appendText]), so this is where adjacent MESSAGE segments fold back into a single
     * markdown document and where the flat `content` view is derived. Both run once per turn,
     * which is the point - doing either per chunk is the O(n^2) copy that the segment-per-chunk
     * shape exists to avoid. [SessionRuntime.reduce] routes SessionLoadComplete through this method
     * too, which is what gives a hydrated transcript its content and its single-segment replies.
     */

    fun finishStreaming(messages: List<ChatMessage>): List<ChatMessage> {
        if (messages.none { it.needsTurnCloseRewrite() }) return messages
        return messages.map { message ->
            if (message.needsTurnCloseRewrite()) message.closedByTurnClose() else message
        }
    }

    /**
     * True when turn close must rewrite [message]: it is still streaming, its flat `content` view
     * has not been derived yet, or its reply chunks still stand as one segment each and need
     * folding into one MESSAGE segment (see [mergedMessageSegments]).
     */
    private fun ChatMessage.needsTurnCloseRewrite(): Boolean =
        isStreaming ||
            (
                role == ChatMessage.Role.ASSISTANT &&
                    (needsDerivedContent() || segments.hasAdjacentMessageSegments())
            )

    /**
     * Settles a bubble for rendering: clears the streaming flag, folds adjacent MESSAGE segments
     * into one markdown document per run, and derives the flat `content` view when it is still
     * empty. The fold does not change that view - it is the join of every MESSAGE segment's text in
     * order - so an already-derived `content` is carried through untouched.
     */
    private fun ChatMessage.closedByTurnClose(): ChatMessage {
        if (role != ChatMessage.Role.ASSISTANT) return copy(isStreaming = false)
        val merged = mergedMessageSegments(segments)
        return copy(
            isStreaming = false,
            segments = merged,
            content = if (content.isEmpty()) derivedContent(merged) else content,
        )
    }

    /**
     * Folds every run of adjacent MESSAGE segments into a single segment whose text is their
     * concatenation, keeping the run's first id so the key set is stable - the absorbed trailing
     * ids simply drop out. (The cached markdown entry is still re-parsed: its text no longer
     * matches the first chunk's.) Non-MESSAGE segments (thoughts, tool calls, plans) keep their
     * place and are never absorbed, so a THOUGHT between two runs leaves those runs separate.
     *
     * Linear in the run length: one StringBuilder per run, rather than one `text +` per segment
     * (which would be O(n^2) inside the run - the very cost the per-chunk shape avoids).
     */
    private fun mergedMessageSegments(segments: PersistentList<AssistantSegment>): PersistentList<AssistantSegment> {
        if (!segments.hasAdjacentMessageSegments()) return segments
        val merged = ArrayList<AssistantSegment>(segments.size)
        var index = 0
        while (index < segments.size) {
            val segment = segments[index]
            if (segment.kind != AssistantSegment.Kind.MESSAGE) {
                merged.add(segment)
                index++
                continue
            }
            val runText = StringBuilder(segment.text)
            index++
            while (index < segments.size && segments[index].kind == AssistantSegment.Kind.MESSAGE) {
                runText.append(segments[index].text)
                index++
            }
            merged.add(segment.copy(text = runText.toString()))
        }
        return merged.toPersistentList()
    }

    /** True when two MESSAGE segments sit next to each other - the shape a chunked stream leaves
     * behind, and the only shape [mergedMessageSegments] has to rewrite. */
    private fun List<AssistantSegment>.hasAdjacentMessageSegments(): Boolean =
        (1 until size).any { index ->
            this[index].kind == AssistantSegment.Kind.MESSAGE &&
                this[index - 1].kind == AssistantSegment.Kind.MESSAGE
        }

    /** Flat text view of the MESSAGE segments, in the order the agent emitted them. */
    private fun derivedContent(segments: List<AssistantSegment>): String =
        segments
            .filter { it.kind == AssistantSegment.Kind.MESSAGE }
            .joinToString("") { it.text }

    /** True when [this] is an assistant bubble whose flat `content` has not been derived yet.
     * Requires a MESSAGE segment specifically: a tool-call- or thought-only bubble derives
     * an empty string, so treating it as pending would make this never settle and re-derive
     * the whole list on every turn close. */
    private fun ChatMessage.needsDerivedContent(): Boolean =
        role == ChatMessage.Role.ASSISTANT &&
            content.isEmpty() &&
            segments.any { it.kind == AssistantSegment.Kind.MESSAGE }

    /** True when [lastMessage] is an empty streaming assistant placeholder whose preceding USER
     * message starts with or equals [text] (echo dedup on the server-echo path). */
    private fun isStreamingPlaceholderWithPreviousUser(
        lastMessage: ChatMessage?,
        previousMessage: ChatMessage?,
        text: String,
    ): Boolean =
        lastMessage?.role == ChatMessage.Role.ASSISTANT &&
            lastMessage.isStreaming &&
            lastMessage.content.isBlank() &&
            lastMessage.segments.isEmpty() &&
            previousMessage?.role == ChatMessage.Role.USER &&
            (previousMessage.content.startsWith(text) || previousMessage.content == text)

    /** True when [lastMessage] is an empty streaming assistant placeholder whose preceding USER
     * message content exactly matches [text] (echo dedup on the local-insertion path). */
    private fun isStreamingPlaceholderWithExactPreviousUser(
        lastMessage: ChatMessage?,
        previousMessage: ChatMessage?,
        text: String,
    ): Boolean =
        lastMessage?.role == ChatMessage.Role.ASSISTANT &&
            lastMessage.isStreaming &&
            lastMessage.content.isBlank() &&
            lastMessage.segments.isEmpty() &&
            previousMessage?.role == ChatMessage.Role.USER &&
            previousMessage.content == text
}

/**
 * Marks the last turn's PENDING / IN_PROGRESS tool calls FAILED. Once a turn has ended no
 * update can finish them, and a turn cut short (stopped, stream dropped, agent crashed) never
 * sends one, so left alone they would spin forever. A late update that does arrive still
 * carries its own status and overrides this.
 */
internal fun failUnfinishedToolCalls(messages: List<ChatMessage>): List<ChatMessage> {
    val turnStart = messages.indexOfLast { it.role == ChatMessage.Role.USER } + 1
    if ((turnStart until messages.size).none { messages[it].segments.any(AssistantSegment::isUnfinishedCall) }) {
        return messages
    }
    return messages.mapIndexed { index, message ->
        if (index < turnStart || message.segments.none(AssistantSegment::isUnfinishedCall)) {
            message
        } else {
            message.copy(
                segments =
                    message.segments
                        .map { segment ->
                            if (segment.isUnfinishedCall()) {
                                segment.copy(toolCall = segment.toolCall?.copy(status = ToolCallStatus.FAILED))
                            } else {
                                segment
                            }
                        }.toPersistentList(),
            )
        }
    }
}

private fun AssistantSegment.isUnfinishedCall(): Boolean =
    toolCall?.status == ToolCallStatus.PENDING || toolCall?.status == ToolCallStatus.IN_PROGRESS
