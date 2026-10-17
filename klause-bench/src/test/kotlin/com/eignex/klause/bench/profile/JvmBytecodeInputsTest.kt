package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.sha256
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JvmBytecodeInputsTest {
    @Test
    fun `the complete input specification retains source and file identities`(@TempDir root: File) {
        val input = JvmBytecodeInput("original", "jars", "a".repeat(40), root.path,
            linkedMapOf("lib/sample.jar" to CaptureFile(3, "b".repeat(64))))
        val text = Json.encodeToString(JvmBytecodeSpecification.serializer(),
            JvmBytecodeSpecification(1, listOf(input)))

        val actual = JvmBytecodeInputs.read(text)

        assertEquals(listOf(input), actual)
    }

    @ParameterizedTest
    @ValueSource(strings = ["changed", "missing", "extra", "symlink"])
    fun `unlisted missing replaced and linked input files are rejected`(variant: String, @TempDir root: File) {
        val file = File(root, "sample.jar").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val input = JvmBytecodeInput("original", "jars", "a".repeat(40), root.path,
            mapOf(file.name to CaptureFile(3, sha256(file.readBytes()))))
        when (variant) {
            "changed" -> file.writeBytes(byteArrayOf(4, 5, 6))
            "missing" -> file.delete()
            "extra" -> File(root, "other").writeText("unlisted")
            else -> Files.createSymbolicLink(File(root, "alias").toPath(), file.toPath())
        }

        assertFailsWith<IllegalArgumentException> { JvmBytecodeInputs.verify(listOf(input)) }
    }

    @Test
    fun `all listed bytes are authenticated before class parsing`(@TempDir root: File) {
        val bytes = byteArrayOf(1, 2, 3)
        File(root, "not-a-class.jar").writeBytes(bytes)
        val input = JvmBytecodeInput("original", "jars", "a".repeat(40), root.path,
            mapOf("not-a-class.jar" to CaptureFile(3, sha256(bytes))))

        JvmBytecodeInputs.verify(listOf(input))
    }

    @ParameterizedTest
    @ValueSource(strings = ["kind", "relative", "parent", "hash", "negative", "unknown", "duplicate", "escaped"])
    fun `invalid and ambiguous specifications fail before filesystem access`(variant: String) {
        val input = JvmBytecodeInput("original", "jars", "a".repeat(40), "/absent/inputs",
            mapOf("lib/sample.jar" to CaptureFile(3, "b".repeat(64))))
        val original = Json.encodeToString(JvmBytecodeSpecification.serializer(),
            JvmBytecodeSpecification(1, listOf(input)))
        val text = when (variant) {
            "kind" -> original.replace("\"jars\"", "\"selected-methods\"")
            "relative" -> original.replace("/absent/inputs", "relative")
            "parent" -> original.replace("lib/sample.jar", "../sample.jar")
            "hash" -> original.replace("b".repeat(64), "unhashed")
            "negative" -> original.replace("\"bytes\":3", "\"bytes\":-1")
            "unknown" -> original.replace("\"schema\":1", "\"schema\":1,\"unknown\":true")
            "duplicate" -> original.replace("\"schema\":1", "\"schema\":1,\"schema\":1")
            else -> original.replace("\"schema\":1", "\"schema\":1,\"\\u0073chema\":1")
        }

        assertFailsWith<IllegalArgumentException> { JvmBytecodeInputs.read(text) }
    }
}
