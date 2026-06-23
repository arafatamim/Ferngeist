package com.tamimarafat.ferngeist.core.model

import java.util.UUID

/**
 * A launchable provider on a [PaseoSource].
 *
 * A Paseo daemon hosts multiple providers (claude, codex, copilot, opencode,
 * …), so each binding pins one provider + working directory on a source,
 * analogous to [GatewayAgentBinding] on a [GatewaySource].
 *
 * @property provider Paseo provider id, e.g. `claude`, `codex`.
 * @property cwd working directory the agent session runs in.
 * @property preferredModelId optional model id pre-selected on create.
 */
data class PaseoAgentBinding(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val paseoSourceId: String,
    val provider: String,
    val cwd: String,
    val preferredModelId: String? = null,
    val preferredAuthMethodId: String? = null,
)
