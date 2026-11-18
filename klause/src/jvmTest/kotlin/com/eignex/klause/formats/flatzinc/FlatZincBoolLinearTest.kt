package com.eignex.klause.formats.flatzinc

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.propagation.bake
import org.junit.BeforeClass
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(Parameterized::class)
class FlatZincBoolLinearTest(private val constraint: String, private val expected: Set<String>) {
    companion object {
        @JvmStatic
        @BeforeClass
        fun warmRuntime() {
            val program = parseFlatZinc(
                "var bool: a; var bool: b; constraint bool_lin_le([2,-3],[a,b],-1); solve satisfy;",
            )
            BacktrackSolver(program.problem.bake()).enumerate(BacktrackParams(randomSeed = 0L)).toList()
        }

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun rows(): List<Array<Any>> = listOf(
            arrayOf("bool_lin_le([2,-3],[a,b],-1)", setOf("01", "11")),
            arrayOf("bool_lin_eq([2,-3],[a,b],-1)", setOf("11")),
            arrayOf("bool_lin_le([-1,-2],[a,b],-1)", setOf("10", "01", "11")),
            arrayOf("bool_lin_eq([-1,-2],[a,b],-1)", setOf("10")),
            arrayOf("bool_lin_le([2,-2,0],[a,a,b],0)", setOf("00", "01", "10", "11")),
            arrayOf("bool_lin_eq([2,-2,0],[a,a,b],0)", setOf("00", "01", "10", "11")),
            arrayOf("bool_lin_le([${Long.MIN_VALUE},${Long.MAX_VALUE}],[a,b],-1)", setOf("10", "11")),
            arrayOf("bool_lin_eq([${Long.MIN_VALUE},${Long.MAX_VALUE}],[a,b],-1)", setOf("11")),
            arrayOf("bool_lin_le([${Long.MIN_VALUE},-1],[a,b],${Long.MIN_VALUE})", setOf("10", "11")),
            arrayOf("bool_lin_eq([${Long.MIN_VALUE},-1],[a,b],${Long.MIN_VALUE})", setOf("10")),
            arrayOf("bool_lin_le([${Long.MAX_VALUE},${Long.MAX_VALUE},-1],[a,a,b],${Long.MAX_VALUE})",
                setOf("00", "01")),
            arrayOf("bool_lin_eq([${Long.MAX_VALUE},${Long.MAX_VALUE},-1],[a,a,b],${Long.MAX_VALUE})",
                emptySet<String>()),
            arrayOf("bool_lin_le([-2,1,-1],[true,a,false],-1)", setOf("00", "01", "10", "11")),
            arrayOf("bool_lin_eq([-2,1,-1],[true,a,false],-1)", setOf("10", "11")),
        )
    }

    @Test
    fun `signed Boolean rows should preserve exactly the source solutions`() {
        val program = parseFlatZinc("var bool: a; var bool: b; constraint $constraint; solve satisfy;")

        val assignments = BacktrackSolver(program.problem.bake()).enumerate(BacktrackParams(randomSeed = 0L))
            .map { sample ->
                listOf("a", "b").joinToString("") {
                    if (sample.bools[program.boolVarsByName.getValue(it)]) "1" else "0"
                }
            }
            .toSet()

        assertEquals(expected, assignments)
    }
}
