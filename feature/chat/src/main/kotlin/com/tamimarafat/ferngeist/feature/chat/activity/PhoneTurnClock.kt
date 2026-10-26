package com.tamimarafat.ferngeist.feature.chat.activity

import java.util.concurrent.ConcurrentHashMap

/** The last phone-origin prompt echo, in process memory; replayed timings never imply ownership. */
internal object PhoneTurnClock {
    private val turns = ConcurrentHashMap<Pair<String, String>, Long>()

    fun record(
        serverId: String,
        sessionId: String,
        updatedAt: Long,
    ) {
        turns.merge(serverId to sessionId, updatedAt, ::maxOf)
    }

    fun lastTurn(
        serverId: String,
        sessionId: String,
    ): Long? = turns[serverId to sessionId]
}
