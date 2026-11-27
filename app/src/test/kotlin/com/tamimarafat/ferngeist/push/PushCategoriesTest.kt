package com.tamimarafat.ferngeist.push

import com.tamimarafat.ferngeist.notification.Purpose
import org.junit.Assert.assertEquals
import org.junit.Test

class PushCategoriesTest {
    // Literal wire values: the gateway's category strings are the contract, not our constants.
    @Test
    fun cardPurposeOf_gatewayCategories_mapToCardPurposes() {
        assertEquals(Purpose.PROGRESS, cardPurposeOf("progress"))
        assertEquals(Purpose.APPROVAL, cardPurposeOf("permission_request"))
        assertEquals(Purpose.FAILURE, cardPurposeOf("agent_error"))
        assertEquals(Purpose.FAILURE, cardPurposeOf("agent_crash"))
        assertEquals(Purpose.COMPLETION, cardPurposeOf("turn_complete"))
    }

    @Test
    fun cardPurposeOf_maintenanceOrMissingCategory_isOther() {
        assertEquals(Purpose.OTHER, cardPurposeOf("gateway_update"))
        assertEquals(Purpose.OTHER, cardPurposeOf(null))
    }
}
