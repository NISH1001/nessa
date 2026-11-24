package dev.nish.nessa.whisker

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** A Whisker session token set. [refreshToken] is null when Cognito didn't issue a new one. */
data class Tokens(val idToken: String, val refreshToken: String?, val expiresAtEpochSec: Long) {
    override fun toString() = "Tokens(expiresAtEpochSec=$expiresAtEpochSec)"
}

/**
 * Signs in to Whisker's AWS Cognito user pool with plain HTTPS calls.
 * Mirrors pylitterbot's session.py (which uses pycognito for the same pool and client).
 */
class CognitoAuth(
    private val http: OkHttpClient,
    private val baseUrl: String = COGNITO_URL,
    private val clientId: String = CLIENT_ID,
    private val nowEpochSec: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    suspend fun signIn(email: String, password: String): Tokens =
        initiate("USER_PASSWORD_AUTH", mapOf("USERNAME" to email, "PASSWORD" to password))

    /** The returned [Tokens.refreshToken] is null; keep using the one you passed in. */
    suspend fun refresh(refreshToken: String): Tokens =
        initiate("REFRESH_TOKEN_AUTH", mapOf("REFRESH_TOKEN" to refreshToken))

    private suspend fun initiate(flow: String, params: Map<String, String>): Tokens = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("AuthFlow", flow)
            put("ClientId", clientId)
            putJsonObject("AuthParameters") { params.forEach { (k, v) -> put(k, v) } }
        }.toString()
        val request = Request.Builder()
            .url(baseUrl)
            .header("X-Amz-Target", "AWSCognitoIdentityProviderService.InitiateAuth")
            .post(body.toByteArray().toRequestBody(AMZ_JSON)) // bytes: keeps the exact content type, no charset suffix
            .build()
        val (code, text) = try {
            http.newCall(request).execute().use { it.code to it.body?.string().orEmpty() }
        } catch (e: IOException) {
            throw WhiskerException.Network(e)
        }
        if (code !in 200..299) {
            val type = runCatching { json.decodeFromString<ErrorBody>(text).type }.getOrNull()?.substringAfterLast('#')
            if (code == 400 && type in REJECTED) throw WhiskerException.BadCredentials()
            throw WhiskerException.Server(code, type)
        }
        val result = runCatching { json.decodeFromString<AuthResponse>(text).result }.getOrNull()
            ?: throw WhiskerException.Server(code, "NoTokens")
        Tokens(result.idToken, result.refreshToken, nowEpochSec() + result.expiresIn)
    }

    @Serializable
    private data class AuthResponse(@SerialName("AuthenticationResult") val result: AuthResult? = null)

    @Serializable
    private data class AuthResult(
        @SerialName("IdToken") val idToken: String,
        @SerialName("RefreshToken") val refreshToken: String? = null,
        @SerialName("ExpiresIn") val expiresIn: Long = 3600,
    )

    @Serializable
    private data class ErrorBody(@SerialName("__type") val type: String? = null)

    companion object {
        const val COGNITO_URL = "https://cognito-idp.us-east-1.amazonaws.com/"
        const val CLIENT_ID = "4552ujeu3aic90nf8qn53levmn"
        private val AMZ_JSON = "application/x-amz-json-1.1".toMediaType()
        private val REJECTED = setOf("NotAuthorizedException", "UserNotFoundException")
        private val json = Json { ignoreUnknownKeys = true }
    }
}
