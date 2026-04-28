package com.example.untiswecker

import kotlinx.serialization.Serializable

@Serializable
data class UntisRequest(
    val id: String,
    val method: String,
    val params: Map<String, String>,
    val jsonrpc: String = "2.0"
)

@Serializable
data class UntisResponse<T>(
    val id: String,
    val result: T? = null,
    val error: UntisError? = null,
    val jsonrpc: String = "2.0"
)

@Serializable
data class UntisError(
    val code: Int,
    val message: String
)

@Serializable
data class LoginResult(
    val sessionId: String,
    val personType: Int,
    val personId: Int,
    val klasseId: Int? = null
)
