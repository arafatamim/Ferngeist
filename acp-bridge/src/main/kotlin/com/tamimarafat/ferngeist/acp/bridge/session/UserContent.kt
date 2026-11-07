package com.tamimarafat.ferngeist.acp.bridge.session

import com.tamimarafat.ferngeist.core.model.ChatFileData
import com.tamimarafat.ferngeist.core.model.ChatImageData

/**
 * The content one user message carries: its text plus any attached images and files, and the
 * client's id for the prompt when the gateway tagged its echo with one.
 */
internal data class UserContent(
    val text: String,
    val images: List<ChatImageData>,
    val files: List<ChatFileData>,
    val clientId: String? = null,
    val messageId: String? = null,
)
