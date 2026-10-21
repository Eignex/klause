package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.PresolveShared.withSourcePassDelta
import com.eignex.klause.propagation.bake
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ClauseStrengtheningTest {
    @Test
    fun `either pivot polarity should shorten a clause without changing any assignment`() {
        for (positive in listOf(false, true)) {
            val pivot = Lit.make(0, positive)
            val problem = Problem(3, 0, emptyArray(), listOf(
                Clause(intArrayOf(pivot, Lit.make(1, true))),
                Clause(intArrayOf(Lit.negate(pivot), Lit.make(1, true), Lit.make(2, false))),
            ))

            val delta = ClauseStrengthening.strengthenSource(problem, Cancellation.Never)
            val reduced = assertNotNull(problem.withSourcePassDelta(delta))

            assertEquals(listOf(1), delta.droppedIndices.toList())
            assertEquals(listOf(2, 5), (delta.addedFactors.single() as Clause).literals.toList())
            for (mask in 0 until 8) {
                val assignment = BooleanArray(3) { mask and (1 shl it) != 0 }
                val before = problem.factors.filterIsInstance<Clause>().all { clause ->
                    clause.literals.any { Lit.evaluate(it, assignment[Lit.variable(it)]) }
                }
                val after = reduced.factors.filterIsInstance<Clause>().all { clause ->
                    clause.literals.any { Lit.evaluate(it, assignment[Lit.variable(it)]) }
                }
                assertEquals(before, after, "polarity=$positive mask=$mask")
            }
            assertTrue(delta.rebuild.isEmpty)
        }
    }

    @Test
    fun `a unit donor should remove its opposite literal`() {
        val problem = Problem(2, 0, emptyArray(), listOf(
            Clause(intArrayOf(0)),
            Clause(intArrayOf(1, 2)),
        ))
        val budget = PresolveBudget(Long.MAX_VALUE)

        val delta = ClauseStrengthening.strengthenSource(problem, budget.orSpent(Cancellation.Never))

        assertEquals(listOf(2), (delta.addedFactors.single() as Clause).literals.toList())
        assertEquals(1L, budget.clauseLiteralsRemoved)
    }

    @Test
    fun `opposing units should prove infeasibility`() {
        val problem = Problem(1, 0, emptyArray(), listOf(Clause(intArrayOf(0)), Clause(intArrayOf(1))))

        val delta = ClauseStrengthening.strengthenSource(problem, Cancellation.Never)

        assertTrue(delta.infeasible)
    }

    @Test
    fun `missing donor residual literals should leave a target unchanged`() {
        val problem = Problem(4, 0, emptyArray(), listOf(
            Clause(intArrayOf(0, 2, 4)),
            Clause(intArrayOf(1, 2, 6)),
        ))

        val delta = ClauseStrengthening.strengthenSource(problem, Cancellation.Never)

        assertTrue(delta.isEmpty)
        assertFalse(delta.infeasible)
    }

    @Test
    fun `a tautological donor should not strengthen another clause`() {
        val problem = Problem(2, 0, emptyArray(), listOf(
            Clause(intArrayOf(0, 1)),
            Clause(intArrayOf(1, 2)),
        ))

        val delta = ClauseStrengthening.strengthenSource(problem, Cancellation.Never)

        assertTrue(delta.isEmpty)
    }

    @Test
    fun `clauses containing private atoms should remain outside source strengthening`() {
        val problem = Problem(2, 0, emptyArray(), listOf(
            Clause(intArrayOf(0, 4)),
            Clause(intArrayOf(1, 4, 2)),
        ))

        val delta = ClauseStrengthening.strengthenSource(problem, Cancellation.Never)

        assertTrue(delta.isEmpty)
    }

    @Test
    fun `later reductions should use current clauses despite stale occurrence entries`() {
        val problem = Problem(3, 0, emptyArray(), listOf(
            Clause(intArrayOf(0)),
            Clause(intArrayOf(1, 2)),
            Clause(intArrayOf(3, 4)),
        ))
        val budget = PresolveBudget(Long.MAX_VALUE)

        val delta = ClauseStrengthening.strengthenSource(problem, budget.orSpent(Cancellation.Never))

        assertEquals(listOf(listOf(2), listOf(4)), delta.addedFactors.map { (it as Clause).literals.toList() })
        assertEquals(2L, budget.clauseLiteralsRemoved)
    }

    @Test
    fun `successive donors should publish only the final replacement of a target`() {
        val problem = Problem(3, 0, emptyArray(), listOf(
            Clause(intArrayOf(0)),
            Clause(intArrayOf(2)),
            Clause(intArrayOf(1, 3, 4)),
        ))
        val budget = PresolveBudget(Long.MAX_VALUE)

        val delta = ClauseStrengthening.strengthenSource(problem, budget.orSpent(Cancellation.Never))

        assertEquals(listOf(2), delta.droppedIndices.toList())
        assertEquals(listOf(4), (delta.addedFactors.single() as Clause).literals.toList())
        assertEquals(2L, budget.clauseLiteralsRemoved)
    }

    @Test
    fun `all pairs of three variable clauses should preserve their complete truth tables`() {
        val clauses = (1 until 27).map { code ->
            var digits = code
            val literals = ArrayList<Int>()
            for (variable in 0 until 3) {
                when (digits % 3) {
                    1 -> literals.add(Lit.make(variable, true))
                    2 -> literals.add(Lit.make(variable, false))
                }
                digits /= 3
            }
            Clause(literals.toIntArray())
        }
        for (first in clauses) {
            for (second in clauses) {
                val problem = Problem(3, 0, emptyArray(), listOf(first, second))

                val delta = ClauseStrengthening.strengthenSource(problem, Cancellation.Never)
                val reduced = if (delta.infeasible) null else assertNotNull(problem.withSourcePassDelta(delta))

                for (mask in 0 until 8) {
                    val assignment = BooleanArray(3) { mask and (1 shl it) != 0 }
                    val before = listOf(first, second).all { clause ->
                        clause.literals.any { Lit.evaluate(it, assignment[Lit.variable(it)]) }
                    }
                    val after = reduced?.factors?.filterIsInstance<Clause>()?.all { clause ->
                        clause.literals.any { Lit.evaluate(it, assignment[Lit.variable(it)]) }
                    } ?: false
                    assertEquals(before, after)
                }
            }
        }
    }

    @Test
    fun `other Boolean readers and open integer declarations should retain their coordinates`() {
        val open = Bits(1).also { it.set(0) }
        val reader = ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 7)
        val problem = Problem(
            numBoolVars = 2,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(0), null, open),
            factors = arrayOf<Factor>(reader, Clause(intArrayOf(0)), Clause(intArrayOf(1, 2))),
        )
        val context = PresolveContext(solutionSetSensitive = true, objectiveBoolCoeffs = mapOf(0 to 3L))

        val delta = PresolvePass.STRENGTHEN_CLAUSES.applySource(problem, context)
        val reduced = assertNotNull(problem.withSourcePassDelta(delta))

        assertSame(reader, reduced.factors.first())
        assertSame(problem.intBounds, reduced.intBounds)
        assertEquals(problem.numBoolVars, reduced.numBoolVars)
        assertTrue(delta.rebuild.isEmpty)
        assertTrue(PresolvePass.STRENGTHEN_CLAUSES.preservesSolutionSet)
        assertTrue(PresolveConfig.AUTO.resolved(PresolvePass.STRENGTHEN_CLAUSES, context))
    }

    @Test
    fun `the finite lane should apply the same clause equivalence`() {
        val problem = Problem(3, 0, emptyArray(), listOf(
            Clause(intArrayOf(0, 2)),
            Clause(intArrayOf(1, 2, 4)),
        )).bake()

        val delta = PresolvePass.STRENGTHEN_CLAUSES.applyFinite(problem, PresolveContext.EMPTY)

        assertEquals(listOf(2, 4), (delta.addedFactors.single() as Clause).literals.toList())
        assertEquals(null, delta.domains)
        assertEquals(null, delta.reconstruct)
    }

    @Test
    fun `an already stopped token should publish no changes or work`() {
        val problem = Problem(2, 0, emptyArray(), listOf(Clause(intArrayOf(0)), Clause(intArrayOf(1, 2))))
        val budget = PresolveBudget(Long.MAX_VALUE)

        val delta = ClauseStrengthening.strengthenSource(problem, budget.orSpent(Cancellation { true }))

        assertTrue(delta.isEmpty)
        assertEquals(0L, budget.spent())
        assertEquals(0L, budget.clauseLiteralsRemoved)
    }

    @Test
    fun `every work stop should retain only equivalent completed replacements and charged debt`() {
        val problem = Problem(3, 0, emptyArray(), listOf(
            Clause(intArrayOf(0)),
            Clause(intArrayOf(1, 2)),
            Clause(intArrayOf(3, 4)),
        ))
        val full = PresolveBudget(Long.MAX_VALUE)
        ClauseStrengthening.strengthenSource(problem, full.orSpent(Cancellation.Never))
        var sawPartial = false
        var sawUnchanged = false
        for (allowance in 0..full.spent()) {
            val budget = PresolveBudget(allowance)

            val delta = ClauseStrengthening.strengthenSource(problem, budget.orSpent(Cancellation.Never))
            val reduced = assertNotNull(problem.withSourcePassDelta(delta))

            if (budget.clauseLiteralsRemoved == 1L) sawPartial = true
            if (delta.isEmpty) sawUnchanged = true
            assertFalse(delta.infeasible)
            assertEquals(budget.clauseLiteralsRemoved.toInt(), delta.addedFactors.size)
            assertTrue(budget.spent() >= allowance)
            for (mask in 0 until 8) {
                val assignment = BooleanArray(3) { mask and (1 shl it) != 0 }
                val before = problem.factors.filterIsInstance<Clause>().all { clause ->
                    clause.literals.any { Lit.evaluate(it, assignment[Lit.variable(it)]) }
                }
                val after = reduced.factors.filterIsInstance<Clause>().all { clause ->
                    clause.literals.any { Lit.evaluate(it, assignment[Lit.variable(it)]) }
                }
                assertEquals(before, after, "allowance=$allowance mask=$mask")
            }
        }
        assertTrue(sawPartial)
        assertTrue(sawUnchanged)
    }

    @Test
    fun `cancellation during a replacement copy should discard the unfinished clause`() {
        val problem = Problem(3, 0, emptyArray(), listOf(
            Clause(intArrayOf(0)),
            Clause(intArrayOf(1, 2)),
            Clause(intArrayOf(3, 4)),
        ))
        var fullPolls = 0
        ClauseStrengthening.strengthenSource(problem, Cancellation { fullPolls++; false })
        var sawPartial = false
        for (stop in 1..fullPolls) {
            var polls = 0
            val budget = PresolveBudget(Long.MAX_VALUE)
            val token = budget.orSpent(Cancellation { ++polls >= stop })

            val delta = ClauseStrengthening.strengthenSource(problem, token)
            val reduced = assertNotNull(problem.withSourcePassDelta(delta))

            assertFalse(delta.infeasible)
            assertEquals(budget.clauseLiteralsRemoved.toInt(), delta.addedFactors.size)
            if (delta.addedFactors.size == 1) sawPartial = true
            for (mask in 0 until 8) {
                val assignment = BooleanArray(3) { mask and (1 shl it) != 0 }
                val before = problem.factors.filterIsInstance<Clause>().all { clause ->
                    clause.literals.any { Lit.evaluate(it, assignment[Lit.variable(it)]) }
                }
                val after = reduced.factors.filterIsInstance<Clause>().all { clause ->
                    clause.literals.any { Lit.evaluate(it, assignment[Lit.variable(it)]) }
                }
                assertEquals(before, after, "stop=$stop mask=$mask")
            }
        }
        assertTrue(sawPartial)
    }
}
