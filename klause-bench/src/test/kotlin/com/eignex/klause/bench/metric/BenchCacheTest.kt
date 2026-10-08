package com.eignex.klause.bench.metric

import com.eignex.klause.bench.runner.Budget
import com.eignex.klause.bench.source.CorpusFiles
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class BenchCacheTest {

    @Test
    fun `cache reuse requires the same executed build and validation policy`() {
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
            assertNotEquals(
                baseline,
                BenchCache.keyFor(model, null, "klause", budget, build, validationPolicy = PINNED_SOURCE_POLICY),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `settings with colliding output labels retain separate cache entries`() {
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
    fun `keyFor is the same for a plain and a compressed copy of an instance`() {
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
    fun `an oversized output keeps its status and objective lines and drops the model lines`() {
        val model = "v " + "1 ".repeat(5_000_000)
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
}
