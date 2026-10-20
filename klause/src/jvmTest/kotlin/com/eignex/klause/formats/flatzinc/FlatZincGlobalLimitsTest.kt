package com.eignex.klause.formats.flatzinc

import kotlin.test.Test
import kotlin.test.assertFailsWith
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class FlatZincGlobalLimitsTest(private val source: String) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun sources(): List<Array<String>> = listOf(
            "constraint set_in(0,{4294967296});",
            "array[1..1] of set of int: a = [{4294967296}]; constraint array_set_element(1,a,{0});",
            "constraint set_lt({-2147483648},{});",
            "constraint global_cardinality([1],[1],[4294967297]);",
            "constraint global_cardinality_low_up([1],[1],[4294967297],[4294967297]);",
            "constraint inverse_offsets([1],4294967297,[1],4294967297);",
        ).map { arrayOf(it) }
    }

    @Test
    fun `unsupported global constants should decline instead of wrapping`() {
        assertFailsWith<FlatZincParseException> { parseFlatZinc("$source solve satisfy;", exactFloats = true) }
    }
}
