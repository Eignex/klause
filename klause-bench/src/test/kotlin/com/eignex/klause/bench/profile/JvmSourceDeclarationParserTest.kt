package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.sha256
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JvmSourceDeclarationParserTest {
    private lateinit var parser: JvmSourceDeclarationParser

    @BeforeAll
    fun prepareParser() {
        parser = JvmSourceDeclarationParser()
        parser.inspect("Warm.kt", "fun warm() = 1") { }
        parser.inspect("Warm.java", "class Warm { int warm() { return 1; } }") { }
    }

    @AfterAll
    fun closeParser() {
        parser.close()
    }

    @ParameterizedTest
    @ValueSource(strings = ["\n", ";\n"])
    fun `overloads and local functions retain distinct ranges and lexical parents`(separator: String) {
        val text = "fun pick(x: Int) = x\nfun pick(x: String): String { fun local() = x${separator} return local() }\n"
        val rows = ArrayList<JsonObject>()

        val result = parser.inspect("Pick.kt", text, rows::add)

        val functions = rows.filter { it["kind"]?.jsonPrimitive?.content == "function" }
        assertEquals(0, result.syntaxErrors, rows.filter { it["syntaxError"] != JsonNull }.toString())
        assertEquals(listOf("pick", "pick", "local"), functions.map { it.getValue("name").jsonPrimitive.content })
        assertTrue(functions[0]["range"] != functions[1]["range"])
        val localParent = functions[2].getValue("parentIndex").jsonPrimitive.int
        assertEquals("body", rows[localParent].getValue("role").jsonPrimitive.content)
    }

    @Test
    fun `function text in comments and strings creates no declaration`() {
        val rows = ArrayList<JsonObject>()
        val text = "/* fun fake() { } */\nfun real() = \"fun another() = 1\"\n"

        parser.inspect("Real.kt", text, rows::add)

        assertEquals(listOf("real"), rows.filter { it["kind"]?.jsonPrimitive?.content == "function" }
            .map { it.getValue("name").jsonPrimitive.content })
    }

    @ParameterizedTest
    @ValueSource(strings = ["\n", ";\n"])
    fun `executable string interpolation retains its local declarations`(separator: String) {
        val rows = ArrayList<JsonObject>()
        val text = "fun render() = \"value=\${run { fun local() = 1${separator} local() }}\""

        val result = parser.inspect("Render.kt", text, rows::add)

        assertEquals(0, result.syntaxErrors, rows.filter { it["syntaxError"] != JsonNull }.toString())
        assertEquals(listOf("render", "local"), rows.filter { it["kind"]?.jsonPrimitive?.content == "function" }
            .map { it.getValue("name").jsonPrimitive.content })
    }

    @Test
    fun `default argument expressions retain their parameter owner`() {
        val rows = ArrayList<JsonObject>()

        parser.inspect("Default.kt", "fun pick(x: Int = other()) = x", rows::add)

        val argument = rows.single { it["role"]?.jsonPrimitive?.content == "default-argument" }
        val parent = argument.getValue("parentIndex").jsonPrimitive.int
        assertEquals("parameter", rows[parent].getValue("kind").jsonPrimitive.content)
        assertEquals("x", rows[parent].getValue("name").jsonPrimitive.content)
    }

    @Test
    fun `accessors delegates and initializers remain separate source constructs`() {
        val text = """
            class Box {
                val first by lazy { 1 }
                var second = 0
                    get() = field
                    set(value) { field = value }
                init { second++ }
            }
        """.trimIndent()
        val rows = ArrayList<JsonObject>()

        val result = parser.inspect("Box.kt", text, rows::add)

        assertEquals(0, result.syntaxErrors, rows.filter { it["syntaxError"] != JsonNull }.toString())
        val kinds = rows.map { it.getValue("kind").jsonPrimitive.content }
        assertTrue(kinds.containsAll(listOf("getter", "setter", "initializer", "lambda")))
        val roles = rows.map { it.getValue("role").jsonPrimitive.content }
        assertTrue(roles.containsAll(listOf("property-delegate", "property-initializer")))
    }

    @ParameterizedTest
    @ValueSource(strings = ["\n", "\r\n", "\r"])
    fun `unicode and line separators retain exact original byte identities`(separator: String) {
        val text = "// emoji \uD83D\uDE00${separator}fun \u03A9() = \"\uD83D\uDE00\"$separator"
        val raw = text.encodeToByteArray()
        val rows = ArrayList<JsonObject>()

        val result = parser.inspect("Unicode.kt", text, rows::add)

        assertEquals(0, result.syntaxErrors, rows.filter { it["syntaxError"] != JsonNull }.toString())
        val function = rows.single { it["kind"]?.jsonPrimitive?.content == "function" }
        val range = function.getValue("range").jsonObject
        assertEquals(1, range.getValue("firstLine").jsonPrimitive.int)
        assertEquals("\u03A9", function.getValue("name").jsonPrimitive.content)
        assertEquals(0, range.getValue("startUtf16").jsonPrimitive.int)
        assertEquals(0, range.getValue("startByte").jsonPrimitive.int)
        val body = rows.single { it["role"]?.jsonPrimitive?.content == "body" }.getValue("range").jsonObject
        assertEquals(2, body.getValue("firstLine").jsonPrimitive.int)
        assertEquals(21 + separator.length, body.getValue("startUtf16").jsonPrimitive.int)
        assertEquals(24 + separator.length, body.getValue("startByte").jsonPrimitive.int)
        assertEquals("\"\uD83D\uDE00\"", raw.copyOfRange(body.getValue("startByte").jsonPrimitive.int,
            body.getValue("endByteExclusive").jsonPrimitive.int).decodeToString())
        assertEquals(raw.size, rows.first().getValue("range").jsonObject.getValue("endByteExclusive").jsonPrimitive.int)
        for (row in rows) {
            val span = row.getValue("range").jsonObject
            val bytes = raw.copyOfRange(span.getValue("startByte").jsonPrimitive.int,
                span.getValue("endByteExclusive").jsonPrimitive.int)
            assertEquals(sha256(bytes), span.getValue("sha256").jsonPrimitive.content)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "fun pick(x: Int) = x\nfun pick(x: String): String { fun local() = x; return local() }\n",
        "fun render() = \"value=\${run { fun local() = 1; local() }}\"",
        "fun pick(x: Int) = x\nfun pick(x: String): String { fun local() = x;; return local() }\n",
        "fun render() = \"value=\${run { fun local() = 1;; local() }}\"",
    ])
    fun `compiler rejected local function separators retain errors and declarations`(text: String) {
        val rows = ArrayList<JsonObject>()

        val result = parser.inspect("Rejected.kt", text, rows::add)

        assertEquals(1, result.syntaxErrors)
        assertEquals("Unexpected tokens (use ';' to separate expressions on the same line)",
            rows.single { it["kind"]?.jsonPrimitive?.content == "syntax-error" }
                .getValue("syntaxError").jsonPrimitive.content)
        assertTrue(rows.any { it["name"]?.jsonPrimitive?.content == "local" })
    }

    @Test
    fun `Java constructors methods and lambdas retain their source constructs`() {
        val text = "class Box { Box() {} void act() { Runnable x = () -> call(); } }"
        val rows = ArrayList<JsonObject>()

        val result = parser.inspect("Box.java", text, rows::add)

        assertEquals(0, result.syntaxErrors, rows.filter { it["syntaxError"] != JsonNull }.toString())
        assertTrue(rows.map { it.getValue("kind").jsonPrimitive.content }
            .containsAll(listOf("constructor", "method", "lambda", "call")))
    }

    @Test
    fun `syntax failures remain explicit alongside the rest of the source tree`() {
        val rows = ArrayList<JsonObject>()

        val result = parser.inspect("Broken.kt", "fun broken( = 1\nfun intact() = 2", rows::add)

        assertTrue(result.syntaxErrors > 0)
        assertTrue(rows.any { it["syntaxError"] != JsonNull })
        assertTrue(rows.any { it["kind"]?.jsonPrimitive?.content == "source-file" })
        assertFalse(rows.any { it.getValue("symbolResolutionProven").jsonPrimitive.content == "true" })
    }
}
