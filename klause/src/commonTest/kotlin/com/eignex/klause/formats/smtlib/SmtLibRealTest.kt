package com.eignex.klause.formats.smtlib

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.solver.pipeline.TheoryCompletion
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.parseBigInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SmtLibRealTest {
    @Test
    fun `wide real coefficients should retain original coordinates and exact witness values`() {
        val coefficient = "100000000000000000000000000000000000000000000000001"
        val source = SmtLib.parse("(declare-const x Real) (assert (= (* $coefficient x) 1))")

        val result = BacktrackSolver.source(source.model).solve()

        val point = assertIs<OpenTheoryResult.Sat>(result).assignment
        assertEquals(
            BigFraction.of(BIG_ONE, parseBigInt(coefficient)),
            TheoryCompletion.parseRational(point.realValue(0)),
        )
        assertEquals(1, source.model.numRealVars)
        assertEquals(1, source.model.factors.filterIsInstance<ExactLinear>().size)
    }

    @Test
    fun `wide reified strict comparisons should preserve an excluded boundary`() {
        val coefficient = "100000000000000000000000000000000000000000000000001"
        val source = SmtLib.parse(
            "(declare-const x Real) (assert (= (* $coefficient x) 1)) " +
                "(assert (or (< (* $coefficient x) 0) (> (* $coefficient x) 1)))",
        )

        val result = BacktrackSolver.source(source.model).solve()

        assertIs<OpenTheoryResult.Unsat>(result)
        assertEquals(1, source.model.numRealVars)
    }

    @Test
    fun `wide rational integer comparisons should preserve reified lattice truth`() {
        val numerator = "100000000000000000000000000000000000000000000000001"
        val denominator = "100000000000000000000000000000000000000000000000000"
        val source = SmtLib.parse(
            "(declare-const n Int) (declare-const b Bool) (assert (= n 1)) " +
                "(assert (= b (< (/ (* $numerator (to_real n)) $denominator) 1))) (assert (not b))",
        )

        val result = BacktrackSolver.source(source.model).solve()

        val point = assertIs<OpenTheoryResult.Sat>(result).assignment
        assertEquals("1", point.intValue(source.intVarNames.getValue("n")))
        assertEquals(false, point.boolValue(source.boolVarNames.getValue("b")))
        assertEquals(0, source.model.numRealVars)
    }
}
