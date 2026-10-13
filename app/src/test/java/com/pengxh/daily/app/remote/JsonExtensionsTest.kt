package com.pengxh.daily.app.remote

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 针对后台同步线程崩溃的回归测试。
 *
 * 崩溃现场：后端在「配置无变化」时返回 `"config": null`，
 * 代码直接调用 Gson 的 `getAsJsonObject("config")` 抛出
 * `ClassCastException: JsonNull cannot be cast to JsonObject`，
 * 该异常发生在 Dispatchers.IO 的同步协程中，直接杀死进程。
 */
class JsonExtensionsTest {

    /** 复现原始缺陷：Gson 原生的取值方式在遇到 null 字段时会抛异常。 */
    @Test
    fun gsonNativeGetter_throwsWhenFieldIsJsonNull() {
        val payload = JsonParser.parseString("""{"configRevision":3,"config":null}""").asJsonObject

        assertThrows(ClassCastException::class.java) {
            payload.getAsJsonObject("config")
        }
    }

    /** 修复后的取值方式必须安全返回 null。 */
    @Test
    fun objectOrNull_returnsNullWhenFieldIsJsonNull() {
        val payload = JsonParser.parseString("""{"configRevision":3,"config":null}""").asJsonObject

        assertNull(payload.objectOrNull("config"))
    }

    @Test
    fun objectOrNull_returnsObjectWhenFieldIsPresent() {
        val payload = JsonParser.parseString("""{"config":{"resetTime":9}}""").asJsonObject

        val config = payload.objectOrNull("config")

        assertEquals(9, config?.intOrZero("resetTime"))
    }

    @Test
    fun objectOrNull_returnsNullWhenFieldMissing() {
        val payload = JsonParser.parseString("""{"configRevision":3}""").asJsonObject

        assertNull(payload.objectOrNull("config"))
    }

    /** config 为 null 时应视为「无需应用配置」，而不是异常。 */
    @Test
    fun configNullMeansNoUpdateRequired() {
        val payload = JsonParser.parseString("""{"configRevision":3,"config":null}""").asJsonObject
        val configElement = payload.get("config")

        val shouldApply = configElement != null && configElement.isJsonObject

        assertTrue(!shouldApply)
    }

    /** 指令数组缺失或为 null 时不应抛异常。 */
    @Test
    fun arrayOrNull_returnsNullWhenFieldIsJsonNull() {
        val payload = JsonParser.parseString("""{"commands":null}""").asJsonObject

        assertNull(payload.arrayOrNull("commands"))
    }

    @Test
    fun arrayOrNull_returnsArrayWhenFieldIsPresent() {
        val payload = JsonParser.parseString("""{"commands":[{"id":"cmd_1"}]}""").asJsonObject

        assertEquals(1, payload.arrayOrNull("commands")?.size())
    }

    /** data 为 null 时应回退为空对象，而不是抛异常。 */
    @Test
    fun dataOrNull_returnsNullWhenFieldIsJsonNull() {
        val payload = JsonParser.parseString("""{"ok":false,"data":null}""").asJsonObject

        assertNull(payload.objectOrNull("data"))
    }

    /** 布尔字段为 null 时不应抛异常。 */
    @Test
    fun booleanOrNull_returnsNullWhenFieldIsJsonNull() {
        val payload = JsonParser.parseString("""{"ok":null}""").asJsonObject

        assertNull(payload.booleanOrNull("ok"))
    }
}