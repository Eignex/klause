package com.eignex.klause.formats.smtlib

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.factor.arithmetic.Power
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.ir.NonlinearIntegerProblem
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SmtLibProductsTest {
    @Test
    fun `bounded product lowering should preserve the complete source solution set`() {
        val parsed = SmtLib.parse(
            "(declare-const x Int) (declare-const y Int) " +
                "(assert (and (<= 1 x 3) (<= 1 y 3) (= (* x y) 6)))",
        )

        val values = BacktrackSolver(parsed.model.bake()).enumerate(BacktrackParams(maxDecisions = 100)).map { sample ->
            sample.intValue(parsed.intVarNames.getValue("x")) to sample.intValue(parsed.intVarNames.getValue("y"))
        }.toSet()

        assertEquals(setOf(2L to 3L, 3L to 2L), values)
    }

    @Test
    fun `bounded integer product exhaustion should prove source infeasibility`() {
        val parsed = SmtLib.parse(
            "(declare-const x Int) (declare-const y Int) " +
                "(assert (and (<= 1 x 3) (<= 1 y 3) (= (* x y) 5)))",
        )

        val result = BacktrackSolver(parsed.model.bake()).solve(BacktrackParams(maxDecisions = 100))

        assertIs<SolveResult.Unsat>(result)
    }

    @Test
    fun `bounded integer real products should use the shared mixed product factor`() {
        val parsed = SmtLib.parse(
            "(declare-const x Int) (declare-const y Real) " +
                "(assert (and (<= 1 x 3) (= (* (to_real x) y) 6)))",
        )

        assertTrue(parsed.model.factors.any { it is RealProduct })
    }

    @Test
    fun `real products should use the shared continuous product factor`() {
        val parsed = SmtLib.parse("(declare-const x Real) (assert (= (* x x) 2))")

        assertTrue(parsed.model.factors.any { it is ContinuousProduct })
    }

    @Test
    fun `bounded powers should retain one native relation`() {
        val parsed = SmtLib.parse("(declare-const x Int) (assert (and (<= 0 x 2) (= (^ x 4) 16)))")

        assertEquals(4, parsed.model.factors.filterIsInstance<Power>().single().exponent)
        assertTrue(parsed.model.factors.none { it is Product })
    }

    @Test
    fun `compound operand range checks should preserve sign reversal beyond word bounds`() {
        val source = "(declare-const x Int) (declare-const y Int) " +
            "(assert (= x (- 9223372036854775808))) (assert (<= 1 y 2)) " +
            "(assert (= (* (+ (- x) 1) y) 1))"

        val captured = SmtLib.parse(source)

        assertIs<NonlinearIntegerProblem>(captured.source.originalSource)
    }

    @Test
    fun `word overflowing and open products should retain exact source coordinates`() {
        val sources = listOf(
            "(declare-const x Int) (assert (= (* x x) 4))",
            "(declare-const x Int) (assert (and (<= 3037000500 x 3037000501) (= (* x x) 1)))",
            "(declare-const x Int) (declare-const y Real) (assert (= (* (to_real x) y) 1))",
            "(declare-const x Int) (declare-const y Real) (assert (= x 9007199254740993)) " +
                "(assert (= (* (to_real x) y) 1))",
        )

        for (source in sources) {
            val captured = SmtLib.parse(source)

            assertIs<NonlinearIntegerProblem>(captured.source.originalSource)
        }
    }
}
