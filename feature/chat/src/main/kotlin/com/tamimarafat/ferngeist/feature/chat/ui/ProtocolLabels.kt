package com.tamimarafat.ferngeist.feature.chat.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.agentclientprotocol.model.ToolCallStatus
import com.agentclientprotocol.model.ToolKind
import com.tamimarafat.ferngeist.feature.chat.R

@Composable
internal fun toolKindLabel(kind: ToolKind): String =
    when (kind) {
        ToolKind.READ -> stringResource(R.string.chat_tool_kind_read)
        ToolKind.EDIT -> stringResource(R.string.chat_tool_kind_edit)
        ToolKind.DELETE -> stringResource(R.string.chat_tool_kind_delete)
        ToolKind.MOVE -> stringResource(R.string.chat_tool_kind_move)
        ToolKind.SEARCH -> stringResource(R.string.chat_tool_kind_search)
        ToolKind.EXECUTE -> stringResource(R.string.chat_tool_kind_execute)
        ToolKind.THINK -> stringResource(R.string.chat_tool_kind_think)
        ToolKind.FETCH -> stringResource(R.string.chat_tool_kind_fetch)
        ToolKind.SWITCH_MODE -> stringResource(R.string.chat_tool_kind_switch_mode)
        ToolKind.OTHER -> stringResource(R.string.chat_tool_kind_other)
    }

@Composable
internal fun toolCallStatusLabel(status: ToolCallStatus): String =
    when (status) {
        ToolCallStatus.PENDING -> stringResource(R.string.chat_tool_status_pending)
        ToolCallStatus.IN_PROGRESS -> stringResource(R.string.chat_tool_status_in_progress)
        ToolCallStatus.COMPLETED -> stringResource(R.string.chat_tool_status_completed)
        ToolCallStatus.FAILED -> stringResource(R.string.chat_tool_status_failed)
    }

@Composable
internal fun permissionKindLabel(kind: String): String =
    when (kind) {
        "allow_once" -> stringResource(R.string.chat_permission_kind_allow_once)
        "allow_always" -> stringResource(R.string.chat_permission_kind_allow_always)
        "reject_once" -> stringResource(R.string.chat_permission_kind_reject_once)
        "reject_always" -> stringResource(R.string.chat_permission_kind_reject_always)
        else -> kind.replace('_', ' ')
    }
