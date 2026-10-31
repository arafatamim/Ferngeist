package com.tamimarafat.ferngeist.core.model.repository

import com.tamimarafat.ferngeist.core.model.AgentQuota

interface ProviderQuotas {
    suspend fun fetch(serverId: String): List<AgentQuota>?
}
