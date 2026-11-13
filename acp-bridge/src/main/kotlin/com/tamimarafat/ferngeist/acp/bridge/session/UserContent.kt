package com.tamimarafat.ferngeist.acp.bridge.session

import com.tamimarafat.ferngeist.core.model.ChatFileData
import com.tamimarafat.ferngeist.core.model.ChatImageData
import com.tamimarafat.ferngeist.core.model.ChatResourceLink

/**
 * The content one user message carries: its text plus any attached images, files and linked host
 * files, and the
 * client's id for the prompt when the gateway tagged its echo with one.
 */
internal data class UserContent(
    val text: String,
    val images: List<ChatImageData>,
    val files: List<ChatFileData>,
    val clientId: String? = null,
    val messageId: String? = null,
    val links: List<ChatResourceLink> = emptyList(),
)

internal val UserContent.hasAttachments: Boolean
    get() = images.isNotEmpty() || files.isNotEmpty() || links.isNotEmpty()
