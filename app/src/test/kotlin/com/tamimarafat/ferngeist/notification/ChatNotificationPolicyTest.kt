package com.tamimarafat.ferngeist.notification

import com.tamimarafat.ferngeist.core.model.ChatPresence
import com.tamimarafat.ferngeist.core.model.LiveTurn
import com.tamimarafat.ferngeist.core.model.TurnStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatNotificationPolicyTest {
    private val policy = ChatNotificationPolicy()

    @Test
    fun completion_visiblePane_deliversOnlyUnattendedChat() {
        val changes =
            policy.updateLocal(
                listOf(done("b"), done("c")),
                setOf("local/a", "local/b"),
                0,
                foreground = true,
            )
        val post = changes.filterIsInstance<CardChange.Post>().single()
        assertEquals("c", post.target?.sessionId)
        assertTrue(post.silent)
        assertEquals(Purpose.COMPLETION, post.key.purpose)
    }

    @Test
    fun completion_newOutcome_replacesCardAndOpeningConsumesIt() {
        val original = policy.updateLocal(listOf(done("c")), emptySet(), 0, foreground = false).post()
        val latest = done("c", 2)
        assertEquals(original.key, policy.updateLocal(listOf(latest), emptySet(), 1, foreground = false).post().key)
        val opened = policy.updateLocal(listOf(latest), setOf("local/c"), 2, foreground = true)
        assertTrue(CardChange.Cancel(original.key) in opened)
        assertTrue(policy.updateLocal(listOf(latest), emptySet(), 3, foreground = false).isEmpty())
    }

    @Test
    fun approval_sameRequest_alertsOnce() {
        val first = policy.updateLocal(listOf(pending()), emptySet(), 0, foreground = false).post()
        assertEquals(false, first.silent)
        assertTrue(policy.updateLocal(listOf(pending()), emptySet(), 1, foreground = false).isEmpty())
    }

    @Test
    fun approval_sameRequestAfterLeavingChat_returnsSilently() {
        val first = policy.updateLocal(listOf(pending()), emptySet(), 0, foreground = false).post()
        val opened = policy.updateLocal(listOf(pending()), setOf("local/a"), 1, foreground = true)
        assertTrue(CardChange.Cancel(first.key) in opened)
        assertTrue(policy.updateLocal(listOf(pending()), emptySet(), 2, foreground = false).post().silent)
    }

    @Test
    fun approval_resolvedLocally_cancelsCard() {
        val first = policy.updateLocal(listOf(pending()), emptySet(), 0, foreground = false).post()
        val working = pending().copy(status = TurnStatus.WORKING)
        assertTrue(
            CardChange.Cancel(first.key) in policy.updateLocal(listOf(working), emptySet(), 1, foreground = false),
        )
    }

    @Test
    fun visibility_chatOpens_cancelsCardsAnEarlierProcessPosted() {
        val opened = policy.updateLocal(emptyList(), setOf("local/a"), 0, foreground = true)
        assertTrue(CardChange.CancelChat("local/a") in opened)
        assertTrue(policy.updateLocal(emptyList(), setOf("local/a"), 1, foreground = true).isEmpty())
    }

    @Test
    fun unknown_localState_hasNoOutcomeCard() {
        val unknown = LiveTurn(ChatPresence("local", "a"), TurnStatus.UNKNOWN)
        assertTrue(policy.updateLocal(listOf(unknown), emptySet(), 0, foreground = false).isEmpty())
    }

    @Test
    fun progress_gatewayTerminal_cancelsFallbackAndAllowsProgressAfterGuard() {
        val progress = push(Purpose.PROGRESS)
        val first = policy.acceptGateway(progress, 0).post()
        assertEquals(120_000L, first.timeoutMs)
        assertTrue(first.silent)
        assertEquals(first.key, policy.acceptGateway(progress.copy(body = "Editing"), 100).post().key)
        val ended = policy.acceptGateway(push(Purpose.COMPLETION), 200)
        assertTrue(CardChange.Cancel(first.key) in ended)
        assertTrue(ended.post().silent)
        assertTrue(policy.acceptGateway(progress, 14_200).isEmpty())
        assertEquals(first.key, policy.acceptGateway(progress, 15_200).post().key)
    }

    @Test
    fun completion_repeatedAfterFallback_clearsProgressWithoutRepostingCompletion() {
        val completion = push(Purpose.COMPLETION)
        policy.acceptGateway(completion, 0)
        val fallback = policy.acceptGateway(push(Purpose.PROGRESS), 16_000).post()

        val repeated = policy.acceptGateway(completion, 20_000)

        assertTrue(CardChange.Cancel(fallback.key) in repeated)
        assertTrue(repeated.none { it is CardChange.Post })
    }

    @Test
    fun progress_sameSessionAcrossGateways_clearsOnlyTheMatchedGateway() {
        val event = push(Purpose.PROGRESS)
        val a = policy.acceptGateway(event, 0).post()
        val b = policy.acceptGateway(event.copy(gatewayId = "other", target = null), 0).post()
        assertNotEquals(a.key.tag, b.key.tag)
        val terminal = policy.acceptGateway(push(Purpose.COMPLETION), 100)
        assertTrue(CardChange.Cancel(a.key) in terminal)
        assertTrue(CardChange.Cancel(b.key) !in terminal)
    }

    @Test
    fun progress_locallyCovered_cancelsFallbackAndSuppressesFurtherPushes() {
        val event = push(Purpose.PROGRESS)
        val posted = policy.acceptGateway(event, 0).post()
        assertTrue(CardChange.Cancel(posted.key) in policy.setLiveCoverage(setOf("local/a"), 1))
        assertTrue(policy.acceptGateway(event.copy(body = "Editing"), 2).isEmpty())
    }

    @Test
    fun visibility_gatewayIdentityArrivesAfterChatOpens_clearsPreviouslyPostedStableKey() {
        val previousProcess = ChatNotificationPolicy()
        val oldCard =
            previousProcess
                .acceptGateway(
                    push(Purpose.APPROVAL).copy(target = null),
                    0,
                ).post()
        policy.updateLocal(emptyList(), setOf("local/a"), 1, foreground = true)

        val attached = LiveTurn(ChatPresence("local", "a", gatewaySourceId = "gateway"), TurnStatus.UNKNOWN)
        val changes = policy.updateLocal(listOf(attached), setOf("local/a"), 2, foreground = true)

        assertTrue(CardChange.Cancel(oldCard.key) in changes)
    }

    @Test
    fun approval_duplicateGatewayPush_preservesOriginalExpiry() {
        val event = push(Purpose.APPROVAL).copy(target = null)
        val posted = policy.acceptGateway(event, 0).post()
        assertEquals(3_600_000L, posted.timeoutMs)
        assertEquals(null, posted.target)
        assertEquals(false, posted.silent)
        assertTrue(policy.acceptGateway(event, 3_599_999).isEmpty())
        assertEquals(posted.key, policy.acceptGateway(event, 3_600_000).post().key)
    }

    @Test
    fun approval_observedLocalResolution_removesGatewayCard() {
        val posted = policy.acceptGateway(push(Purpose.APPROVAL), 0).post()
        val pending = LiveTurn(ChatPresence("local", "a", gatewaySourceId = "gateway"), TurnStatus.NEEDS_ATTENTION)
        policy.updateLocal(listOf(pending), emptySet(), 1, foreground = false)
        val working = pending.copy(status = TurnStatus.WORKING)
        assertTrue(
            CardChange.Cancel(posted.key) in policy.updateLocal(listOf(working), emptySet(), 2, foreground = false),
        )
    }

    @Test
    fun failure_afterPreviousFailureWasSeen_alertsForTheNewFailure() {
        val event = push(Purpose.FAILURE)
        policy.updateLocal(emptyList(), setOf("local/a"), 0, foreground = true)
        assertTrue(policy.acceptGateway(event, 1).none { it is CardChange.Post })
        policy.updateLocal(emptyList(), emptySet(), 2, foreground = false)

        val newFailure = policy.acceptGateway(event.copy(body = "Agent crashed"), 3).post()
        assertEquals(false, newFailure.silent)
    }

    @Test
    fun maintenance_foreground_preservesExistingSuppression() {
        val event = push(Purpose.OTHER).copy(sessionId = null, target = null)
        assertEquals(
            Purpose.OTHER,
            policy
                .acceptGateway(event, 0)
                .post()
                .key.purpose,
        )
        policy.updateLocal(emptyList(), emptySet(), 1, foreground = true)
        assertTrue(policy.acceptGateway(event, 2).isEmpty())
    }

    private fun pending() = LiveTurn(ChatPresence("local", "a"), TurnStatus.NEEDS_ATTENTION)

    private fun done(
        session: String,
        sequence: Long = 1,
    ) = LiveTurn(ChatPresence("local", session), TurnStatus.FINISHED, outcomeId = LiveTurn.OutcomeId(1, sequence))

    private fun push(purpose: Purpose) =
        GatewayCardEvent(
            gatewayId = "gateway",
            sessionId = "a",
            target = ChatDeepLinkTarget("local", "a", "", "", "gateway"),
            purpose = purpose,
            title = "Agent",
            body = "Working",
            branch = null,
        )

    private fun List<CardChange>.post() = filterIsInstance<CardChange.Post>().single()
}
