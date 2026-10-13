package com.pengxh.daily.app.remote

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * 安全读取 JsonObject 字段。
 *
 * Gson 的 `getAsJsonObject(key)` / `getAsJsonArray(key)` 在字段存在但值为 `null`
 * （即 `JsonNull`）时会抛 `ClassCastException`，而不是返回 null。
 * 服务端在多处会返回 `"xxx": null`（例如配置无变化时的 `config`），
 * 因此统一用这些扩展方法做类型判断，避免后台同步线程崩溃。
 */
fun JsonObject.objectOrNull(key: String): JsonObject? {
    val element = get(key) ?: return null
    return if (element.isJsonObject) element.asJsonObject else null
}

fun JsonObject.arrayOrNull(key: String): JsonArray? {
    val element = get(key) ?: return null
    return if (element.isJsonArray) element.asJsonArray else null
}

fun JsonObject.stringOrEmpty(key: String): String {
    val element = get(key) ?: return ""
    return if (element.isJsonPrimitive) element.asString else ""
}

fun JsonObject.intOrZero(key: String): Int {
    val element = get(key) ?: return 0
    return if (element.isJsonPrimitive && element.asJsonPrimitive.isNumber) element.asInt else 0
}

fun JsonObject.booleanOrNull(key: String): Boolean? {
    val element = get(key) ?: return null
    return if (element.isJsonPrimitive && element.asJsonPrimitive.isBoolean) element.asBoolean else null
}