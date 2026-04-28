package com.example.untiswecker

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

class UntisClient(private val server: String, private val school: String) {
    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                coerceInputValues = true
            })
        }
    }

    private val url = "https://$server/WebUntis/jsonrpc.do?school=$school"

    suspend fun authenticate(user: String, pass: String): UntisResponse<LoginResult> {
        val request = UntisRequest(
            id = "1",
            method = "authenticate",
            params = mapOf(
                "user" to user,
                "password" to pass,
                "client" to "UntisWecker"
            )
        )

        return try {
            client.post(url) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }.body()
        } catch (e: Exception) {
            UntisResponse(id = "1", error = UntisError(code = -1, message = e.message ?: "Unknown error"))
        }
    }
}
