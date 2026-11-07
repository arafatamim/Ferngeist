package com.tamimarafat.ferngeist.core.model.auth

/** Authentication choices rendered independently of the agent protocol. */
data class AuthenticationMethod(
    val id: String,
    val name: String,
    val description: String?,
    val type: String,
    val envVars: List<AuthenticationEnvVar> = emptyList(),
    val link: String? = null,
    val args: List<String> = emptyList(),
)

data class AuthenticationEnvVar(
    val name: String,
    val label: String?,
    val secret: Boolean,
    val optional: Boolean,
)

data class AuthenticationRequest(
    val agentName: String,
    val methods: List<AuthenticationMethod>,
    val errorMessage: String? = null,
    val isAuthenticating: Boolean = false,
)
