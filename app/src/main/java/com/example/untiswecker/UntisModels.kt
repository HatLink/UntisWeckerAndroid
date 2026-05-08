package com.example.untiswecker

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class UntisRequest<T>(
    val jsonrpc: String = "2.0",
    val id: String,
    val method: String,
    val params: T
)

@Serializable
data class LoginParams(
    val user: String,
    val password: String,
    val client: String
)

@Serializable
data class UntisResponse<T>(
    val jsonrpc: String = "2.0",
    val id: JsonElement? = null,
    val result: T? = null,
    val error: UntisError? = null
)

@Serializable
data class UntisError(
    val code: Int,
    val message: String
)

@Serializable
data class LoginResult(
    val sessionId: String? = null,
    val jwt: String? = null,
    val personType: JsonElement? = null,
    val personId: Int? = null,
    val userId: Int? = null,
    val klasseId: Int? = null,
    val userConfig: UserConfig? = null,
    val userData: UserData? = null,
    val masterData: MasterData? = null
) {
    fun extractPersonId(): Int? = personId ?: userConfig?.personId ?: userData?.elemId

    fun extractPersonType(): Int? {
        val typeJson = personType ?: userConfig?.personType ?: userData?.elemType
        val typeStr = typeJson?.toString()?.trim('"') ?: return null
        return when {
            typeStr.contains("STUDENT", ignoreCase = true) -> 5
            typeStr.contains("TEACHER", ignoreCase = true) -> 2
            else -> typeStr.toIntOrNull()
        }
    }
}

@Serializable
data class MasterData(
    val teachers: List<Entity>? = null,
    val subjects: List<Entity>? = null,
    val rooms: List<Entity>? = null,
    val klassen: List<Entity>? = null
)

@Serializable
data class UserConfig(
    val personId: Int? = null,
    val personType: JsonElement? = null
)

@Serializable
data class UserData(
    val elemId: Int? = null,
    val elemType: JsonElement? = null
)

@Serializable
data class UserData2017Params(
    val auth: AuthParams
)

@Serializable
data class AuthParams(
    val clientTime: Long,
    val user: String,
    val otp: String
)

@Serializable
data class TimetableParams(
    val id: Int,
    val type: Int,
    val startDate: Int,
    val endDate: Int
)

@Serializable
data class TimetableEntry(
    val id: Long,
    val date: Int,
    val startTime: Int,
    val endTime: Int,
    val kl: List<Entity>? = null,
    val te: List<Entity>? = null,
    val su: List<Entity>? = null,
    val ro: List<Entity>? = null,
    val activityType: String? = null,
    val code: String? = null,
    val lstext: String? = null,
    val substText: String? = null,
    val info: String? = null
)

@Serializable
data class Entity(
    val id: Long? = null,
    val name: String? = null,
    val longName: String? = null,
    val longname: String? = null,
    val lastName: String? = null,
    val foreName: String? = null,
    val forename: String? = null,
    val firstName: String? = null,
    val title: String? = null,
    val dname: String? = null,
    val fullName: String? = null,
    val backColor: String? = null,
    val foreColor: String? = null,
    val orgid: Long? = null
) {
    fun getDisplayName(preferLong: Boolean = true): String? {
        val short = name?.takeIf { it.isNotEmpty() }
        val long = (longname ?: longName ?: lastName ?: dname ?: fullName)?.takeIf { it.isNotEmpty() }
        return if (preferLong) (long ?: short) else (short ?: long)
    }

    fun getFullTeacherName(): String? {
        val t = title?.takeIf { it.isNotEmpty() }
        val last = (lastName ?: longname ?: longName)?.takeIf { it.isNotEmpty() }
        val first = (firstName ?: forename ?: foreName)?.takeIf { it.isNotEmpty() }
        val dn = dname?.takeIf { it.isNotEmpty() }
        val fn = fullName?.takeIf { it.isNotEmpty() }

        return when {
            t != null && last != null -> "$t $last"
            dn != null -> dn
            fn != null -> fn
            first != null && last != null -> "$first $last"
            last != null -> last
            first != null -> first
            else -> name
        }
    }
}

@Serializable
data class MobileAuthRequest(
    val username: String,
    val password: String
)

@Serializable
data class MobileAuthResponse(
    val jwt: String,
    val isEmailUpdateRequired: Boolean = false,
    val isPasswordChangeRequired: Boolean = false
)
