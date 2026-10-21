package com.tamimarafat.ferngeist.feature.sessionlist.worktree

import java.util.UUID

private const val SUFFIX_LENGTH = 8

/**
 * A branch name for a new worktree, shaped like the gateway's own default (`ferngeist/<8 hex>`)
 * so the form can show it before the chat opens instead of leaving the field blank.
 */
internal fun newWorktreeBranchName(): String = "ferngeist/" + UUID.randomUUID().toString().take(SUFFIX_LENGTH)
