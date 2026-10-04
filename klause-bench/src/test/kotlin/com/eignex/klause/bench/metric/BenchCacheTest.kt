package com.eignex.klause.bench.metric

import com.eignex.klause.bench.runner.Budget
import com.eignex.klause.bench.source.CorpusFiles
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class BenchCacheTest {

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
