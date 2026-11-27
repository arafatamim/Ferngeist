package com.tamimarafat.ferngeist.notification

import com.tamimarafat.ferngeist.core.model.LiveTurn
import com.tamimarafat.ferngeist.core.model.TurnStatus
import com.tamimarafat.ferngeist.core.model.chatIdFor

/** Reconciles drawer cards using elapsed time. All inputs are serialized by [ChatNotifications]. */
internal class ChatNotificationPolicy {
    private data class RemoteCard(
        val event: GatewayCardEvent,
        val expiresAt: Long,
        val shown: Boolean,
        val confirmed: Boolean = false,
    )

    private var turns = emptyMap<String, LiveTurn>()
    private var visible = emptySet<String>()
    private var coverage = emptySet<String>()
    private var inForeground = false
    private val remote = mutableMapOf<CardKey, RemoteCard>()
    private val recentlyEnded = mutableMapOf<ChatCardScope, Long>()
    private val consumedOutcomes = mutableMapOf<String, LiveTurn.OutcomeId>()
    private var maintenanceSequence = 0L

    fun updateLocal(
        turns: List<LiveTurn>,
        visibleChatIds: Set<String>,
        nowMs: Long,
        foreground: Boolean,
    ): List<CardChange> {
        prune(nowMs)
        val previousTurns = this.turns
        val previousVisible = visible
        this.turns = turns.associateBy { chatIdFor(it.presence.serverId, it.presence.sessionId) }
        visible = visibleChatIds
        inForeground = foreground
        consumedOutcomes.keys.retainAll(this.turns.keys)
        return buildList {
            clearVisibleChats(previousTurns, previousVisible)
            this@ChatNotificationPolicy.turns.forEach { (id, turn) ->
                val previous = previousTurns[id]
                val scope = ChatCardScope.of(turn.presence)
                val outcome = turn.outcomeId
                val newOutcome = outcome != null && consumedOutcomes.put(id, outcome) != outcome
                if (turn.running && (!previous.running || previous?.startMark != turn.startMark)) {
                    recentlyEnded.remove(scope)
                    clear(scope, Purpose.COMPLETION)
                    clear(scope, Purpose.FAILURE)
                }
                if (newOutcome) endTurn(scope, nowMs)
                if (scope is ChatCardScope.Local) reconcileManual(id, turn, previous, previousVisible, newOutcome)
            }
            clearWithdrawnApprovals(previousTurns)
            reconcileRemote(nowMs)
        }.distinct()
    }

    fun setLiveCoverage(
        chatIds: Set<String>,
        nowMs: Long,
    ): List<CardChange> {
        coverage = chatIds
        prune(nowMs)
        return buildList { reconcileRemote(nowMs) }
    }

    fun acceptGateway(
        event: GatewayCardEvent,
        nowMs: Long,
    ): List<CardChange> {
        prune(nowMs)
        val purpose = event.purpose
        if (purpose == Purpose.OTHER) {
            if (inForeground) return emptyList()
            val scope = ChatCardScope.Maintenance(event.gatewayId, nowMs, maintenanceSequence++)
            return listOf(CardChange.Post(CardKey(scope, purpose), event.target, purpose.quiet, gateway = event))
        }
        val scope = event.scope
        val key = CardKey(scope, purpose)
        val id = resolveChatId(event)
        val previous = remote[key]
        if (purpose == Purpose.PROGRESS && suppressProgress(scope, id, nowMs)) return emptyList()
        val duplicate = previous?.event == event
        return buildList {
            if (purpose.isTerminal) endTurn(scope, nowMs)
            if (!duplicate) recordGateway(key, event, id, nowMs, previous)
        }
    }

    private fun MutableList<CardChange>.recordGateway(
        key: CardKey,
        event: GatewayCardEvent,
        id: String?,
        nowMs: Long,
        previous: RemoteCard?,
    ) {
        key.purpose.replaces?.let { clear(key.scope, it) }
        val confirmed = key.purpose == Purpose.APPROVAL && turns[id]?.status == TurnStatus.NEEDS_ATTENTION
        val duration =
            when (key.purpose) {
                Purpose.PROGRESS -> PROGRESS_MS
                Purpose.APPROVAL -> APPROVAL_LIFETIME_MS
                else -> TERMINAL_MS
            }
        val expiresAt =
            if (key.purpose == Purpose.APPROVAL) previous?.expiresAt ?: nowMs + duration else nowMs + duration
        val card = RemoteCard(event, expiresAt, shown = id !in visible, confirmed)
        remote[key] = card
        if (card.shown) {
            val existingApproval = key.purpose == Purpose.APPROVAL && previous != null
            add(card.post(key, nowMs, silent = existingApproval))
        }
    }

    private fun MutableList<CardChange>.clearVisibleChats(
        previousTurns: Map<String, LiveTurn>,
        previousVisible: Set<String>,
    ) {
        for (id in visible) {
            val scope = turns[id]?.presence?.let(ChatCardScope::of) ?: ChatCardScope.Local(id)
            val previousScope = previousTurns[id]?.presence?.let(ChatCardScope::of)
            val gatewayIdentified = scope is ChatCardScope.Gateway && scope != previousScope
            if (id in previousVisible && !gatewayIdentified) continue
            if (id !in previousVisible) add(CardChange.CancelChat(id))
            add(CardChange.Cancel(CardKey(scope, Purpose.APPROVAL)))
            add(CardChange.Cancel(CardKey(scope, Purpose.FAILURE)))
            add(CardChange.Cancel(CardKey(scope, Purpose.COMPLETION)))
            if (scope is ChatCardScope.Gateway) add(CardChange.Cancel(CardKey(scope, Purpose.PROGRESS)))
        }
    }

    private fun MutableList<CardChange>.reconcileManual(
        id: String,
        turn: LiveTurn,
        previous: LiveTurn?,
        previousVisible: Set<String>,
        newOutcome: Boolean,
    ) {
        val scope = ChatCardScope.of(turn.presence)
        val wasPending = previous?.status == TurnStatus.NEEDS_ATTENTION
        val pending = turn.status == TurnStatus.NEEDS_ATTENTION
        if (wasPending && !pending && !newOutcome) clear(scope, Purpose.APPROVAL)
        if (id in visible) return
        val target = turn.presence.toChatDeepLinkTarget(turn.title.orEmpty())
        val pendingContinues = wasPending && previous.startMark == turn.startMark
        if (pending && (!pendingContinues || id in previousVisible)) {
            add(CardChange.Post(CardKey(scope, Purpose.APPROVAL), target, silent = wasPending))
        }
        if (newOutcome) {
            val purpose = if (turn.status == TurnStatus.FINISHED) Purpose.COMPLETION else Purpose.FAILURE
            purpose.replaces?.let { clear(scope, it) }
            add(CardChange.Post(CardKey(scope, purpose), target, purpose.quiet, timeoutMs = TERMINAL_MS))
        }
    }

    private fun MutableList<CardChange>.clearWithdrawnApprovals(previous: Map<String, LiveTurn>) {
        previous.forEach { (id, turn) ->
            if (turn.status == TurnStatus.NEEDS_ATTENTION && turn.presence.gatewaySourceId == null && id !in turns) {
                clear(ChatCardScope.of(turn.presence), Purpose.APPROVAL)
            }
        }
    }

    private fun MutableList<CardChange>.reconcileRemote(nowMs: Long) {
        remote.entries.toList().forEach { (key, card) ->
            val id = resolveChatId(card.event)
            when (key.purpose) {
                Purpose.APPROVAL -> reconcileRemoteApproval(key, card, id, nowMs)
                Purpose.PROGRESS -> {
                    if (id in visible || id in coverage) clear(key.scope, key.purpose)
                }
                else -> {
                    if (id in visible && card.shown) {
                        add(CardChange.Cancel(key))
                        remote[key] = card.copy(shown = false)
                    }
                }
            }
        }
    }

    private fun MutableList<CardChange>.reconcileRemoteApproval(
        key: CardKey,
        card: RemoteCard,
        id: String?,
        nowMs: Long,
    ) {
        val local = turns[id]
        val confirmed = local?.status == TurnStatus.NEEDS_ATTENTION
        val needsCard = id !in visible && (card.shown || confirmed)
        when {
            card.confirmed && local != null && !confirmed -> clear(key.scope, key.purpose)
            !confirmed && card.expiresAt <= nowMs -> clear(key.scope, key.purpose)
            else -> {
                val updated = card.copy(shown = needsCard, confirmed = confirmed)
                if (card.shown && !needsCard) add(CardChange.Cancel(key))
                if (needsCard && updated != card) add(updated.post(key, nowMs, silent = true))
                remote[key] = updated
            }
        }
    }

    private fun RemoteCard.post(
        key: CardKey,
        nowMs: Long,
        silent: Boolean,
    ): CardChange.Post =
        CardChange.Post(
            key,
            event.target,
            silent = silent || key.purpose.quiet,
            timeoutMs = if (confirmed) null else (expiresAt - nowMs).coerceAtLeast(1),
            gateway = event,
        )

    private fun MutableList<CardChange>.clear(
        scope: ChatCardScope,
        purpose: Purpose,
    ) {
        val key = CardKey(scope, purpose)
        remote.remove(key)
        add(CardChange.Cancel(key))
    }

    private fun MutableList<CardChange>.endTurn(
        scope: ChatCardScope,
        nowMs: Long,
    ) {
        recentlyEnded[scope] = nowMs + TERMINAL_PROGRESS_GUARD_MS
        clear(scope, Purpose.PROGRESS)
        clear(scope, Purpose.APPROVAL)
    }

    private fun resolveChatId(event: GatewayCardEvent): String? =
        event.target?.chatId ?: turns.entries.singleOrNull { ChatCardScope.of(it.value.presence) == event.scope }?.key

    private fun suppressProgress(
        scope: ChatCardScope,
        id: String?,
        nowMs: Long,
    ): Boolean = inForeground || id in coverage || (recentlyEnded[scope] ?: 0) > nowMs

    private fun prune(nowMs: Long) {
        recentlyEnded.values.removeAll { it <= nowMs }
        remote.values.removeAll { !it.confirmed && it.expiresAt <= nowMs }
    }

    private val LiveTurn?.running: Boolean
        get() = this?.status == TurnStatus.WORKING || this?.status == TurnStatus.NEEDS_ATTENTION

    private companion object {
        const val PROGRESS_MS = 120_000L
        const val TERMINAL_MS = 86_400_000L

        // Progress pushes are throttled server-side to one per ~15s per session, so one can
        // still arrive just after the turn ended.
        const val TERMINAL_PROGRESS_GUARD_MS = 15_000L
    }
}
