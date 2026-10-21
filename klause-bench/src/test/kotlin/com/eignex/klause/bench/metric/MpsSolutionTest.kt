package com.eignex.klause.bench.metric

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
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
    @CsvSource(
        "1007.9999999999998863131622783839702606201171875,1007.9999999999998863131622783839702606201171875",
        "-0.0000000000001136868377216160297393798828125,-1.136868377216160297393798828125E-13",
        "1564851723957962726619/1759218604441600,889515.2199999999576328946204739622771739959716796875",
    )
    fun `long terminating coordinates and objectives should convert without rounding`(value: String, decimal: String) {
        val point = record.copy(finalWitness = "v x=$value", exactObjective = value)

        val solution = MpsSolution.convert(point, "o $value\nv x=$value\n", true)

        assertEquals("=obj= $decimal\nx $decimal\n", solution)
    }

    @ParameterizedTest
    @ValueSource(ints = [31, 32, 159])
    fun `solution precision within the upstream buffer profile should be preserved`(digits: Int) {
        val value = "9".repeat(digits)
        val point = record.copy(finalWitness = "v x=$value", exactObjective = value)

        val solution = MpsSolution.convert(point, "o $value\nv x=$value\n", true)

        assertEquals("=obj= $value\nx $value\n", solution)
    }

    @ParameterizedTest
    @ValueSource(strings = ["digits", "positive-exponent", "negative-exponent", "expanded-integer", "expanded-fraction"])
    fun `solution numbers exceeding the upstream buffer profile should be declined`(variant: String) {
        val value = when (variant) {
            "digits" -> "1".repeat(160)
            "positive-exponent" -> "1e129"
            "negative-exponent" -> "1e-129"
            "expanded-integer" -> "${"1".repeat(32)}e128"
            else -> "0.${"1".repeat(32)}e-128"
        }
        val point = record.copy(finalWitness = "v x=$value", exactObjective = value)

        assertFailsWith<IllegalArgumentException> {
            MpsSolution.convert(point, "o $value\nv x=$value\n", true)
        }
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
