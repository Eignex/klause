package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.PresolveShared.withPassDelta
import com.eignex.klause.presolve.PresolveShared.withSourcePassDelta
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BlockedClauseEliminationTest {
    private fun pos(v: Int) = Lit.make(v, true)
    private fun neg(v: Int) = Lit.make(v, false)
    private fun clause(vararg lits: Int) = Clause(lits)

    private fun sat(factors: Array<Factor>, a: BooleanArray): Boolean =
        factors.filterIsInstance<Clause>().all { c -> c.literals.any { Lit.evaluate(it, a[Lit.variable(it)]) } }

    private fun checkBce(numBool: Int, clauses: List<Clause>, objectiveBoolVars: Set<Int> = emptySet()): Problem {
        val problem = Problem(numBool, 0, emptyArray(), clauses).bake()
        val delta = Presolve.eliminateBlockedClauses(problem, objectiveBoolVars)
        val reduced = problem.withPassDelta(delta, BakeConfig.NONE)
        var origSat = false
        var reducedSat = false
        for (mask in 0 until (1 shl numBool)) {
            val a = BooleanArray(numBool) { (mask shr it) and 1 == 1 }
            val orig = sat(problem.factors, a)
            val red = sat(reduced.factors, a)
            if (orig) {
                origSat = true
                assertTrue(red, "reduced problem rejected the projection of an original solution (mask $mask)")
            }
            if (red) {
                reducedSat = true
                val recon = delta.reconstruct?.invoke(Sample(a.copyOf(), LongArray(0)))?.bools ?: a
                assertTrue(
                    sat(problem.factors, recon),
                    "reconstruct produced an invalid original solution (mask $mask)",
                )
            }
        }
        assertEquals(origSat, reducedSat, "BCE changed satisfiability")
        return reduced
    }

    private fun clauseCount(problem: Problem) = problem.factors.filterIsInstance<Clause>().size

    @Test
    fun `interrupted candidate and opposite scans should retain only completed repairs`() {
        val scans = listOf(
            listOf(Clause((0..300).map(::pos).toIntArray())) to (0..300).toSet(),
            listOf(Clause((0..300).map(::pos).toIntArray()), clause(neg(0), neg(300))) to (1..300).toSet(),
            listOf(clause(pos(0), pos(1)), Clause(intArrayOf(neg(0)) + (2..300).map(::pos) + neg(1))) to
                (1..300).toSet(),
            (listOf(clause(pos(0), pos(1))) + List(300) { clause(neg(0), neg(1)) }) to (1..300).toSet(),
        )
        for ((clauses, protected) in scans) {
            val problem = Problem(302, 0, emptyArray(), listOf(clause(pos(301))) + clauses)
            val setup = PresolveBudget(Long.MAX_VALUE)
            assertNotNull(SatClauseDb.build(problem, protected, setup.orSpent(Cancellation.Never)))
            for (finite in listOf(false, true)) {
                val budget = PresolveBudget(setup.spent() + 1_020L)
                val token = budget.orSpent(Cancellation.Never)
                val delta = if (finite) {
                    BlockedClauseElimination.eliminate(problem, protected, token).let {
                        SourceDelta(it.droppedIndices, it.addedFactors, rebuild = it.rebuild)
                    }
                } else {
                    BlockedClauseElimination.eliminateSource(problem, protected, token)
                }

                assertContentEquals(intArrayOf(0), delta.droppedIndices)
                assertTrue(delta.addedFactors.isEmpty())
                assertEquals(budget.allowance, budget.spent())
                val solved = BooleanArray(302) { it == 1 || it == 300 }
                val reduced = assertNotNull(problem.withSourcePassDelta(delta))
                assertTrue(sat(reduced.factors, solved))
                delta.rebuild.rebuildInto(solved)
                assertTrue(sat(problem.factors, solved))
            }
        }
    }

    @Test
    fun `an attempt should stop before a late pure literal when its visit cap is exhausted`() {
        for (width in listOf(16, 257)) {
            val clauses = listOf(Clause((0..width).map(::pos).toIntArray())) +
                List(width) { clause(neg(it)) }
            val problem = Problem(width + 1, 0, emptyArray(), clauses)
            val budget = PresolveBudget(Long.MAX_VALUE)
            val setup = PresolveBudget(Long.MAX_VALUE)
            assertNotNull(SatClauseDb.build(problem, emptySet(), setup.orSpent(Cancellation.Never)))

            val delta = BlockedClauseElimination.eliminateSource(
                problem, cancellation = budget.orSpent(Cancellation.Never),
            )

            assertEquals(width == 257, delta.isEmpty)
            assertTrue(budget.spent() <= setup.spent() + 655_360L + width * (width + 5L) * 10L)
            val reduced = assertNotNull(problem.withSourcePassDelta(delta))
            val solved = BooleanArray(width + 1) { it == width }
            assertTrue(sat(reduced.factors, solved))
            delta.rebuild.rebuildInto(solved)
            assertTrue(sat(problem.factors, solved))
        }
    }

    @Test
    fun `completed blocked checks should remove clauses and reconstruct a satisfying assignment`() {
        val reduced = checkBce(3, listOf(clause(pos(0), pos(1)), clause(neg(0), neg(1), pos(2))))

        assertEquals(0, clauseCount(reduced))
    }

    @Test
    fun `clauses whose only blocking variables are ineligible should be kept`() {
        val reduced = checkBce(
            2,
            listOf(clause(pos(0), pos(1)), clause(neg(0), neg(1))),
            objectiveBoolVars = setOf(0, 1),
        )
        assertEquals(2, clauseCount(reduced))
    }

    @Test
    fun `a blocked clause should be repaired on a model with an open column`() {
        val open = Bits(1).also { it.set(0) }
        val problem = Problem(
            numBoolVars = 3,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(0), null, open),
            factors = arrayOf<Factor>(
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 7),
                clause(pos(0), pos(1)),
                clause(neg(0), neg(1), pos(2)),
            ),
        )

        val delta = Presolve.eliminateSourceBlockedClauses(problem, emptySet(), Cancellation.Never)

        val reduced = assertNotNull(problem.withSourcePassDelta(delta))
        assertTrue(clauseCount(reduced) < clauseCount(problem))
        for (mask in 0 until (1 shl problem.numBoolVars)) {
            val solved = BooleanArray(problem.numBoolVars) { ((mask shr it) and 1) == 1 }
            if (!sat(reduced.factors, solved)) continue
            val lifted = solved.copyOf().also { delta.rebuild.rebuildInto(it) }
            assertTrue(sat(problem.factors, lifted), "rebuild produced ${lifted.toList()}, not a solution")
        }
    }

    @Test
    fun `a boolean read by an integer row should never block a clause`() {
        val open = Bits(1).also { it.set(0) }
        val problem = Problem(
            numBoolVars = 2,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(0), null, open),
            factors = arrayOf<Factor>(
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 3),
                ReifiedLinear(1, intArrayOf(1), intArrayOf(0), LinearOp.GE, 5),
                clause(pos(0), pos(1)),
            ),
        )

        val delta = Presolve.eliminateSourceBlockedClauses(problem, emptySet(), Cancellation.Never)

        assertTrue(delta.isEmpty)
    }
}
