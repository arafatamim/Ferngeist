package com.tamimarafat.ferngeist.acp.bridge.facade

import com.tamimarafat.ferngeist.core.model.GitWorkspaceReader
import com.tamimarafat.ferngeist.core.model.git.ProjectGitReaderFactory
import com.tamimarafat.ferngeist.core.model.git.ProjectGitTarget
import com.tamimarafat.ferngeist.gateway.GatewayRepository
import javax.inject.Inject

class GatewayProjectGitReaderFactory
    @Inject
    constructor(
        private val repository: GatewayRepository,
    ) : ProjectGitReaderFactory {
        override fun create(target: ProjectGitTarget): GitWorkspaceReader = GatewayProjectGitReader(repository, target)
    }
