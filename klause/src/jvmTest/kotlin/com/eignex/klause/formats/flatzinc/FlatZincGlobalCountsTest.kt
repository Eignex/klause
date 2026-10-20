package com.eignex.klause.formats.flatzinc

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.propagation.bake
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class FlatZincGlobalCountsTest(private val constraint: String, private val expected: Int) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun counts(): List<Array<Any>> = listOf(
            arrayOf("exactly_int(1,[4294967296],4294967296)", 1),
            arrayOf("at_least_int(1,[4294967296],4294967296)", 1),
            arrayOf("at_most_int(0,[4294967296],0)", 1),
            arrayOf("exactly_int(4294967296,[],1)", 0),
            arrayOf("at_least_int(4294967296,[],1)", 0),
            arrayOf("at_most_int(4294967296,[],1)", 1),
            arrayOf("count_eq([],1,4294967296)", 0),
            arrayOf("count_eq([1],1,4294967297)", 0),
            arrayOf("at_least_int(-1,[],1)", 1),
            arrayOf("at_most_int(-1,[],1)", 0),
        )
    }

    @Test
    fun `occurrence comparisons should preserve wide constants and empty arrays`() {
        val program = parseFlatZinc("constraint $constraint; solve satisfy;", exactFloats = true)

        val samples = BacktrackSolver(program.sourceProgram.problem.bake()).enumerate().toList()

        assertEquals(expected, samples.size)
    }
}
