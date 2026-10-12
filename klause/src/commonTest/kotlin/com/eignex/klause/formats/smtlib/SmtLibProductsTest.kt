package com.eignex.klause.formats.smtlib

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import kotlin.test.assertEquals
import kotlin.test.assertIs
import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.factor.arithmetic.RealProduct
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SmtLibProductsTest {
    @Test
    fun `bounded integer products use the shared product factor`() {
        val parsed = SmtLib.parse(
            "(declare-const x Int) (declare-const y Int) " +
                "(assert (and (<= 1 x 3) (<= 1 y 3) (= (* x y) 6)))",
        )

        assertTrue(parsed.model.factors.any { it is Product })
    }

    @Test
    fun `bounded product witnesses satisfy the original integer equation`() {
        val parsed = SmtLib.parse(
            "(declare-const x Int) (declare-const y Int) " +
                "(assert (and (<= 1 x 3) (<= 1 y 3) (= (* x y) 6)))",
        )

        val result = BacktrackSolver(parsed.model.bake()).solve(BacktrackParams(maxDecisions = 100))

        val sample = assertIs<SolveResult.Sat>(result).assignment
        val x = sample.intValue(parsed.intVarNames.getValue("x"))
        val y = sample.intValue(parsed.intVarNames.getValue("y"))
        assertEquals(6L, x * y)
    }

    @Test
    fun `bounded product lowering preserves the complete source solution set`() {
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
    fun `bounded integer product exhaustion proves source infeasibility`() {
        val parsed = SmtLib.parse(
            "(declare-const x Int) (declare-const y Int) " +
                "(assert (and (<= 1 x 3) (<= 1 y 3) (= (* x y) 5)))",
        )

        val result = BacktrackSolver(parsed.model.bake()).solve(BacktrackParams(maxDecisions = 100))

        assertIs<SolveResult.Unsat>(result)
    }

    @Test
    fun `bounded integer real products use the shared mixed product factor`() {
        val parsed = SmtLib.parse(
            "(declare-const x Int) (declare-const y Real) " +
                "(assert (and (<= 1 x 3) (= (* (to_real x) y) 6)))",
        )

        assertTrue(parsed.model.factors.any { it is RealProduct })
    }

    @Test
    fun `real products use the shared continuous product factor`() {
        val parsed = SmtLib.parse("(declare-const x Real) (assert (= (* x x) 2))")

        assertTrue(parsed.model.factors.any { it is ContinuousProduct })
    }

    @Test
    fun `bounded powers share squared intermediates`() {
        val parsed = SmtLib.parse("(declare-const x Int) (assert (and (<= 0 x 2) (= (^ x 4) 16)))")

        assertTrue(parsed.model.factors.count { it is Product } <= 3)
    }

    @Test
    fun `compound operand range checks preserve sign reversal beyond word bounds`() {
        val source = "(declare-const x Int) (declare-const y Int) " +
            "(assert (= x (- 9223372036854775808))) (assert (<= 1 y 2)) " +
            "(assert (= (* (+ (- x) 1) y) 1))"

        assertFailsWith<UnsupportedSmtException> { SmtLib.parse(source) }
    }

    @Test
    fun `word overflowing and open products decline finite lowering`() {
        val sources = listOf(
            "(declare-const x Int) (assert (= (* x x) 4))",
            "(declare-const x Int) (assert (and (<= 3037000500 x 3037000501) (= (* x x) 1)))",
            "(declare-const x Int) (declare-const y Real) (assert (= (* (to_real x) y) 1))",
            "(declare-const x Int) (declare-const y Real) (assert (= x 9007199254740993)) " +
                "(assert (= (* (to_real x) y) 1))",
        )

        for (source in sources) assertFailsWith<UnsupportedSmtException> { SmtLib.parse(source) }
    }
}
