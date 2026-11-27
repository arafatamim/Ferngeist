package com.tamimarafat.ferngeist.push

import com.tamimarafat.ferngeist.notification.Purpose

// Push categories the gateway may send (mirrors the server-side taxonomy). Urgent ones
// route to the heads-up Alerts channel; everything else stays quiet on Updates.
const val PUSH_CATEGORY_PERMISSION_REQUEST = "permission_request"
const val PUSH_CATEGORY_AGENT_ERROR = "agent_error"
const val PUSH_CATEGORY_AGENT_CRASH = "agent_crash"
const val PUSH_CATEGORY_PROGRESS = "progress"
const val PUSH_CATEGORY_TURN_COMPLETE = "turn_complete"

/** The card a gateway category becomes; any other category is a gateway maintenance message. */
internal fun cardPurposeOf(category: String?): Purpose =
    when (category) {
        PUSH_CATEGORY_PROGRESS -> Purpose.PROGRESS
        PUSH_CATEGORY_PERMISSION_REQUEST -> Purpose.APPROVAL
        PUSH_CATEGORY_AGENT_ERROR, PUSH_CATEGORY_AGENT_CRASH -> Purpose.FAILURE
        PUSH_CATEGORY_TURN_COMPLETE -> Purpose.COMPLETION
        else -> Purpose.OTHER
    }
