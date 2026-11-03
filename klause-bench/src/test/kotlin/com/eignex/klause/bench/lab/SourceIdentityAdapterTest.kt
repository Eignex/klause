package com.eignex.klause.bench.lab

import com.eignex.klause.bench.metric.sha256
import com.eignex.lab.bench.BenchCase
import com.eignex.lab.bench.BenchContext
import com.eignex.lab.bench.BenchInput
import com.eignex.lab.bench.BenchInputRoot
import com.eignex.lab.bench.BenchProtocol
import com.eignex.lab.bench.BenchRequest
import com.eignex.lab.bench.BenchStatus
import com.github.luben.zstd.Zstd
import java.nio.file.Files
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class SourceIdentityAdapterTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `source identity hashes decoded bytes after verifying the selected input`(compressed: Boolean) {
        val root = Files.createTempDirectory("source-identity-")
        try {
            val corpus = Files.createDirectory(root.resolve("corpus"))
            val out = Files.createDirectory(root.resolve("out"))
            val plain = "hello\n".toByteArray()
            val packed = if (compressed) Zstd.compress(plain) else plain
            val name = if (compressed) "tiny.opb.zst" else "tiny.opb"
            Files.write(corpus.resolve(name), packed)
            val template = BenchCase("original", inputs = listOf(
                BenchInput(name, BenchInputRoot.CORPUS, "model", sha256(packed))),
                attributes = mapOf("suite" to "offline", "problem" to "tiny"))
            val case = SourceIdentitySuite.plan(BenchRequest(mapOf(
                "cases" to BenchProtocol.json.encodeToString(listOf(template))))).single()

            val result = SourceIdentitySuite.run(case, BenchContext(out, corpusDirectory = corpus))

            assertEquals(BenchStatus.SUCCESS, result.status)
            val details = requireNotNull(result.details)
            assertEquals("5891b5b522d5df086d0ff0b110fbd9d21bb4fc7163af34d08286a2e846f6be03",
                details.getValue("plainSha256").jsonPrimitive.content)
            assertEquals("6", details.getValue("plainBytes").jsonPrimitive.content)
            assertEquals(sha256(packed), details.getValue("packedSha256").jsonPrimitive.content)
            assertEquals("false", details.getValue("solverExecuted").jsonPrimitive.content)
            assertEquals("source-identity.json", result.artifacts.single().path)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `mismatched input bytes withhold the plain source receipt`(compressed: Boolean) {
        val root = Files.createTempDirectory("source-identity-")
        try {
            val corpus = Files.createDirectory(root.resolve("corpus"))
            val out = Files.createDirectory(root.resolve("out"))
            val plain = "hello\n".toByteArray()
            val name = if (compressed) "tiny.opb.zst" else "tiny.opb"
            Files.write(corpus.resolve(name), if (compressed) Zstd.compress(plain) else plain)
            Files.writeString(out.resolve("source-identity.json"), "stale receipt")
            val case = BenchCase("original", inputs = listOf(
                BenchInput(name, BenchInputRoot.CORPUS, "model", "0".repeat(64))),
                attributes = mapOf("suite" to "offline", "problem" to "tiny"))

            assertFailsWith<IllegalArgumentException> {
                SourceIdentitySuite.run(case, BenchContext(out, corpusDirectory = corpus))
            }

            assertFalse(Files.exists(out.resolve("source-identity.json")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
