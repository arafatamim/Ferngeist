package com.tamimarafat.ferngeist.acp.bridge.session

import com.tamimarafat.ferngeist.core.model.ChatFileData
import com.tamimarafat.ferngeist.core.model.ChatImageData

/** The content one user message carries: its text plus any attached images and files. */
internal data class UserContent(
    val text: String,
    val images: List<ChatImageData>,
    val files: List<ChatFileData>,
)
