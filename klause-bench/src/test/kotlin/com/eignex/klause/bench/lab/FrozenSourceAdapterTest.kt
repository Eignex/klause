package com.eignex.klause.bench.lab

import com.eignex.lab.bench.BenchCase
import com.eignex.lab.bench.BenchInput
import com.eignex.lab.bench.BenchInputRoot
import com.eignex.lab.bench.BenchProtocol
import com.eignex.lab.bench.BenchRequest
import kotlinx.serialization.encodeToString
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals

class FrozenSourceAdapterTest {
    @ParameterizedTest
    @ValueSource(strings = ["cache", "temporary"])
    fun `ordinary frozen planning preserves source identity across seeded repeat blocks`(target: String) {
        val input = BenchInput("offline/tiny.cnf.zst", BenchInputRoot.CORPUS, "model", "1".repeat(64))
        val template = BenchCase("original", inputs = listOf(input), attributes = mapOf(
            "suite" to "offline", "problem" to "tiny", "format" to "DIMACS", "category" to "UNSAT",
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
}
