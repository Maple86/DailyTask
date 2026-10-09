package com.pengxh.daily.app.remote

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class RelayResponse(
    val ok: Boolean,
    val data: JsonObject?,
    val errorCode: String,
    val errorMessage: String
)

class RemoteRelayClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun call(
        endpoint: String,
        payload: JsonObject,
        token: String? = null
    ): RelayResponse = withContext(Dispatchers.IO) {
        if (endpoint.isBlank()) {
            return@withContext RelayResponse(false, null, "ENDPOINT_EMPTY", "服务地址为空")
        }

        val requestBuilder = Request.Builder()
            .url(endpoint)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))

        if (!token.isNullOrBlank()) {
            requestBuilder.header("Authorization", "Bearer $token")
        }

        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                val body = response.body.string()
                if (body.isBlank()) {
                    return@withContext RelayResponse(
                        false,
                        null,
                        "EMPTY_RESPONSE",
                        "服务端返回为空，HTTP ${response.code}"
                    )
                }

                val root = runCatching { JsonParser.parseString(body).asJsonObject }.getOrElse {
                    return@withContext RelayResponse(
                        false,
                        null,
                        "INVALID_RESPONSE",
                        "服务端返回格式错误，HTTP ${response.code}"
                    )
                }

                val ok = root.get("ok")?.asBoolean ?: response.isSuccessful
                if (!ok) {
                    val error = root.getAsJsonObject("error")
                    return@withContext RelayResponse(
                        false,
                        null,
                        error?.get("code")?.asString ?: "REQUEST_FAILED",
                        error?.get("message")?.asString ?: "请求失败，HTTP ${response.code}"
                    )
                }

                RelayResponse(
                    true,
                    root.getAsJsonObject("data") ?: JsonObject(),
                    "",
                    ""
                )
            }
        } catch (e: Exception) {
            RelayResponse(false, null, "NETWORK_ERROR", e.message ?: "网络请求失败")
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

