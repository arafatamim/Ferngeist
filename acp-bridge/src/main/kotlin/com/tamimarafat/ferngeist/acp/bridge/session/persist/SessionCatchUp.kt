package com.tamimarafat.ferngeist.acp.bridge.session.persist

import com.agentclientprotocol.annotations.UnstableApi
import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientOperationsFactory
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.AcpWithMeta
import com.agentclientprotocol.model.SessionId
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.rpc.MethodName
import com.tamimarafat.ferngeist.acp.bridge.session.AppSessionEvent
import com.tamimarafat.ferngeist.acp.bridge.session.CatchUpCursor
import com.tamimarafat.ferngeist.acp.bridge.session.SessionBridge
import com.tamimarafat.ferngeist.acp.bridge.session.beginHydration
import com.tamimarafat.ferngeist.acp.bridge.session.completeHydration
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// The gateway's catch-up wire contract: ferngeist-acp-gateway docs/api.md, "Catch-up resume".
private const val SEQ_KEY = "ferngeist/seq"
private const val CATCH_UP_KEY = "ferngeist/catchUp"

/** What the gateway reported after replaying the updates a catch-up resume missed. */
internal data class CatchUpReply(
    val epoch: String?,
    val turnActive: Boolean,
    val gap: Boolean,
)

/** The gateway's number for [update], or null for a frame it did not number (a direct agent). */
internal fun updateSeq(update: SessionUpdate): Long? =
    ((update as? AcpWithMeta)?._meta as? JsonObject)?.get(SEQ_KEY)?.jsonPrimitive?.longOrNull

/** `session/resume` params `_meta` asking the gateway for the updates after [CatchUpCursor.afterSeq]. */
internal fun CatchUpCursor.toResumeMeta(): JsonObject =
    buildJsonObject {
        putJsonObject(CATCH_UP_KEY) {
            put("epoch", epoch)
            put("afterSeq", afterSeq)
        }
    }

/** The catch-up block of a resume reply's `_meta`; an agent's own reply has none, which is a gap. */
private fun parseCatchUpReply(meta: JsonElement?): CatchUpReply {
    val block = (meta as? JsonObject)?.get(CATCH_UP_KEY) as? JsonObject
    return CatchUpReply(
        epoch = block?.get("epoch")?.jsonPrimitive?.contentOrNull,
        turnActive = block?.get("turnActive")?.jsonPrimitive?.booleanOrNull == true,
        gap = block?.get("gap")?.jsonPrimitive?.booleanOrNull != false,
    )
}

/** This new bridge holding [savedState]; a state that no longer decodes costs only a load. */
internal suspend fun SessionBridge.restoredFrom(
    savedState: String?,
    log: (String) -> Unit,
): SessionBridge {
    if (savedState == null) return this
    try {
        runtime.restore(savedState)
    } catch (error: SerializationException) {
        log("restore $sessionId: saved state unreadable: ${error.message}")
    }
    return this
}

/** A catch-up resume's SDK session and the gateway's report on it. */
internal class CaughtUp(
    val session: ClientSession,
    val reply: CatchUpReply,
)

/** The catch-up `session/resume` for [cursor]; null when it fails, so the caller attaches the old way. */
@OptIn(UnstableApi::class)
internal suspend fun Client.resumeCatchingUp(
    sessionId: String,
    cwd: String,
    cursor: CatchUpCursor,
    operationsFactory: ClientOperationsFactory,
    log: (String) -> Unit,
): CaughtUp? {
    var replyMeta: JsonElement? = null
    // The SDK hands the reply only to the operations factory, so it is read there.
    val readingReply =
        ClientOperationsFactory { id, response ->
            replyMeta = response._meta
            operationsFactory.createClientOperations(id, response)
        }
    val parameters = SessionCreationParameters(cwd = cwd, mcpServers = emptyList(), _meta = cursor.toResumeMeta())
    return runCatching { resumeSession(SessionId(sessionId), parameters, readingReply) }
        .onFailure { error ->
            if (error is CancellationException) throw error
            log("catch-up resume $sessionId failed: ${error.message}")
        }.getOrNull()
        ?.let { CaughtUp(it, parseCatchUpReply(replyMeta)) }
}

/**
 * Settles [bridge] once the gateway has replayed what it missed: follows the reply's epoch,
 * refills a gap with a load when the agent has one ([canLoad]; without it the bridge keeps its
 * state, as a plain resume would) and closes a turn that is no longer running.
 */
internal suspend fun Client.settleCatchUp(
    bridge: SessionBridge,
    reply: CatchUpReply,
    cwd: String,
    canLoad: Boolean,
) {
    reply.epoch?.let { bridge.runtime.catchUpFrom(it) }
    if (reply.gap && canLoad) loadInto(bridge, cwd)
    if (reply.gap || !reply.turnActive) bridge.emitEvent(AppSessionEvent.SessionLoadComplete)
}

/**
 * Refills a gap with a `session/load` replayed into [bridge]. Raw, because the SDK session the
 * catch-up resume registered keeps routing the replay to the bridge; a second SDK attach would not.
 */
private suspend fun Client.loadInto(
    bridge: SessionBridge,
    cwd: String,
) {
    bridge.beginHydration()
    val params =
        buildJsonObject {
            put("sessionId", bridge.sessionId)
            put("cwd", cwd)
            putJsonArray("mcpServers") {}
        }
    protocol.sendRequestRaw(MethodName("session/load"), params)
    bridge.completeHydration()
}
