package com.example.untiswecker

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

class UntisClient(private val server: String, private val school: String) {
    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            val jsonConfig = Json {
                ignoreUnknownKeys = true
                coerceInputValues = true
                encodeDefaults = true
            }
            json(jsonConfig, contentType = ContentType.parse("application/json-rpc"))
            json(jsonConfig)
        }
        install(Logging) {
            logger = object : Logger {
                override fun log(message: String) {
                    val scrubbed = message.replace(Regex("\"(password|otp|key)\":\"[^\"]*\""), "\"$1\":\"***\"")
                    Log.d("UntisClient", scrubbed)
                }
            }
            level = LogLevel.ALL
        }
        install(DefaultRequest) {
            header("User-Agent", "Untis Mobile")
        }
    }

    private val url = "https://$server/WebUntis/jsonrpc.do?school=$school"
    private val internUrl = "https://$server/WebUntis/jsonrpc_intern.do?m=getUserData2017&school=$school&v=i2.2"
    private val mobileAuthUrl = "https://$server/WebUntis/api/mobile/v2/$school/authentication"
    private var sessionId: String? = null

    suspend fun authenticate(user: String, pass: String): UntisResponse<LoginResult> {
        val methods = mutableListOf<suspend () -> UntisResponse<LoginResult>?>()

        // 1. TOTP from secret (Untis Mobile flow - highest priority for QR login)
        val totp = TotpUtils.generateTotp(pass)
        if (totp != null) {
            methods.add {
                Log.d("UntisClient", "Attempting getUserData2017 with TOTP: $totp")
                requestUserData2017(user, totp)
            }
        }

        // 2. Regular password (standard API)
        methods.add {
            Log.d("UntisClient", "Attempting JSON-RPC with password")
            requestJsonRpc(user, pass)
        }

        // 3. Mobile API v2
        methods.add {
            Log.d("UntisClient", "Attempting Mobile API v2")
            requestMobileAuth(user, pass)
        }

        var lastResponse: UntisResponse<LoginResult>? = null
        for (method in methods) {
            val response = method()
            if (response != null) {
                if (response.result != null || (response.error == null && sessionId != null)) {
                    // Success if we have a result OR if we have no error and managed to get a sessionId from cookies
                    return response
                }
                lastResponse = response
            }
        }

        return lastResponse ?: UntisResponse(error = UntisError(code = -1, message = "All auth methods failed"))
    }

    private suspend fun requestUserData2017(user: String, otp: String): UntisResponse<LoginResult>? {
        val request = UntisRequest(
            id = "1",
            method = "getUserData2017",
            params = listOf(
                UserData2017Params(
                    auth = AuthParams(
                        clientTime = System.currentTimeMillis(),
                        user = user,
                        otp = otp
                    )
                )
            )
        )
        return try {
            val response: HttpResponse = client.post(internUrl) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            
            // Extract JSESSIONID from Set-Cookie headers
            response.headers.getAll("Set-Cookie")?.forEach { cookie ->
                if (cookie.contains("JSESSIONID=")) {
                    sessionId = cookie.substringAfter("JSESSIONID=").substringBefore(";")
                    Log.d("UntisClient", "Extracted SessionID: $sessionId")
                }
            }
            
            val body = response.body<UntisResponse<LoginResult>>()
            // Some versions of the API return the data in a way that needs sessionId injection if it was only in cookies
            if (body.result != null && body.result.sessionId == null) {
                body.copy(result = body.result.copy(sessionId = sessionId))
            } else if (body.result == null && body.error == null && sessionId != null) {
                // If it succeeded but returned an empty result (unexpected but possible), 
                // return a dummy result with the sessionId
                UntisResponse(result = LoginResult(sessionId = sessionId))
            } else {
                body
            }
        } catch (e: Exception) {
            Log.e("UntisClient", "getUserData2017 failed", e)
            null
        }
    }

    private suspend fun requestJsonRpc(user: String, pass: String): UntisResponse<LoginResult>? {
        val request = UntisRequest(
            id = "1",
            method = "authenticate",
            params = LoginParams(user = user, password = pass, client = "Untis Mobile")
        )
        return try {
            val response = client.post(url) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            val body = response.body<UntisResponse<LoginResult>>()
            if (body.result != null) {
                sessionId = body.result.sessionId
            }
            body
        } catch (e: Exception) {
            Log.e("UntisClient", "JSON-RPC failed", e)
            null
        }
    }

    private suspend fun requestMobileAuth(user: String, pass: String): UntisResponse<LoginResult>? {
        return try {
            val response = client.post(mobileAuthUrl) {
                contentType(ContentType.Application.Json)
                setBody(MobileAuthRequest(username = user, password = pass))
            }
            if (response.status.value == 200) {
                val authRes = response.body<MobileAuthResponse>()
                UntisResponse(result = LoginResult(jwt = authRes.jwt))
            } else {
                Log.e("UntisClient", "Mobile API v2 failed: ${response.status}")
                null
            }
        } catch (e: Exception) {
            Log.e("UntisClient", "Mobile API v2 failed", e)
            null
        }
    }

    suspend fun getTimetable(
        personId: Int,
        personType: Int,
        startDate: Int,
        endDate: Int
    ): UntisResponse<List<TimetableEntry>>? {
        val request = UntisRequest(
            id = "2",
            method = "getTimetable",
            params = TimetableParams(personId, personType, startDate, endDate)
        )
        return try {
            client.post(url) {
                contentType(ContentType.Application.Json)
                header("Cookie", "JSESSIONID=$sessionId")
                setBody(request)
            }.body()
        } catch (e: Exception) {
            Log.e("UntisClient", "getTimetable failed", e)
            null
        }
    }
}
