package com.eignex.klause.bench.lab

import com.eignex.lab.bench.BenchCase
import com.eignex.lab.bench.BenchInput
import com.eignex.lab.bench.BenchInputRoot
import com.eignex.lab.bench.BenchProtocol
import com.eignex.lab.bench.BenchRequest
import kotlinx.serialization.encodeToString
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FrozenSourceAdapterTest {
    @ParameterizedTest
    @CsvSource(
        "cache,DIMACS", "temporary,DIMACS", "cli,DIMACS",
        "cache,WCNF", "temporary,WCNF", "cli,WCNF",
        "cache,MPS", "temporary,MPS", "cli,MPS",
        "cache,OPB", "temporary,OPB", "cli,OPB",
        "cache,MINIZINC", "temporary,MINIZINC",
    )
    fun `ordinary frozen planning preserves source identity across seeded repeat blocks`(
        target: String,
        format: String,
    ) {
        val extension = when (format) {
            "DIMACS" -> "cnf.zst"
            "MINIZINC" -> "mzn"
            else -> "${format.lowercase()}.zst"
        }
        val input = BenchInput("offline/tiny.$extension", BenchInputRoot.CORPUS, "model", "1".repeat(64))
        val template = BenchCase("original", inputs = listOf(input), attributes = mapOf(
            "suite" to "offline", "problem" to "tiny", "format" to format, "category" to "UNSAT",
            "sourcePlainSha256" to "2".repeat(64), "labInputDigest" to "stale"))

        val cases = FrozenSourceSuite.plan(BenchRequest(mapOf(
            "cases" to BenchProtocol.json.encodeToString(listOf(template)), "source-staging" to target,
            "solver-seeds" to "1,3", "repeats" to "2", "timeout-ms" to "10000")))

        assertEquals(4, cases.map { it.attributes["comparisonKey"] }.distinct().size)
        assertEquals(listOf(listOf(input)), cases.map { it.inputs }.distinct())
        assertEquals(setOf("2".repeat(64)), cases.map { it.attributes["sourcePlainSha256"] }.toSet())
        assertEquals(setOf(target), cases.map { it.parameters["source-staging"] }.toSet())
        assertEquals(setOf("10000"), cases.map { it.parameters["timeout-ms"] }.toSet())
        assertEquals(setOf<String?>(null), cases.map { it.attributes["labInputDigest"] }.toSet())
    }

    @ParameterizedTest
    @CsvSource("true", "false")
    fun `frozen MiniZinc data requires its own identity`(withIdentity: Boolean) {
        val inputs = listOf(
            BenchInput("offline/model.mzn", BenchInputRoot.CORPUS, "model", "1".repeat(64)),
            BenchInput("offline/data.dzn", BenchInputRoot.CORPUS, "data", "2".repeat(64)),
        )
        val attributes = mapOf("suite" to "offline", "problem" to "tiny", "format" to "MINIZINC",
            "category" to "OPTIMIZATION", "sourcePlainSha256" to "1".repeat(64)) +
            if (withIdentity) mapOf("sourceDataPlainSha256" to "2".repeat(64)) else emptyMap()
        val request = BenchRequest(mapOf("cases" to BenchProtocol.json.encodeToString(
            listOf(BenchCase("original", inputs = inputs, attributes = attributes)))))

        if (withIdentity) {
            val cases = FrozenSourceSuite.plan(request)

            assertEquals(inputs, cases.single().inputs)
            assertEquals("2".repeat(64), cases.single().attributes["sourceDataPlainSha256"])
        } else {
            assertFailsWith<IllegalArgumentException> { FrozenSourceSuite.plan(request) }
        }
    }

    @Test
    fun `planning names the missing decoded identity of each rejected case`() {
        val input = BenchInput("miplib2017/timtab1CUTS.mps.zst", BenchInputRoot.CORPUS, "model", "1".repeat(64))
        val template = BenchCase("timtab1CUTS", inputs = listOf(input), attributes = mapOf("suite" to "miplib2017",
            "problem" to "timtab1CUTS", "format" to "MPS", "category" to "OPTIMIZATION"))
        val request = BenchRequest(mapOf("cases" to BenchProtocol.json.encodeToString(listOf(template))))

        val failure = assertFailsWith<IllegalArgumentException> { FrozenSourceSuite.plan(request) }

        assertContains(failure.message.orEmpty(), "case 'timtab1CUTS': attribute sourcePlainSha256 is missing")
    }
}
