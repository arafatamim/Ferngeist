package com.tamimarafat.ferngeist.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.tamimarafat.ferngeist.AppForegroundState
import com.tamimarafat.ferngeist.R
import com.tamimarafat.ferngeist.acp.bridge.hub.ChatConnectionHub
import com.tamimarafat.ferngeist.core.model.LiveTurn
import com.tamimarafat.ferngeist.core.model.TurnStatus
import com.tamimarafat.ferngeist.core.model.repository.ChatCatalog
import com.tamimarafat.ferngeist.core.model.repository.LaunchableTargetRepository
import com.tamimarafat.ferngeist.notification.ChatNotifications
import com.tamimarafat.ferngeist.notification.chatPendingIntent
import com.tamimarafat.ferngeist.notification.toChatDeepLinkTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
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
 * update re-posts them until those turns end and a new one starts. The chip stays silent;
 * actionable requests are handled separately by [ChatNotifications].
 */
internal class LiveUpdateNotifier(
    private val context: Context,
    private val hub: ChatConnectionHub,
    private val foregroundState: AppForegroundState,
    private val targets: LaunchableTargetRepository,
    private val catalog: ChatCatalog,
    private val chatNotifications: ChatNotifications,
) {
    private data class Inputs(
        val turns: List<LiveTurn>,
        val foreground: Boolean,
        val agentNames: Map<String, String>,
    )

    private val manager = context.getSystemService(NotificationManager::class.java)
    private var observation: Job? = null
    private val titles = mutableMapOf<String, String?>()
    private val dismissedTurns = mutableSetOf<LiveTurnKey>()
    private var postedTurns = emptySet<LiveTurnKey>()
    private var posted: LiveUpdateContent? = null

    fun start(scope: CoroutineScope) {
        if (observation != null) return
        createLiveChannel(context, manager)
        observation = scope.launch { observe() }
    }

    fun stop() {
        observation?.cancel()
        observation = null
        cancelLive()
        dismissedTurns.clear()
        titles.clear()
        chatNotifications.setLiveCoverage(emptySet())
    }

    /** The user swiped the promotion away: hold it until these turns end. */
    fun onDismissed() {
        dismissedTurns.addAll(postedTurns)
        cancelLive()
    }

    private suspend fun observe() {
        combine(
            hub.liveTurns,
            foregroundState.isForeground,
            targets.getTargets(),
            ticker(),
        ) { turns, foreground, allTargets, _ ->
            Inputs(turns, foreground, allTargets.associate { it.id to it.name })
        }.collectLatest(::render)
    }

    private suspend fun render(inputs: Inputs) {
        val active = inputs.turns.filter { it.status == TurnStatus.WORKING || it.status == TurnStatus.NEEDS_ATTENTION }
        val activeKeys = active.mapTo(mutableSetOf(), ::turnKey)
        val activeChatIds = activeKeys.mapTo(mutableSetOf()) { it.chatId }
        dismissedTurns.retainActiveDismissals(activeKeys)
        // An attached, paused turn still suppresses gateway progress while its approval is pending.
        chatNotifications.setLiveCoverage(if (inputs.foreground) emptySet() else activeChatIds)
        val running = active.filter { it.status == TurnStatus.WORKING }
        if (inputs.foreground || running.isEmpty()) {
            cancelLive()
            titles.clear()
            return
        }
        val resolvedTitles = resolveTitles(running)
        val content =
            liveUpdateContent(running, inputs.agentNames, resolvedTitles, System.currentTimeMillis(), dismissedTurns)
        val shownKeys = running.mapTo(mutableSetOf(), ::turnKey).apply { removeAll(dismissedTurns::dismisses) }
        if (content == null) {
            cancelLive()
        } else if (content != posted || postedTurns != shownKeys) {
            manager.notify(LIVE_NOTIFICATION_ID, buildLiveNotification(context, content))
            posted = content
            postedTurns = shownKeys
        }
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

    private fun cancelLive() {
        if (posted != null) manager.cancel(LIVE_NOTIFICATION_ID)
        posted = null
        postedTurns = emptySet()
    }
}

private const val LIVE_CHANNEL_ID = "ferngeist_live_update"

// Kept clear of the foreground (1) and error (2) notification ids.
private const val LIVE_NOTIFICATION_ID = 3
private const val DISMISS_REQUEST_CODE = 3
private const val ELAPSED_REFRESH_MS = 60_000L
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
        .setContentTitle(context.getString(R.string.live_update_agents_working))
        .setContentText(lines.firstOrNull())
        // BigTextStyle stays promotable; InboxStyle does not.
        .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
        .setShortCriticalText(
            context.resources.getQuantityString(R.plurals.live_update_busy_chip, lines.size, lines.size),
        )
}

private fun formatLine(
    context: Context,
    line: LiveUpdateContent.Aggregate.Line,
): String =
    context.getString(
        R.string.live_update_agent_line,
        agentLabel(context, line.turn),
        sessionLabel(context, line.turn),
        formatElapsed(context, line.elapsedMs),
    )

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
    val turn =
        when (content) {
            is LiveUpdateContent.PlanStep -> content.turn
            is LiveUpdateContent.Working -> content.turn
            is LiveUpdateContent.Aggregate -> null
        }
    val target = turn?.let { it.presence.toChatDeepLinkTarget(it.sessionTitle.orEmpty()) }
    return chatPendingIntent(context, target, "live-update")
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
