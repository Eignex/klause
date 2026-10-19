package com.eignex.klause.bench.profile

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

internal class JvmSavedJsonTest {
    @Test
    fun `complete producer metadata retains unknown fields above the catalog limit`(@TempDir out: File) {
        val file = File(out, "completion.json")
        val value = """{"completed":true,"unknown":{"alternatives":[null,false,{"name":"cold"}]}}"""
        file.writeText(value + " ".repeat(28_251_896 - value.length - 1) + "\n")

        val actual = JvmSavedJson.read(file, JvmSavedJson.Kind.PRODUCER_COMPLETION)

        assertEquals(Json.parseToJsonElement(value), actual)
    }

    @Test
    fun `a large producer file cannot be admitted as catalog metadata`(@TempDir out: File) {
        val file = File(out, "catalog.json")
        RandomAccessFile(file, "rw").use { it.setLength(28_251_896) }

        val failure = assertFailsWith<IllegalArgumentException> {
            JvmSavedJson.read(file, JvmSavedJson.Kind.CATALOG_METADATA)
        }

        assertEquals("JSON metadata exceeds CATALOG_METADATA bound", failure.message)
    }

    @ParameterizedTest
    @EnumSource(JvmSavedJson.Kind::class)
    fun `metadata above its declared kind bound is refused before decoding`(
        kind: JvmSavedJson.Kind,
        @TempDir out: File,
    ) {
        val file = File(out, "metadata.json")
        RandomAccessFile(file, "rw").use { it.setLength(kind.maximumBytes + 1) }

        val failure = assertFailsWith<IllegalArgumentException> { JvmSavedJson.read(file, kind) }

        assertEquals("JSON metadata exceeds ${kind.name} bound", failure.message)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "{\"key\":1,\"key\":2}",
            "{\"key\":1,\"\\u006bey\":2}",
            "{\"nested\":[{\"key\":1,\"key\":2}]}",
        ],
    )
    fun `duplicate keys cannot replace saved observations`(text: String, @TempDir out: File) {
        val file = File(out, "metadata.json").apply { writeText(text) }

        val failure = assertFailsWith<IllegalArgumentException> {
            JvmSavedJson.read(file, JvmSavedJson.Kind.CATALOG_METADATA)
        }

        assertEquals("Duplicate saved JSON key", failure.message)
    }

    @Test
    fun `identical keys in distinct objects retain their separate values`(@TempDir out: File) {
        val text = """{"key":1,"nested":[{"key":2},{"key":3}],"tail":{"key":4}}"""
        val file = File(out, "metadata.json").apply { writeText(text) }

        val actual = JvmSavedJson.read(file, JvmSavedJson.Kind.CATALOG_METADATA)

        assertEquals(Json.parseToJsonElement(text), actual)
    }

    @Test
    fun `invalid UTF8 cannot be replaced while decoding saved metadata`(@TempDir out: File) {
        val file = File(out, "metadata.json")
        file.writeBytes(byteArrayOf(123, 34, 120, 34, 58, 34, 0xc3.toByte(), 0x28, 34, 125))

        assertFailsWith<CharacterCodingException> {
            JvmSavedJson.read(file, JvmSavedJson.Kind.CATALOG_METADATA)
        }
    }

    @Test
    fun `a linked file cannot substitute for saved metadata`(@TempDir out: File) {
        val original = File(out, "original.json").apply { writeText("{}") }
        val link = File(out, "metadata.json")
        Files.createSymbolicLink(link.toPath(), original.toPath())

        assertFailsWith<IllegalArgumentException> {
            JvmSavedJson.read(link, JvmSavedJson.Kind.CATALOG_METADATA)
        }
    }
}
