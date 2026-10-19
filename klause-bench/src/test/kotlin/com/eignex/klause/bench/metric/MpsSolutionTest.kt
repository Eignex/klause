package com.eignex.klause.bench.metric

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.Test

class MpsSolutionTest {
    private val record = SolveRecord(
        "fixture", "klause", null, 1, "free", 1, 10_000, "optimize", false,
        true, 0.125, 1, proven = true, gitSha = "test", timestamp = "test", command = "cli --exact",
        finalWitness = "v x=1/8", exactObjective = "1/8",
    )

    @Test
    fun `terminating rational coordinates should convert without rounding`() {
        val solution = MpsSolution.convert(record, "o 0.125\nv x=1/8\n", true)

        assertEquals("=obj= 0.125\nx 0.125\n", solution)
    }

    @ParameterizedTest
    @ValueSource(strings = ["v x=1 x=2", "v x=1/3", "v x=nan", "v x=1/0", "v x=1e999999", "v x=1=2"])
    fun `unsupported or repeated coordinates should be declined`(point: String) {
        assertFailsWith<RuntimeException> {
            MpsSolution.convert(record.copy(finalWitness = point), "o 1/8\n$point\n", true)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["point", "objective", "policy", "semantics"])
    fun `producer custody mismatches should be declined`(variant: String) {
        val raw = when (variant) {
            "point" -> "o 1/8\nv x=2\n"
            "objective" -> "o 2\nv x=1/8\n"
            "semantics" -> "c klause-source-semantics: format=MPS overrides=true\no 1/8\nv x=1/8\n"
            else -> "o 1/8\nv x=1/8\n"
        }

        assertFailsWith<IllegalArgumentException> { MpsSolution.convert(record, raw, variant != "policy") }
    }
}
