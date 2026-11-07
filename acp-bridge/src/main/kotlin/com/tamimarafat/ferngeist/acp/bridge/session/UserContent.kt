package com.tamimarafat.ferngeist.acp.bridge.session

import com.agentclientprotocol.model.ContentBlock
import com.tamimarafat.ferngeist.core.model.ChatFileData
import com.tamimarafat.ferngeist.core.model.ChatImageData
import com.tamimarafat.ferngeist.core.model.userContentBlocks

/**
 * The content one user message carries: its ordered ACP blocks, the flattened text used for
 * matching and quotes, and the client's id for the prompt when the gateway tagged its echo.
 */
internal data class UserContent(
    val text: String = "",
    val blocks: List<ContentBlock> = emptyList(),
    val clientId: String? = null,
    val messageId: String? = null,
) {
    fun isEmpty(): Boolean = text.isEmpty() && blocks.isEmpty()

    /** True when the chunk carries something beyond the prompt text, so text alone cannot dedup it. */
    fun hasAttachmentBlocks(): Boolean = blocks.any { it !is ContentBlock.Text }

    companion object {
        /** A locally sent prompt, whose attachments the composer holds as images and files. */
        fun from(
            text: String,
            images: List<ChatImageData>,
            files: List<ChatFileData>,
            clientId: String? = null,
        ): UserContent =
            UserContent(
                text = text,
                blocks = userContentBlocks(text, images, files),
                clientId = clientId,
            )
    }
}
