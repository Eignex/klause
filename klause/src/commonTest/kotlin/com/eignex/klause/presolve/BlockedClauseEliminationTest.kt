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
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Blocked-clause elimination. Like BVE, BCE is satisfiability- but not solution-set-preserving, so each
 * test enumerates all Boolean assignments and checks equisatisfiability, that no original solution's
 * projection is rejected, and that reconstruction lifts every reduced solution back to a valid original.
 */
class BlockedClauseEliminationTest {

    private fun pos(v: Int) = Lit.make(v, true)
    private fun neg(v: Int) = Lit.make(v, false)
    private fun clause(vararg lits: Int) = Clause(lits)

    private fun sat(factors: Array<Factor>, a: BooleanArray): Boolean =
        factors.filterIsInstance<Clause>().all { c -> c.literals.any { Lit.evaluate(it, a[Lit.variable(it)]) } }

    /** Run BCE and assert soundness over the whole `2^numBool` assignment space; returns the reduced problem. */
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
    fun `removes a blocked clause`() {
        // In (a ∨ b) ∧ (¬a ∨ ¬b) each clause is blocked (the resolvent on either shared variable is a
        // tautology), so at least one is removed and the solution set is recovered by reconstruction.
        val problem = Problem(2, 0, emptyArray(), listOf(clause(pos(0), pos(1)), clause(neg(0), neg(1))))
        val reduced = checkBce(2, listOf(clause(pos(0), pos(1)), clause(neg(0), neg(1))))
        assertTrue(clauseCount(reduced) < clauseCount(problem), "a blocked clause is removed")
    }

    @Test
    fun `removes a pure-literal clause as vacuously blocked`() {
        // b never occurs negatively, so (a ∨ b) is blocked on b (no opposite clause to clash with).
        val reduced = checkBce(2, listOf(clause(pos(0), pos(1))))
        assertEquals(0, clauseCount(reduced), "the clause is removed")
    }

    @Test
    fun `keeps clauses whose only blocking variables are ineligible`() {
        // With both variables protected, neither may serve as a blocking literal, so nothing is removed.
        val reduced = checkBce(
            2,
            listOf(clause(pos(0), pos(1)), clause(neg(0), neg(1))),
            objectiveBoolVars = setOf(0, 1),
        )
        assertEquals(2, clauseCount(reduced), "no clause is removed")
    }

    @Test
    fun `is sound on a mixed formula`() {
        checkBce(
            4,
            listOf(
                clause(pos(0), pos(1)),
                clause(neg(0), pos(2)),
                clause(neg(1), neg(2), pos(3)),
                clause(pos(0), neg(3)),
            ),
        )
    }

    // ---- the source lane ----

    @Test
    fun `a blocked clause is repaired on a model with an open column`() {
        // The blocked check reads only the clause database, so an open integer column beside it is
        // untouched — the reduction the finite lane cannot reach on this model.
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

        val reduced = assertNotNull(problem.withSourcePassDelta(delta), "the elimination must not refute")
        assertTrue(clauseCount(reduced) < clauseCount(problem), "a blocked clause is removed")
        for (mask in 0 until (1 shl problem.numBoolVars)) {
            val solved = BooleanArray(problem.numBoolVars) { ((mask shr it) and 1) == 1 }
            if (!sat(reduced.factors, solved)) continue
            val lifted = solved.copyOf().also { delta.rebuild.rebuildInto(it) }
            assertTrue(sat(problem.factors, lifted), "rebuild produced ${lifted.toList()}, not a solution")
        }
    }

    @Test
    fun `a boolean an integer row reads never blocks a clause`() {
        // (b0 ∨ b1) is blocked on either literal over the clauses alone, but a reified row reads both, so
        // a repair flipping one would change what the row sees.
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

        assertTrue(delta.isEmpty, "no clause over row-read booleans is removed")
    }
}
