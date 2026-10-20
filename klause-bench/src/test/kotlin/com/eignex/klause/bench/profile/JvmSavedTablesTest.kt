package com.eignex.klause.bench.profile

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JvmSavedTablesTest {
    @Test
    fun `selected fields retain nested values and integer precision`() {
        val envelope = JvmSavedJson.parse(
            """{
                "encoding":"key-and-string-tables-v1",
                "objectKeys":[["selected","hidden"],["value"]],"strings":["text"],
                "value":[-1,[-2,[0,[1,0],null,false,123456789012345678901234567890,0.125]],[0]]
            }""",
        ).jsonObject

        val actual = JvmSavedTables(envelope).project(setOf("selected"))

        assertEquals(
            JvmSavedJson.parse(
                """{"selected":{"value":["text",null,false,123456789012345678901234567890,0.125]}}""",
            ),
            actual,
        )
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "[1,1]", "[2]", "[]", "[1,true]", "[1,-1]", "[-2]", "[-1]", "\"text\"", "{\"x\":1}", "1e9999",
    ])
    fun `invalid unselected values reject the entire encoded record`(hidden: String) {
        val envelope = JvmSavedJson.parse(
            """{
                "encoding":"key-and-string-tables-v1",
                "objectKeys":[["selected","hidden"]],"strings":["kept"],
                "value":[-1,[1,0],$hidden]
            }""",
        ).jsonObject

        assertFailsWith<IllegalArgumentException> { JvmSavedTables(envelope).project(setOf("selected")) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["keys", "strings", "encoding", "identity-format", "extra"])
    fun `malformed dictionaries cannot define a saved record`(variant: String) {
        val original = JvmSavedJson.parse(
            """{
                "encoding":"key-and-string-tables-v1",
                "objectKeys":[["value"]],"strings":["kept"],"value":[-1,[1,0]]
            }""",
        ).jsonObject
        val field = when (variant) {
            "keys" -> "objectKeys" to JvmSavedJson.parse("[[\"value\",\"value\"]]")
            "strings" -> "strings" to JvmSavedJson.parse("[\"kept\",\"kept\"]")
            "encoding" -> "encoding" to JsonPrimitive("other")
            "identity-format" -> "encoding" to JsonPrimitive("canonical-json-key-and-string-tables-v1")
            else -> "extra" to JsonPrimitive(false)
        }

        assertFailsWith<IllegalArgumentException> { JvmSavedTables(JsonObject(original + field)) }
    }

    @Test
    fun `method projection preserves every ordered descriptor and ignores no invalid field`() {
        val envelope = JvmSavedJson.parse(
            """{
                "encoding":"key-and-string-tables-v1",
                "objectKeys":[["methods"],["methodIndex","name","descriptor","unknown"]],
                "strings":["pick","(I)V","(J)V"],
                "value":[-1,[0,[-2,0,[1,0],[1,1],[0,false]],[-2,1,[1,0],[1,2],null]]]
            }""",
        ).jsonObject

        val actual = JvmSavedTables(envelope).objectArray("methods", setOf("methodIndex", "name", "descriptor"))

        assertEquals(
            JvmSavedJson.parse(
                """[{"methodIndex":0,"name":"pick","descriptor":"(I)V"},
                    {"methodIndex":1,"name":"pick","descriptor":"(J)V"}]""",
            ),
            actual,
        )
    }

    @ParameterizedTest
    @ValueSource(ints = [2048, 4097])
    fun `deep saved values use bounded traversal without recursive expansion`(depth: Int) {
        var nested = JsonArray(emptyList())
        repeat(depth) { nested = JsonArray(listOf(JsonPrimitive(0), nested).take(if (it == 0) 1 else 2)) }
        val envelope = JsonObject(
            mapOf(
                "encoding" to JsonPrimitive("key-and-string-tables-v1"),
                "objectKeys" to JvmSavedJson.parse("[[\"value\"]]"),
                "strings" to JsonArray(emptyList()),
                "value" to JsonArray(listOf(JsonPrimitive(-1), nested)),
            ),
        )

        if (depth > 4096) {
            assertFailsWith<IllegalArgumentException> { JvmSavedTables(envelope) }
        } else {
            var value = JvmSavedTables(envelope).project(setOf("value")).getValue("value")
            repeat(depth - 1) { value = (value as JsonArray).single() }
            assertEquals(JsonArray(emptyList()), value)
        }
    }
}
