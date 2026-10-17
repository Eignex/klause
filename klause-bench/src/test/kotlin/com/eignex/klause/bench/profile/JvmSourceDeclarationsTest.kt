package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.sha256
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JvmSourceDeclarationsTest {
    private lateinit var parser: JvmSourceDeclarationParser

    @BeforeAll
    fun prepareParser() {
        parser = JvmSourceDeclarationParser()
        parser.inspect("Warm.kt", "fun warm() = 1") { }
    }

    @AfterAll
    fun closeParser() {
        parser.close()
    }

    @ParameterizedTest
    @ValueSource(strings = ["exception", "error"])
    fun `failed source traversal retains a partial report`(variant: String, @TempDir out: File) {
        val failure = if (variant == "error") AssertionError("source traversal failed")
            else IllegalStateException("source traversal failed")
        val sources = object : AbstractList<SourceSyntaxInput>() {
            override val size = 1
            override fun get(index: Int): SourceSyntaxInput = throw failure
        }

        val actual = assertFailsWith<Throwable> { JvmSourceDeclarations.inspect(sources, out) }

        assertSame(failure, actual)
        assertTrue(File(out, "source-declarations.partial.jsonl.gz").isFile)
        assertFalse(File(out, "source-declarations.jsonl.gz").exists())
    }

    @ParameterizedTest
    @ValueSource(strings = ["uncompressed", "compressed"])
    fun `report byte bounds decline the complete inventory`(variant: String, @TempDir out: File) {
        val text = "fun pick() = 1"
        val sources = listOf(SourceSyntaxInput("pick", "Pick.kt", sha256(text.encodeToByteArray()), text))

        assertFailsWith<IllegalStateException> {
            if (variant == "uncompressed") JvmSourceDeclarations.inspect(sources, out, maximumReportBytes = 1)
            else JvmSourceDeclarations.inspect(sources, out, maximumCompressedReportBytes = 1)
        }

        assertTrue(File(out, "source-declarations.partial.jsonl.gz").isFile)
        assertFalse(File(out, "source-declarations.jsonl.gz").exists())
    }

    @Test
    fun `request retains the complete immutable source specification identity`() {
        val arguments = arrayOf("declarations=https://example.test/sources.json",
            "declarations-sha256=${"a".repeat(64)}")

        val options = JvmSourceDeclarations.parse(arguments)

        assertEquals(arguments.associate { it.substringBefore('=') to it.substringAfter('=') }, options)
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "duplicate", "unknown", "hash", "http", "credentials", "query", "fragment"])
    fun `ambiguous and unpinned source requests fail before access`(variant: String) {
        val base = listOf("declarations=https://example.test/sources.json", "declarations-sha256=${"a".repeat(64)}")
        val arguments = when (variant) {
            "missing" -> base.drop(1)
            "duplicate" -> base + base.first()
            "unknown" -> base + "other=value"
            "hash" -> base.dropLast(1) + "declarations-sha256=bad"
            else -> listOf("declarations=" + when (variant) {
                "http" -> "http://example.test/sources.json"
                "credentials" -> "https://user@example.test/sources.json"
                "query" -> "https://example.test/sources.json?q=x"
                else -> "https://example.test/sources.json#fragment"
            }, base.last())
        }

        assertFailsWith<IllegalArgumentException> { JvmSourceDeclarations.parse(arguments.toTypedArray()) }
    }

    @Test
    fun `source text is authenticated before parser access`() {
        val text = "fun pick() = 1\n"
        val source = JsonObject(mapOf("id" to JsonPrimitive("pick"), "path" to JsonPrimitive("src/Pick.kt"),
            "sourceFile" to JsonPrimitive("Pick.kt"), "sourceText" to JsonPrimitive(text),
            "sha256" to JsonPrimitive(sha256(text.encodeToByteArray()))))
        val input = JsonObject(mapOf("schema" to JsonPrimitive(1), "sources" to JsonArray(listOf(source))))

        val sources = JvmSourceDeclarations.readSources(input.toString())

        assertEquals(listOf(SourceSyntaxInput("pick", "src/Pick.kt", sha256(text.encodeToByteArray()), text)), sources)
    }

    @ParameterizedTest
    @ValueSource(strings = ["../Pick.kt", "/Pick.kt", "src//Pick.kt", "src/./Pick.kt", "src\\Pick.kt", "Pick.txt"])
    fun `unsupported source paths cannot become declaration inputs`(path: String) {
        val source = JsonObject(mapOf("id" to JsonPrimitive("pick"), "path" to JsonPrimitive(path),
            "sourceFile" to JsonPrimitive(path.substringAfterLast('/')), "sourceText" to JsonPrimitive(""),
            "sha256" to JsonPrimitive(sha256(byteArrayOf()))))
        val input = JsonObject(mapOf("schema" to JsonPrimitive(1), "sources" to JsonArray(listOf(source))))

        assertFailsWith<IllegalArgumentException> { JvmSourceDeclarations.readSources(input.toString()) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["schema", "escaped", "nested", "hash", "identity", "path", "empty"])
    fun `duplicate and changed source declarations cannot hide an input`(variant: String) {
        val source = "{\"id\":\"pick\",\"path\":\"Pick.kt\",\"sourceFile\":\"Pick.kt\"," +
            "\"sourceText\":\"\",\"sha256\":\"${sha256(byteArrayOf())}\"}"
        val input = when (variant) {
            "schema" -> "{\"schema\":1,\"schema\":1,\"sources\":[$source]}"
            "escaped" -> "{\"schema\":1,\"sources\":[],\"\\u0073ources\":[$source]}"
            "nested" -> "{\"schema\":1,\"extra\":{\"a\":1,\"a\":2},\"sources\":[$source]}"
            "hash" -> "{\"schema\":1,\"sources\":[${source.replace(sha256(byteArrayOf()), "a".repeat(64))}]}"
            "identity" -> "{\"schema\":1,\"sources\":[$source,$source]}"
            "path" -> "{\"schema\":1,\"sources\":[$source,${source.replace("pick", "other")}]}"
            else -> "{\"schema\":1,\"sources\":[]}"
        }

        assertFailsWith<IllegalArgumentException> { JvmSourceDeclarations.readSources(input) }
    }

    @Test
    fun `an oversized source inventory cannot become a smaller prefix`() {
        val input = "{\"schema\":1,\"sources\":[" + List(4097) { "{}" }.joinToString(",") + "]}"

        assertFailsWith<IllegalArgumentException> { JvmSourceDeclarations.readSources(input) }
    }
}
