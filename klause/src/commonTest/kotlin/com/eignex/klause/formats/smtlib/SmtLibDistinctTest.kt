package com.eignex.klause.formats.smtlib

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.global.Distinct
import com.eignex.klause.ir.PolynomialDistinct
import com.eignex.klause.ir.TranscendentalProblem
import com.eignex.klause.propagation.bake
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.OpenTheoryPipeline
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.solver.pipeline.execute
import com.eignex.klause.solver.pipeline.pipelineRequest
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.parseBigInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SmtLibDistinctTest {
    @Test
    fun `open distinct integer positions should retain one source global`() {
        val source = SmtLib.parse("(declare-const x Int) (declare-const y Int) (assert (distinct x y x))")

        val relation = assertIs<AllDifferent>(source.model.factors.single())

        assertEquals(listOf(0, 1, 0), relation.vars.toList())
        assertEquals(null, source.model.intDomainOrNull(0))
    }

    @Test
    fun `repeated asserted integer positions should reject every finite point`() {
        val source = SmtLib.parse("(declare-const x Int) (assert (<= 0 x 1)) (assert (distinct x x))")

        val result = BacktrackSolver(source.model.bake()).solve()

        assertIs<SolveResult.Unsat>(result)
    }

    @Test
    fun `asserted Boolean distinct should retain literal complements without integer channels`() {
        val source = SmtLib.parse("(declare-const b Bool) (declare-const c Bool) (assert (distinct b (not c)))")

        val points = BacktrackSolver(source.model.bake()).enumerate().map {
            it.boolValue(source.boolVarNames.getValue("b")) to it.boolValue(source.boolVarNames.getValue("c"))
        }.toSet()

        assertEquals(0, source.model.numIntVars)
        assertEquals(1, source.model.factors.filterIsInstance<Distinct>().size)
        assertEquals(setOf(false to false, true to true), points)
    }

    @Test
    fun `a false distinct reifier should require an original affine collision`() {
        val source = SmtLib.parse("(declare-const x Int) (declare-const y Int) " +
            "(assert (<= 0 x 1)) (assert (<= 0 y 1)) (assert (not (distinct (+ x 1) y)))")

        val points = BacktrackSolver(source.model.bake()).enumerate().map {
            it.intValue(source.intVarNames.getValue("x")) to it.intValue(source.intVarNames.getValue("y"))
        }.toSet()

        assertEquals(setOf(0L to 1L), points)
        assertEquals(1, source.model.factors.filterIsInstance<Distinct>().size)
    }

    @Test
    fun `wide affine distinct coefficients should retain their original integer coordinates`() {
        val coefficient = "100000000000000000000000000000000000001"
        val source = SmtLib.parse("(declare-const x Int) (declare-const y Int) " +
            "(assert (distinct (* $coefficient x) (* $coefficient y)))")

        val relation = source.model.factors.filterIsInstance<Distinct>().single()

        assertEquals(2, source.model.numIntVars)
        assertEquals(List(2) { listOf(BigFraction.of(parseBigInt(coefficient), BIG_ONE)) },
            relation.operands.map { it.coefficients })
    }

    @Test
    fun `native polynomial distinct should reject a nonadjacent source collision`() {
        val source = SmtLibPolynomial.parse("(declare-const x Real) (assert (distinct x 1 x))")

        val result = OpenTheoryPipeline.execute(source.realSource().pipelineRequest(),
            engine = FiniteEngine.BACKTRACK).result

        assertEquals(3, assertIs<PolynomialDistinct>(source.formula).operands.size)
        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `negated function distinct should retain invalid logarithm domains`() {
        val input = SmtLib.parse("(assert (not (distinct (log 0) (log 0))))")
        val source = assertIs<TranscendentalProblem>(input.source.originalSource)

        val result = OpenTheoryPipeline.execute(source.pipelineRequest(), engine = FiniteEngine.BACKTRACK).result

        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `a true alternative should retain all function distinct operand domains`() {
        val input = SmtLib.parse("(assert (or true (distinct (log 0) (log 0))))")
        val source = assertIs<TranscendentalProblem>(input.source.originalSource)

        val result = OpenTheoryPipeline.execute(source.pipelineRequest(), engine = FiniteEngine.BACKTRACK).result

        assertIs<OpenTheoryResult.Unsat>(result)
    }
}
