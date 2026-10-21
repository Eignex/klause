package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.PresolveShared.withSourcePassDelta
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class OriginalClauseSubsumptionTest {
    private fun clause(vararg literals: Int) = Clause(literals)
    private fun problem(vararg clauses: Clause) = Problem(3, 0, emptyArray(), clauses.toList())

    private fun models(factors: List<Factor>, variables: Int): Set<Int> = (0 until (1 shl variables)).filter { mask ->
        factors.filterIsInstance<Clause>().all { clause ->
            clause.literals.any { Lit.evaluate(it, mask and (1 shl Lit.variable(it)) != 0) }
        }
    }.toSet()

    private fun checkModels(problem: Problem, delta: SourceDelta) {
        val expected = models(problem.factors.toList(), problem.numBoolVars)
        val reduced = if (delta.infeasible) {
            emptySet()
        } else {
            val result = assertNotNull(problem.withSourcePassDelta(delta))
            assertEquals(problem.numBoolVars, result.numBoolVars)
            assertTrue(delta.rebuild.isEmpty)
            models(result.factors.toList(), result.numBoolVars)
        }
        assertEquals(expected, reduced)
    }

    private fun clauses(variables: Int): List<Clause> {
        var count = 1
        repeat(variables) { count *= 3 }
        return (1 until count).map { encoded ->
            var remaining = encoded
            val literals = ArrayList<Int>()
            repeat(variables) { variable ->
                val digit = remaining % 3
                remaining /= 3
                if (digit != 0) literals.add(Lit.make(variable, digit == 1))
            }
            Clause(literals.toIntArray())
        }
    }

    @Test
    fun `all pairs of three variable clauses should preserve every assignment`() {
        val clauses = clauses(3)
        for (first in clauses) {
            for (second in clauses) {
            val problem = problem(first, second)
            checkModels(problem, OriginalClauseSubsumption.simplify(problem))
        }
        }
    }

    @Test
    fun `all triples of two variable clauses should preserve every assignment`() {
        val clauses = clauses(2)
        for (first in clauses) {
            for (second in clauses) {
                for (third in clauses) {
            val problem = Problem(2, 0, emptyArray(), listOf(first, second, third))
            checkModels(problem, OriginalClauseSubsumption.simplify(problem))
        }
            }
        }
    }

    @Test
    fun `resolution should retain its premise and remove only the opposite target literal`() {
        val premise = clause(0, 2)
        val problem = problem(premise, clause(1, 2, 4))

        val delta = OriginalClauseSubsumption.simplify(problem)

        assertEquals(listOf(1), delta.droppedIndices.toList())
        assertEquals(setOf(2, 4), (delta.addedFactors.single() as Clause).literals.toSet())
        assertSame(premise, assertNotNull(problem.withSourcePassDelta(delta)).factors.first())
        checkModels(problem, delta)
    }

    @Test
    fun `duplicate premises should keep one live clause across batched deletion`() {
        val problem = problem(clause(0, 2), clause(2, 0), clause(0, 2, 4), clause(1, 2, 4))

        val delta = OriginalClauseSubsumption.simplify(problem)

        val reduced = assertNotNull(problem.withSourcePassDelta(delta))
        assertEquals(1, reduced.factors.filterIsInstance<Clause>().count { it.literals.toSet() == setOf(0, 2) })
        checkModels(problem, delta)
    }

    @Test
    fun `a shortened clause should revisit earlier clauses as a live premise`() {
        val problem = problem(clause(0, 2, 4), clause(1, 2), clause(0, 2))

        val delta = OriginalClauseSubsumption.simplify(problem)

        val reduced = assertNotNull(problem.withSourcePassDelta(delta))
        assertEquals(listOf(setOf(2)), reduced.factors.filterIsInstance<Clause>().map { it.literals.toSet() })
        checkModels(problem, delta)
    }

    @Test
    fun `two polarity flips and tautological premises should not strengthen a clause`() {
        for (premise in listOf(clause(0, 2), clause(0, 1))) {
            val problem = problem(premise, clause(1, 3, 4))
            assertTrue(OriginalClauseSubsumption.simplify(problem).isEmpty)
        }
    }

    @Test
    fun `a unit conflict should refute without constructing an empty clause`() {
        val problem = problem(clause(0), clause(1))

        val delta = OriginalClauseSubsumption.simplify(problem)

        assertTrue(delta.infeasible)
        checkModels(problem, delta)
    }

    @Test
    fun `clauses outside the width envelope should remain untouched`() {
        val problem = Problem(9, 0, emptyArray(), listOf(clause(0, 2), Clause(IntArray(9) { 2 * it })))

        assertTrue(OriginalClauseSubsumption.simplify(problem).isEmpty)
    }

    @Test
    fun `source scheduling should preserve objective columns and mixed theory participation when counting`() {
        val channel = ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.GE, 1)
        val realRow = Linear(
            intVars = intArrayOf(),
            intCoeffs = doubleArrayOf(),
            realVars = intArrayOf(0),
            realCoeffs = doubleArrayOf(1.0),
            op = LinearOp.GE,
            bound = 0.0,
        )
        val problem = Problem(
            3,
            1,
            arrayOf(IntDomain(0, 2)),
            listOf(clause(0, 2), clause(1, 2, 4), channel, realRow),
            numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
        )
        val context = PresolveContext(solutionSetSensitive = true, objectiveBoolCoeffs = mapOf(0 to 7L, 1 to -3L))

        val prepared = Presolver.runSource(problem, PresolveConfig.parse("off,+clause-subsume"), context)

        assertEquals(listOf(PresolvePass.SUBSUME_CLAUSES), prepared.passesFired)
        assertEquals(3, prepared.problem.numBoolVars)
        assertSame(channel, prepared.problem.factors.first { it === channel })
        assertSame(realRow, prepared.problem.factors.first { it === realRow })
        assertTrue(prepared.rebuild.isEmpty)
        assertEquals(models(problem.factors.toList(), 3), models(prepared.problem.factors.toList(), 3))
        assertTrue(prepared.problem.factors.filterIsInstance<Clause>().any { it.literals.toSet() == setOf(2, 4) })
    }

    @Test
    fun `candidate and literal caps should stop optional work deterministically`() {
        val problem = problem(clause(0, 2), clause(1, 2, 4))
        for (limits in listOf(
            OriginalClauseSubsumption.Limits(candidates = 0),
            OriginalClauseSubsumption.Limits(literals = 0),
        )) {
            val budget = PresolveBudget(Long.MAX_VALUE)

            val delta = OriginalClauseSubsumption.simplify(problem, budget.orSpent(Cancellation.Never), limits)

            assertTrue(delta.isEmpty)
            assertTrue(budget.spent() in 1L..40L)
        }
    }

    @Test
    fun `dense unsuccessful scans should obey the candidate cap`() {
        val problem = Problem(66, 0, emptyArray(), List(64) { clause(0, 2 * (it + 1), 2 * (it + 2)) })
        val budget = PresolveBudget(Long.MAX_VALUE)

        val delta = OriginalClauseSubsumption.simplify(
            problem,
            budget.orSpent(Cancellation.Never),
            OriginalClauseSubsumption.Limits(candidates = 32),
        )

        assertTrue(delta.isEmpty)
        assertTrue(budget.spent() in 1L..1000L)
    }

    @Test
    fun `cancellation should retain only completed equivalent transformations`() {
        val problem = problem(clause(0, 2), clause(1, 2, 4), clause(2, 4), clause(0, 2, 4))
        val full = PresolveBudget(Long.MAX_VALUE)
        OriginalClauseSubsumption.simplify(problem, full.orSpent(Cancellation.Never))
        var partial = false
        for (allowance in 0L..full.spent()) {
            val budget = PresolveBudget(allowance)

            val delta = OriginalClauseSubsumption.simplify(problem, budget.orSpent(Cancellation.Never))

            assertTrue(budget.spent() <= allowance)
            checkModels(problem, delta)
            if (!delta.isEmpty && budget.spent() == allowance) partial = true
        }
        assertTrue(partial)
    }

    @Test
    fun `automatic presets should keep the unpromoted pass disabled`() {
        for (emphasis in PresolveEmphasis.entries) {
            assertFalse(PresolveConfig(emphasis).resolved(PresolvePass.SUBSUME_CLAUSES, PresolveContext.EMPTY))
        }
        assertEquals(TransformationGuarantees.IDENTITY, PresolvePass.SUBSUME_CLAUSES.guarantees)
    }
}
