package com.eignex.klause.bench.lab

import com.eignex.klause.bench.metric.SolverInvocation
import com.eignex.klause.bench.runner.Budget
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.CorpusFiles
import java.nio.file.Files
import java.util.Base64
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals

class LpLibraryAdapterTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `library callers should receive original source bytes from either storage form`(compressed: Boolean) {
        val root = Files.createTempDirectory("lp-library-source").toFile()
        try {
            val fixture = CorpusFetcher.workspaceRoot().resolve("klause-bench/smoke-corpus/mps/pure-real-min.mps")
            val body = fixture.readText()
            val source = root.resolve("point.mps").apply { writeText(body) }
            val input = if (compressed) CorpusFiles.compress(source) else source
            var observed: String? = null

            invokeLibrarySource(input, Budget(10_000), 3L) { arguments, _ ->
                val encoded = arguments.single { it.startsWith("source64=") }.substringAfter('=')
                observed = Base64.getDecoder().decode(encoded).toString(Charsets.UTF_8)
                SolverInvocation.Result(null, null, null, proven = false,
                    stats = emptyMap(), rawOutput = "", command = "fixture")
            }

            assertEquals(body, observed)
        } finally {
            root.deleteRecursively()
        }
    }
}
