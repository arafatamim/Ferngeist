package com.tamimarafat.ferngeist.feature.chat.pending

import com.tamimarafat.ferngeist.core.model.ChatMessage
import com.tamimarafat.ferngeist.core.model.MessageDeliveryStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class PendingEchoesTest {
    private fun sending(clientId: String) =
        ChatMessage(
            id = clientId,
            role = ChatMessage.Role.USER,
            content = "continue",
            status = MessageDeliveryStatus.SENDING,
            clientId = clientId,
        )

    @Test
    fun `an echo delivers only the identical prompt it names`() {
        val first = sending("c1")
        val second = sending("c2")
        val echo = ChatMessage(id = "echo", role = ChatMessage.Role.USER, content = "continue", clientId = "c2")

        val (stillPending, delivered) = reconcileEchoes(listOf(first, second), listOf(echo))

        assertEquals(listOf(first), stillPending)
        assertEquals(mapOf("echo" to "c2"), delivered)
    }
}
