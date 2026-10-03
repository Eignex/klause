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
}
