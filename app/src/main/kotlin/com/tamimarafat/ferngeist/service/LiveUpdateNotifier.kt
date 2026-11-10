package com.tamimarafat.ferngeist.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.tamimarafat.ferngeist.MainActivity
import com.tamimarafat.ferngeist.R
import com.tamimarafat.ferngeist.acp.bridge.hub.ChatConnectionHub
import com.tamimarafat.ferngeist.core.model.ChatPresence
import com.tamimarafat.ferngeist.core.model.LiveTurn
import com.tamimarafat.ferngeist.core.model.TurnStatus
import com.tamimarafat.ferngeist.core.model.repository.ChatCatalog
import com.tamimarafat.ferngeist.core.model.repository.LaunchableTargetRepository
import com.tamimarafat.ferngeist.push.AppForegroundState
import com.tamimarafat.ferngeist.push.PUSH_CHANNEL_ALERTS_ID
import com.tamimarafat.ferngeist.push.ensurePushChannels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/**
 * The promoted ongoing notification that shows agent turns while the app is in the background.
 *
 * One agent with a plan gets a "2/5" step chip and a segmented bar, one agent without a plan gets
 * a chronometer, and several agents collapse into one aggregate whose blocked lines sort first.
 * Android 16 promotes it into the status-bar chip; older devices keep the same notification as a
 * plain ongoing one (the compat layer drops the promoted extras and the ProgressStyle).
 *
 * Dismissal is per turn: the delete intent records which turns the user swiped away, and no
 * update re-posts them until those turns end and a new one starts. Permission requests also get a
 * separate normal alert on the push Alerts channel, because a promoted chip is silent. Only
 * manual agents need that local alert: a gateway-backed chat is already alerted by the gateway's
 * push.
 */
internal class LiveUpdateNotifier(
    private val context: Context,
    private val hub: ChatConnectionHub,
    private val foregroundState: AppForegroundState,
    private val targets: LaunchableTargetRepository,
    private val catalog: ChatCatalog,
    private val scope: CoroutineScope,
) {
    private data class Inputs(
        val turns: List<LiveTurn>,
        val foreground: Boolean,
        val agentNames: Map<String, String>,
    )

    private val manager = context.getSystemService(NotificationManager::class.java)
    private var observation: Job? = null
    private val titles = mutableMapOf<String, String?>()
    private val dismissedTurns = mutableSetOf<String>()
    private val alerts = mutableMapOf<String, Int>()
    private var posted: LiveUpdateContent? = null
    private var previousTurns = emptyMap<String, LiveTurn>()
    private var completion: LiveUpdateContent.Completed? = null
    private var completionJob: Job? = null
    private val completionRevision = MutableStateFlow(0)

    fun start() {
        if (observation != null) return
        ensurePushChannels(context)
        createLiveChannel(context, manager)
        observation = scope.launch { observe() }
    }

    fun stop() {
        observation?.cancel()
        observation = null
        cancelAll()
        dismissedTurns.clear()
        titles.clear()
        previousTurns = emptyMap()
        clearCompletion()
    }

    /** The user swiped the promotion away: hold it until these turns end. */
    fun onDismissed() {
        dismissedTurns.addAll(hub.liveTurns.value.map(::turnKey))
        clearCompletion()
        cancelLive()
    }

    private suspend fun observe() {
        combine(
            hub.liveTurns,
            foregroundState.isForeground,
            targets.getTargets(),
            ticker(),
            completionRevision,
        ) { turns, foreground, allTargets, _, _ ->
            Inputs(turns, foreground, allTargets.associate { it.id to it.name })
        }.collectLatest(::render)
    }

    private suspend fun render(inputs: Inputs) {
        if (inputs.foreground) {
            cancelAll()
            previousTurns = emptyMap()
            clearCompletion()
            return
        }
        val running = inputs.turns.filter { it.status == TurnStatus.WORKING || it.status == TurnStatus.NEEDS_ATTENTION }
        updateCompletion(inputs.turns)
        if (running.isEmpty() && completion == null) {
            cancelAll()
            dismissedTurns.clear()
            titles.clear()
            return
        }
        val liveKeys = running.map(::turnKey)
        dismissedTurns.retainAll(liveKeys.toSet())
        val resolvedTitles = resolveTitles(running)
        reconcileAlerts(inputs, resolvedTitles)
        val content =
            if (completion != null) {
                completion
            } else if (liveKeys.all { it in dismissedTurns }) {
                null
            } else {
                liveUpdateContent(running, inputs.agentNames, resolvedTitles, System.currentTimeMillis())
            }
        if (content == null) {
            cancelLive()
        } else if (content != posted) {
            postLive(content)
        }
    }

    private fun updateCompletion(turns: List<LiveTurn>) {
        val current = turns.associateBy { titleKey(it.presence) }
        val completed =
            current.mapNotNull { (key, turn) ->
                val previous = previousTurns[key]
                turn.takeIf {
                    previous != null &&
                        previous.status in setOf(TurnStatus.WORKING, TurnStatus.NEEDS_ATTENTION) &&
                        turnKey(previous) !in dismissedTurns &&
                        it.status in setOf(TurnStatus.FINISHED, TurnStatus.ERROR)
                }
            }
        val started =
            current.any { (key, turn) ->
                turn.status in setOf(TurnStatus.WORKING, TurnStatus.NEEDS_ATTENTION) &&
                    (
                        previousTurns[key]?.status !in setOf(TurnStatus.WORKING, TurnStatus.NEEDS_ATTENTION) ||
                            previousTurns[key]?.let(::turnKey) != turnKey(turn)
                    )
            }
        previousTurns = current
        if (started) clearCompletion()
        if (completed.isEmpty()) return
        completion =
            LiveUpdateContent.Completed(
                done = (completion?.done ?: 0) + completed.count { it.status == TurnStatus.FINISHED },
                errors = (completion?.errors ?: 0) + completed.count { it.status == TurnStatus.ERROR },
            )
        completionJob?.cancel()
        completionJob =
            scope.launch {
                delay(COMPLETION_DISPLAY_MS)
                completion = null
                completionRevision.value++
            }
    }

    private fun clearCompletion() {
        completionJob?.cancel()
        completionJob = null
        completion = null
    }

    /** Titles come from the catalog once per session; a null is retried because agents title late. */
    private suspend fun resolveTitles(turns: List<LiveTurn>): Map<String, String?> {
        titles.keys.retainAll(turns.map { titleKey(it.presence) }.toSet())
        turns.forEach { turn ->
            val key = titleKey(turn.presence)
            if (titles[key] == null) {
                titles[key] = catalog.getSession(turn.presence.serverId, turn.presence.sessionId)?.title
            }
        }
        return titles
    }

    private fun reconcileAlerts(
        inputs: Inputs,
        titles: Map<String, String?>,
    ) {
        val blocked =
            inputs.turns.filter {
                it.status == TurnStatus.NEEDS_ATTENTION && it.presence.gatewaySourceId == null
            }
        val blockedKeys = blocked.mapTo(HashSet(), ::turnKey)
        alerts.keys.filterNot { it in blockedKeys }.forEach { key ->
            alerts.remove(key)?.let(manager::cancel)
        }
        blocked.filter { turnKey(it) !in alerts }.forEach { postAlert(it, inputs, titles) }
    }

    private fun postAlert(
        turn: LiveTurn,
        inputs: Inputs,
        titles: Map<String, String?>,
    ) {
        val id = alertId(turn.presence)
        alerts[turnKey(turn)] = id
        val labels = turn.labels(inputs.agentNames, titles)
        val contentIntent = chatIntent(context, turn.presence, ALERT_REQUEST_CODE_BASE + id)
        manager.notify(id, alertNotification(context, labels, contentIntent))
    }

    private fun cancelAll() {
        cancelLive()
        cancelAlerts()
    }

    private fun cancelAlerts() {
        alerts.values.forEach(manager::cancel)
        alerts.clear()
    }

    private fun cancelLive() {
        if (posted != null) manager.cancel(LIVE_NOTIFICATION_ID)
        posted = null
    }

    private fun postLive(content: LiveUpdateContent) {
        manager.notify(LIVE_NOTIFICATION_ID, buildLiveNotification(context, content))
        posted = content
    }
}

private const val LIVE_CHANNEL_ID = "ferngeist_live_update"

// Kept clear of the foreground (1) and error (2) notification ids.
private const val LIVE_NOTIFICATION_ID = 3
private const val ALERT_ID_BASE = 6000
private const val ALERT_REQUEST_CODE_BASE = 10_000
private const val ALERT_ID_RANGE = 1000
private const val TAP_REQUEST_CODE = 2
private const val DISMISS_REQUEST_CODE = 3
private const val ELAPSED_REFRESH_MS = 60_000L
private const val COMPLETION_DISPLAY_MS = 5_000L
private const val MAX_PROGRESS_SEGMENTS = 10
private const val MILLIS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3_600L
private const val LABEL_SEPARATOR = " · "

/** Localized copy the live notification needs, shaped so the renderer owns every string. */
private fun buildLiveNotification(
    context: Context,
    content: LiveUpdateContent,
): Notification {
    val builder =
        NotificationCompat
            .Builder(context, LIVE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.live_update_accent))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setRequestPromotedOngoing(true)
            .setContentIntent(tapIntent(context, content))
            .addAction(0, context.getString(R.string.live_update_open), tapIntent(context, content))
            .setDeleteIntent(dismissIntent(context))
    when (content) {
        is LiveUpdateContent.Completed -> {
            val label =
                listOfNotNull(
                    content.done.takeIf { it > 0 }?.let { context.getString(R.string.live_update_done, it) },
                    content.errors.takeIf { it > 0 }?.let { context.getString(R.string.live_update_errored, it) },
                ).joinToString(LABEL_SEPARATOR)
            builder.setContentTitle(label).setShortCriticalText(label)
        }
        is LiveUpdateContent.PlanStep -> applyPlan(context, builder, content)
        is LiveUpdateContent.Working -> applyWorking(context, builder, content)
        is LiveUpdateContent.Aggregate -> applyAggregate(context, builder, content)
    }
    return builder.build()
}

private fun applyPlan(
    context: Context,
    builder: NotificationCompat.Builder,
    content: LiveUpdateContent.PlanStep,
) {
    val accent = ContextCompat.getColor(context, R.color.live_update_accent)
    val segments =
        if (content.total <= MAX_PROGRESS_SEGMENTS) {
            List(content.total) { NotificationCompat.ProgressStyle.Segment(1).setColor(accent) }
        } else {
            // The platform merges more than ten segments anyway; one bar of the same total keeps
            // the tracker's position exact.
            listOf(NotificationCompat.ProgressStyle.Segment(content.total).setColor(accent))
        }
    builder
        .setContentTitle(content.step)
        .setContentText(
            context.getString(
                R.string.live_update_step_title,
                sessionLabel(context, content.turn),
                content.current,
                content.total,
            ),
        ).setShortCriticalText(context.getString(R.string.live_update_step_chip, content.current, content.total))
        .setStyle(
            NotificationCompat
                .ProgressStyle()
                .setProgress(content.completed)
                .setProgressSegments(segments),
        )
}

private fun applyWorking(
    context: Context,
    builder: NotificationCompat.Builder,
    content: LiveUpdateContent.Working,
) {
    val parts = listOfNotNull(content.activity ?: context.getString(R.string.live_update_working), content.branch)
    builder
        .setContentTitle(sessionLabel(context, content.turn))
        .setContentText(parts.joinToString(LABEL_SEPARATOR))
        .setStyle(
            NotificationCompat
                .ProgressStyle()
                .setProgressSegments(
                    listOf(
                        NotificationCompat.ProgressStyle
                            .Segment(1)
                            .setColor(ContextCompat.getColor(context, R.color.live_update_accent)),
                    ),
                ).setProgressIndeterminate(true),
        ).setWhen(content.startedAtEpochMs)
        .setShowWhen(true)
        .setUsesChronometer(true)
}

private fun applyAggregate(
    context: Context,
    builder: NotificationCompat.Builder,
    content: LiveUpdateContent.Aggregate,
) {
    val lines = content.lines.map { line -> formatLine(context, line) }
    builder
        .setContentTitle(
            context.getString(
                if (content.needsYou) R.string.live_update_needs_you else R.string.live_update_agents_working,
            ),
        ).setContentText(lines.firstOrNull())
        // BigTextStyle stays promotable; InboxStyle does not.
        .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
        .setShortCriticalText(
            if (content.needsYou) {
                context.getString(R.string.live_update_needs_you)
            } else {
                context.resources.getQuantityString(
                    R.plurals.live_update_busy_chip,
                    content.busyCount,
                    content.busyCount,
                )
            },
        )
}

private fun formatLine(
    context: Context,
    line: LiveUpdateContent.Aggregate.Line,
): String {
    val state =
        if (line.needsYou) {
            context.getString(R.string.live_update_needs_you)
        } else {
            formatElapsed(context, line.elapsedMs)
        }
    return context.getString(
        R.string.live_update_agent_line,
        agentLabel(context, line.turn),
        sessionLabel(context, line.turn),
        state,
    )
}

private fun formatElapsed(
    context: Context,
    elapsedMs: Long,
): String {
    val seconds = elapsedMs / MILLIS_PER_SECOND
    return when {
        seconds < SECONDS_PER_MINUTE -> context.getString(R.string.live_update_elapsed_seconds, seconds)
        seconds < SECONDS_PER_HOUR ->
            context.getString(
                R.string.live_update_elapsed_minutes,
                seconds / SECONDS_PER_MINUTE,
            )
        else ->
            context.getString(
                R.string.live_update_elapsed_hours,
                seconds / SECONDS_PER_HOUR,
                seconds % SECONDS_PER_HOUR / SECONDS_PER_MINUTE,
            )
    }
}

private fun agentLabel(
    context: Context,
    turn: LiveUpdateContent.TurnText,
): String = turn.agentName ?: context.getString(R.string.live_update_agent_fallback)

private fun sessionLabel(
    context: Context,
    turn: LiveUpdateContent.TurnText,
): String = turn.sessionTitle ?: turn.agentName ?: context.getString(R.string.app_untitled_session)

/** Several agents have no single chat to land on, so their tap opens the home screen. */
private fun tapIntent(
    context: Context,
    content: LiveUpdateContent,
): PendingIntent {
    val target =
        when (content) {
            is LiveUpdateContent.PlanStep -> content.turn.presence
            is LiveUpdateContent.Working -> content.turn.presence
            is LiveUpdateContent.Aggregate -> null
            is LiveUpdateContent.Completed -> null
        }
    return chatIntent(context, target, TAP_REQUEST_CODE)
}

/** The chat deep link both the live update and its permission alert open. */
private fun chatIntent(
    context: Context,
    target: ChatPresence?,
    requestCode: Int,
): PendingIntent {
    val intent =
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            target?.let { presence ->
                putExtra(FerngeistForegroundService.EXTRA_SERVER_ID, presence.serverId)
                putExtra(FerngeistForegroundService.EXTRA_SESSION_ID, presence.sessionId)
                putExtra(FerngeistForegroundService.EXTRA_CWD, presence.cwd)
                putExtra(FerngeistForegroundService.EXTRA_GATEWAY_ID, presence.gatewaySourceId)
            }
        }
    return PendingIntent.getActivity(
        context,
        requestCode,
        intent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

private fun dismissIntent(context: Context): PendingIntent =
    PendingIntent.getService(
        context,
        DISMISS_REQUEST_CODE,
        Intent(context, FerngeistForegroundService::class.java).apply {
            action = FerngeistForegroundService.ACTION_LIVE_DISMISSED
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

private fun alertId(presence: ChatPresence): Int =
    ALERT_ID_BASE + (presence.sessionId.hashCode() and Int.MAX_VALUE) % ALERT_ID_RANGE

// Each alert needs its own PendingIntent: FLAG_UPDATE_CURRENT would otherwise retarget every
// alert at the last chat posted.

private fun alertNotification(
    context: Context,
    labels: LiveUpdateContent.TurnText,
    contentIntent: PendingIntent,
): Notification =
    NotificationCompat
        .Builder(context, PUSH_CHANNEL_ALERTS_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(sessionLabel(context, labels))
        .setContentText(context.getString(R.string.live_update_needs_you_alert))
        .setAutoCancel(true)
        .setCategory(NotificationCompat.CATEGORY_REMINDER)
        .setContentIntent(contentIntent)
        .build()

private fun createLiveChannel(
    context: Context,
    manager: NotificationManager,
) {
    manager.createNotificationChannel(
        NotificationChannel(
            LIVE_CHANNEL_ID,
            context.getString(R.string.live_update_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.live_update_channel_description)
            setShowBadge(false)
        },
    )
}

/** Keeps the aggregate's per-agent elapsed time from going stale while nothing else changes. */
private fun ticker(): Flow<Unit> =
    flow {
        while (true) {
            emit(Unit)
            delay(ELAPSED_REFRESH_MS)
        }
    }
