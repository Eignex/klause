package com.eignex.klause.bench.lab

import com.eignex.lab.bench.BenchInputRoot
import com.eignex.lab.bench.BenchProtocol
import com.eignex.lab.bench.BenchRequest
import kotlinx.serialization.encodeToString
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CorpusStagingAdapterTest {
    @Test
    fun `frozen source planning preserves both byte identities without resolving a corpus`() {
        val input = FrozenStagingInput("offline", "tiny", "DIMACS", "offline/tiny.cnf.zst",
            "1".repeat(64), "2".repeat(64))

        val case = CorpusStagingSuite.plan(BenchRequest(mapOf(
            "inputs" to BenchProtocol.json.encodeToString(listOf(input)), "timeout-ms" to "10000"))).single()

        assertEquals(input.path, case.inputs.single().path)
        assertEquals(BenchInputRoot.CORPUS, case.inputs.single().root)
        assertEquals(input.packedSha256, case.inputs.single().sha256)
        assertEquals(input.plainSha256, case.parameters["plain-sha256"])
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "bad", "0000"])
    fun `frozen source planning rejects an unavailable plain byte identity`(hash: String) {
        val input = FrozenStagingInput("offline", "tiny", "DIMACS", "offline/tiny.cnf.zst", "1".repeat(64), hash)

        assertFailsWith<IllegalArgumentException> {
            CorpusStagingSuite.plan(BenchRequest(mapOf(
                "inputs" to BenchProtocol.json.encodeToString(listOf(input)), "timeout-ms" to "10000")))
        }
    }

    @Test
    fun `reversed controls retain the same frozen source comparison identity`() {
        val input = FrozenStagingInput("offline", "tiny", "DIMACS", "offline/tiny.cnf.zst",
            "1".repeat(64), "2".repeat(64))
        val parameters = mapOf("inputs" to BenchProtocol.json.encodeToString(listOf(input)), "timeout-ms" to "10000")

        val forward = CorpusStagingSuite.plan(BenchRequest(parameters + ("order" to "forward"))).single()
        val reverse = CorpusStagingSuite.plan(BenchRequest(parameters + ("order" to "reverse"))).single()

        assertEquals(forward.inputs, reverse.inputs)
        assertEquals(forward.attributes["comparisonKey"], reverse.attributes["comparisonKey"])
        assertEquals("forward", forward.parameters["order"])
        assertEquals("reverse", reverse.parameters["order"])
    }
}
