package com.eignex.klause.bench.metric

import com.eignex.klause.bench.runner.Budget
import com.eignex.klause.bench.source.CorpusFiles
import java.io.File
import java.nio.file.Files
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame

class BenchCacheTest {

    @Test
    fun `cache reuse should require the same executed build and validation policy`() {
        val root = Files.createTempDirectory("benchcache").toFile()
        try {
            val model = File(root, "model.fzn").apply { writeText("solve satisfy;") }
            val build = BuildProvenance(mapOf("lib/solver.jar" to "abc"), emptyMap(), emptyMap(), "Linux", "amd64")
            val budget = Budget(1_000)
            val baseline = BenchCache.keyFor(model, null, "klause", budget, build)

            for (changed in listOf(
                build.copy(distribution = mapOf("lib/solver.jar" to "def")),
                build.copy(runtime = mapOf("bin/java" to "def")),
                build.copy(runtimeOptions = mapOf("JAVA_OPTS" to "--add-modules=jdk.incubator.vector")),
            )) {
                assertNotEquals(baseline, BenchCache.keyFor(model, null, "klause", budget, changed))
            }
            for (policy in listOf(PINNED_SOURCE_POLICY, XCSP_SOURCE_POLICY, MPS_SOURCE_POLICY)) {
                assertNotEquals(
                    baseline,
                    BenchCache.keyFor(model, null, "klause", budget, build, validationPolicy = policy),
                )
            }
            assertNotEquals(
                BenchCache.keyFor(model, null, "klause", budget, build,
                    validationPolicy = "mps-original-ground-witness-v1"),
                BenchCache.keyFor(model, null, "klause", budget, build, validationPolicy = MPS_SOURCE_POLICY),
            )
            assertNotEquals(
                BenchCache.keyFor(model, null, "klause", budget, build, validationPolicy = "xcsp3-source-witness-v1"),
                BenchCache.keyFor(model, null, "klause", budget, build, validationPolicy = XCSP_SOURCE_POLICY),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `settings with colliding output labels should retain separate cache entries`() {
        val root = Files.createTempDirectory("benchcache").toFile()
        try {
            val model = File(root, "model.fzn").apply { writeText("solve satisfy;") }
            val build = BuildProvenance(emptyMap(), emptyMap(), emptyMap(), "Linux", "amd64")
            val budget = Budget(1_000)
            val first = SolverInvocation.Settings(params = listOf("arm=a/b"))
            val second = SolverInvocation.Settings(params = listOf("arm=a_b"))
            val tag = SolveMetric.configTag("klause", first, budget)
            assertEquals(tag, SolveMetric.configTag("klause", second, budget))

            val firstKey = BenchCache.keyFor(model, null, tag, budget, build, first)
            val secondKey = BenchCache.keyFor(model, null, tag, budget, build, second)

            assertNotEquals(firstKey, secondKey)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `keyFor should match for a plain and a compressed copy of an instance`() {
        val root = Files.createTempDirectory("benchcache").toFile()
        try {
            val plain = File(root, "plain/inst.opb").apply {
                parentFile.mkdirs()
                writeText("* #variable= 2 #constraint= 1\n+1 x1 +1 x2 >= 1 ;\n")
            }
            val packed = CorpusFiles.compress(plain.copyTo(File(root, "packed/inst.opb")))

            val budget = Budget(1_000)
            assertEquals(
                BenchCache.keyFor(plain, null, "clasp", budget),
                BenchCache.keyFor(packed, null, "clasp", budget),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `an oversized output should keep status and objective lines and drop model lines`() {
        val model = "v " + "1 ".repeat(1_024).repeat(4_097)
        val result = SolverInvocation.Result(
            feasible = true,
            objective = 3.0,
            timeToBestMs = 1L,
            proven = false,
            stats = emptyMap(),
            rawOutput = "o 3\n$model\ns SATISFIABLE",
            command = "",
        )

        assertEquals("o 3\ns SATISFIABLE", BenchCache.compact(result).rawOutput)
    }
    @ParameterizedTest
    @ValueSource(ints = [-1, 0])
    fun `outputs within the character limit should retain the original result`(offset: Int) {
        val raw = ("v " + "\u00e9 ".repeat(1_024).repeat(4_097)).take(8 * 1024 * 1024 + offset)
        val result = SolverInvocation.Result(
            feasible = true,
            objective = 3.0,
            timeToBestMs = 1L,
            proven = false,
            stats = emptyMap(),
            rawOutput = raw,
            command = "",
        )

        val compacted = BenchCache.compact(result)

        assertSame(result, compacted)
    }

    @ParameterizedTest
    @ValueSource(strings = ["LF", "CR", "CRLF"])
    fun `oversized output should normalize retained line endings and preserve trailing empty lines`(ending: String) {
        val newline = when (ending) {
            "LF" -> "\n"
            "CR" -> "\r"
            else -> "\r\n"
        }
        val model = "v " + "1 ".repeat(1_024).repeat(4_097)
        val raw = listOf(model, "o 3", "", "v", "v\tkeep", "value", " v keep", "c note", "s SATISFIABLE", "")
            .joinToString(newline)
        val result = SolverInvocation.Result(
            feasible = true,
            objective = 3.0,
            timeToBestMs = 1L,
            proven = false,
            stats = emptyMap(),
            rawOutput = raw,
            command = "",
        )

        val compacted = BenchCache.compact(result)

        assertEquals(result.copy(rawOutput = "o 3\n\nv\nv\tkeep\nvalue\n v keep\nc note\ns SATISFIABLE\n"), compacted)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `an oversized model alone should be dropped with or without a trailing newline`(trailingNewline: Boolean) {
        val model = ("v " + "1 ".repeat(1_024).repeat(4_097)).take(8 * 1024 * 1024 + 1)
        val result = SolverInvocation.Result(
            feasible = true,
            objective = 3.0,
            timeToBestMs = 1L,
            proven = false,
            stats = emptyMap(),
            rawOutput = model + if (trailingNewline) "\n" else "",
            command = "",
        )

        val compacted = BenchCache.compact(result)

        assertEquals(result.copy(rawOutput = ""), compacted)
    }

    @Test
    fun `oversized non-model output should be retained`() {
        val raw = "c " + "1 ".repeat(1_024).repeat(4_097)
        val result = SolverInvocation.Result(
            feasible = true,
            objective = 3.0,
            timeToBestMs = 1L,
            proven = false,
            stats = emptyMap(),
            rawOutput = raw,
            command = "",
        )

        val compacted = BenchCache.compact(result)

        assertEquals(result, compacted)
    }
}
