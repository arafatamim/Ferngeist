package com.tamimarafat.ferngeist.acp.bridge.session

import com.agentclientprotocol.model.ContentBlock

/** Ordered content and the identity the gateway attaches to its prompt echo. */
internal data class UserContent(
    val blocks: List<ContentBlock>,
    val clientId: String? = null,
    val messageId: String? = null,
)

internal val UserContent.text: String
    get() = blocks.filterIsInstance<ContentBlock.Text>().joinToString("") { it.text }

internal val UserContent.hasAttachments: Boolean
    get() = blocks.any { it !is ContentBlock.Text }
