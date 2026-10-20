package com.eignex.klause.bench.profile

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.charset.CharacterCodingException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JvmSavedTablesTest {
    @ParameterizedTest
    @ValueSource(strings = ["normal", "value-first", "strings-last"])
    fun `dictionary field order preserves unicode references`(variant: String) {
        val parts = mapOf(
            "encoding" to "\"encoding\":\"key-and-string-tables-v1\"",
            "objectKeys" to "\"objectKeys\":[[\"selected\"]]",
            "strings" to "\"strings\":[\"cafe \u00e9 \u2600\"]",
            "value" to "\"value\":[-1,[1,0]]",
        )
        val order = when (variant) {
            "value-first" -> listOf("value", "strings", "encoding", "objectKeys")
            "strings-last" -> listOf("encoding", "value", "objectKeys", "strings")
            else -> listOf("encoding", "objectKeys", "strings", "value")
        }
        val raw = order.joinToString(",", "{", "}") { parts.getValue(it) }.encodeToByteArray()

        val actual = JvmSavedTables(raw).project(setOf("selected"))

        assertEquals(JsonObject(mapOf("selected" to JsonPrimitive("cafe \u00e9 \u2600"))), actual)
    }

    @ParameterizedTest
    @ValueSource(strings = ["duplicate", "escaped-duplicate", "trailing", "truncated"])
    fun `the whole envelope rejects duplicate fields and incomplete or extra json`(variant: String) {
        val fields = """"encoding":"key-and-string-tables-v1","objectKeys":[[]],"strings":[],"value":[-1]"""
        val text = when (variant) {
            "duplicate" -> "{$fields,\"encoding\":\"key-and-string-tables-v1\"}"
            "escaped-duplicate" -> "{$fields,\"encod\\u0069ng\":\"key-and-string-tables-v1\"}"
            "trailing" -> "{$fields} false"
            else -> "{$fields"
        }

        assertFailsWith<IllegalArgumentException> { JvmSavedTables(text.encodeToByteArray()).project(emptySet()) }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `unselected array suffixes still require every dictionary reference`(invalid: Boolean) {
        val suffix = if (invalid) "[1,99]" else "false"
        val hidden = List(10_000) { "0" }.joinToString(",", "[0,", ",$suffix]")
        val raw = """{
            "encoding":"key-and-string-tables-v1","objectKeys":[["selected","hidden"]],
            "strings":["kept"],"value":[-1,[1,0],$hidden]
        }""".encodeToByteArray()

        if (invalid) {
            assertFailsWith<IllegalArgumentException> { JvmSavedTables(raw).project(setOf("selected")) }
        } else {
            assertEquals(
                JsonObject(mapOf("selected" to JsonPrimitive("kept"))),
                JvmSavedTables(raw).project(setOf("selected")),
            )
        }
    }

    @Test
    fun `unselected method payloads cannot hide a dangling reference`() {
        val raw = """{
            "encoding":"key-and-string-tables-v1",
            "objectKeys":[["methods"],["methodIndex","name","descriptor","code"]],
            "strings":["pick","()V"],"value":[-1,[0,[-2,0,[1,0],[1,1],[1,99]]]]
        }""".encodeToByteArray()

        assertFailsWith<IllegalArgumentException> {
            JvmSavedTables(raw).project(
                setOf("methods"), mapOf("methods" to setOf("methodIndex", "name", "descriptor")),
            )
        }
    }

    @Test
    fun `repeated complete key rows retain their independent dictionary positions`() {
        val raw = """{
            "encoding":"key-and-string-tables-v1","objectKeys":[["selected"],["selected"]],
            "strings":["kept"],"value":[-2,[1,0]]
        }""".encodeToByteArray()

        assertEquals(
            JsonObject(mapOf("selected" to JsonPrimitive("kept"))), JvmSavedTables(raw).project(setOf("selected")),
        )
    }

    @Test
    fun `encoded reports refuse malformed utf8 before projection`() {
        val raw = byteArrayOf(0xc3.toByte(), 0x28)

        assertFailsWith<CharacterCodingException> { JvmSavedTables(raw) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["null", "[0]"])
    fun `optional method arrays preserve null and empty metadata`(methods: String) {
        val raw = """{
            "encoding":"key-and-string-tables-v1","objectKeys":[["methods"]],"strings":[],"value":[-1,$methods]
        }""".encodeToByteArray()

        val actual = JvmSavedTables(raw).project(setOf("methods"), mapOf("methods" to setOf("methodIndex")))

        assertEquals(if (methods == "null") JsonNull else JsonArray(emptyList()), actual["methods"])
    }

    @Test
    fun `selected fields retain nested values and integer precision`() {
        val envelope = JvmSavedJson.parse(
            """{
                "encoding":"key-and-string-tables-v1",
                "objectKeys":[["selected","hidden"],["value"]],"strings":["text"],
                "value":[-1,[-2,[0,[1,0],null,false,123456789012345678901234567890,0.125]],[0]]
            }""",
        ).jsonObject

        val actual = JvmSavedTables(envelope.toString().encodeToByteArray()).project(setOf("selected"))

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

        assertFailsWith<IllegalArgumentException> {
            JvmSavedTables(envelope.toString().encodeToByteArray()).project(setOf("selected"))
        }
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

        assertFailsWith<IllegalArgumentException> {
            JvmSavedTables(JsonObject(original + field).toString().encodeToByteArray())
        }
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

        val actual = JvmSavedTables(envelope.toString().encodeToByteArray()).project(
            setOf("methods"), mapOf("methods" to setOf("methodIndex", "name", "descriptor")),
        ).getValue("methods")

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
        val nested = "[0,".repeat(depth - 1) + "[0]" + "]".repeat(depth - 1)
        val raw = """{
            "encoding":"key-and-string-tables-v1","objectKeys":[["value"]],"strings":[],"value":[-1,$nested]
        }""".encodeToByteArray()

        if (depth > 4096) {
            assertFailsWith<IllegalArgumentException> { JvmSavedTables(raw) }
        } else {
            var value = JvmSavedTables(raw).project(setOf("value")).getValue("value")
            repeat(depth - 1) { value = (value as JsonArray).single() }
            assertEquals(JsonArray(emptyList()), value)
        }
    }
}
