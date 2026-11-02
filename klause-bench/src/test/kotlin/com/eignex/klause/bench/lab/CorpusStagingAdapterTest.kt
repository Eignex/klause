package com.eignex.klause.bench.lab

import com.eignex.lab.bench.BenchInputRoot
import com.eignex.lab.bench.BenchProtocol
import com.eignex.lab.bench.BenchRequest
import kotlinx.serialization.encodeToString
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
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

    @ParameterizedTest
    @CsvSource("forward, cache, 8", "reverse, cache, 64", "forward, temporary, 64", "reverse, temporary, 8")
    fun `staging controls retain the same frozen source comparison identity`(
        order: String,
        target: String,
        bufferKiB: Int,
    ) {
        val input = FrozenStagingInput("offline", "tiny", "DIMACS", "offline/tiny.cnf.zst",
            "1".repeat(64), "2".repeat(64))
        val parameters = mapOf("inputs" to BenchProtocol.json.encodeToString(listOf(input)), "timeout-ms" to "10000")

        val baseline = CorpusStagingSuite.plan(BenchRequest(parameters)).single()
        val control = CorpusStagingSuite.plan(BenchRequest(parameters +
            mapOf("order" to order, "staging-target" to target, "staging-buffer-kib" to bufferKiB.toString()))).single()

        assertEquals(baseline.inputs, control.inputs)
        assertEquals(baseline.attributes["comparisonKey"], control.attributes["comparisonKey"])
        assertEquals(baseline.parameters["timeout-ms"], control.parameters["timeout-ms"])
        assertEquals(order, control.parameters["order"])
        assertEquals(target, control.parameters["staging-target"])
        assertEquals(bufferKiB.toString(), control.parameters["staging-buffer-kib"])
    }
}
