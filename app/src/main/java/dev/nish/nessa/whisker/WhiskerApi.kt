package dev.nish.nessa.whisker

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Whisker's REST API for Litter-Robot 5 / EVO. Mirrors pylitterbot robot/litterrobot5.py,
 * so a fix there can be copied here.
 */
class WhiskerApi(
    private val http: OkHttpClient,
    private val baseUrl: String = API_URL,
    private val userAgent: String,
    /** Called when Whisker refuses a token, so the next [idToken] call returns a renewed one. */
    private val invalidateToken: suspend () -> Unit = {},
    private val idToken: suspend () -> String,
) {
    suspend fun robots(): List<Robot> = decode(call("GET", "robots"))

    suspend fun robot(serial: String): Robot = decode(call("GET", "robots/$serial"))

    /** Changes settings; [body] is keyed by setting name, e.g. `sleepSchedules`. */
    suspend fun patchRobot(serial: String, body: JsonObject) {
        call("PATCH", "robots/$serial", body.toString().toByteArray().toRequestBody(JSON))
    }

    suspend fun command(serial: String, type: RobotCommand) {
        val body = buildJsonObject { put("type", type.name) }.toString()
        call("POST", "robots/$serial/commands", body.toByteArray().toRequestBody(JSON))
    }

    suspend fun activities(serial: String, limit: Int = 50, offset: Int = 0): List<Activity> =
        decode(call("GET", "robots/$serial/activities?limit=$limit&offset=$offset"))

    private suspend fun call(method: String, path: String, body: RequestBody? = null): Pair<Int, String> {
        val first = send(method, path, body, idToken())
        if (first.first !in REFUSED) return checked(first)
        // Whisker refused a token we thought was good (revoked, or the phone's clock is off).
        // Renew it and try once more. If the sign-in itself is dead, idToken() throws NeedsReconnect.
        invalidateToken()
        return checked(send(method, path, body, idToken()))
    }

    private fun checked(response: Pair<Int, String>): Pair<Int, String> =
        if (response.first in 200..299) response else throw WhiskerException.Server(response.first, null)

    private suspend fun send(method: String, path: String, body: RequestBody?, token: String): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(baseUrl.trimEnd('/') + "/" + path)
                .header("authorization", "Bearer $token")
                .header("User-Agent", userAgent)
                .method(method, body)
                .build()
            try {
                http.newCall(request).execute().use { it.code to it.body?.string().orEmpty() }
            } catch (e: IOException) {
                throw WhiskerException.Network(e)
            }
        }

    private inline fun <reified T> decode(response: Pair<Int, String>): T = try {
        WhiskerJson.decodeFromString<T>(response.second)
    } catch (e: SerializationException) {
        throw WhiskerException.Server(response.first, "UnexpectedBody")
    }

    companion object {
        const val API_URL = "https://ub.prod.iothings.site/"
        private val JSON = "application/json".toMediaType()
        private val REFUSED = setOf(401, 403)
    }
}
