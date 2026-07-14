package com.eignex.klause.presolve

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.presolve.PresolveShared.withPassDelta
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Bounded variable elimination. BVE is not solution-set preserving (an eliminated variable is left
 * unconstrained), so each test enumerates all Boolean assignments and checks the three properties that
 * make it sound: the reduced problem is equisatisfiable, it never rejects the projection of an original
 * solution, and reconstruction lifts every reduced solution back to a valid original one.
 */
class BoundedVariableEliminationTest {

    private fun pos(v: Int) = Lit.make(v, true)
    private fun neg(v: Int) = Lit.make(v, false)
    private fun clause(vararg lits: Int) = Clause(lits)

    private fun sat(factors: Array<Factor>, a: BooleanArray): Boolean =
        factors.filterIsInstance<Clause>().all { c -> c.literals.any { Lit.evaluate(it, a[Lit.variable(it)]) } }

    /** Run BVE and assert soundness over the whole `2^numBool` assignment space; returns the reduced problem. */
    private fun checkBve(numBool: Int, clauses: List<Clause>, objectiveBoolVars: Set<Int> = emptySet()): Problem {
        val problem = Problem(numBool, 0, emptyArray(), clauses)
        val delta = Presolve.eliminateBoolVars(problem, objectiveBoolVars)
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
        assertEquals(origSat, reducedSat, "BVE changed satisfiability")
        return reduced
    }

    private fun mentions(problem: Problem, v: Int): Boolean =
        problem.factors.filterIsInstance<Clause>().any { c -> c.literals.any { Lit.variable(it) == v } }

    @Test
    fun `eliminates a variable within the bound`() {
        // (a ∨ b) ∧ (¬a ∨ c): resolving out a gives the single resolvent (b ∨ c) — 1 ≤ 2, bounded.
        val reduced = checkBve(3, listOf(clause(pos(0), pos(1)), clause(neg(0), pos(2))))
        assertTrue(!mentions(reduced, 0), "a is eliminated")
    }

    @Test
    fun `eliminates a monotone pure-literal variable with no resolvent`() {
        // a occurs only positively, so both clauses are satisfiable by a = true and simply drop.
        val reduced = checkBve(3, listOf(clause(pos(0), pos(1)), clause(pos(0), pos(2))))
        assertTrue(!mentions(reduced, 0), "the pure-literal variable is eliminated")
        assertEquals(0, reduced.factors.filterIsInstance<Clause>().size, "its clauses drop with no resolvent")
    }

    @Test
    fun `discards tautological resolvents`() {
        // (a ∨ b) ∧ (¬a ∨ ¬b): the only resolvent (b ∨ ¬b) is a tautology, so a eliminates with 0 clauses.
        val reduced = checkBve(2, listOf(clause(pos(0), pos(1)), clause(neg(0), neg(1))))
        assertTrue(!mentions(reduced, 0), "a is eliminated")
    }

    @Test
    fun `does not eliminate when resolution would grow the clause count`() {
        // a has 2 positive and 3 negative occurrences: 6 resolvents > 5 originals — unbounded, kept. The
        // neighbours are protected so a is the sole candidate (else they pure-eliminate a's clauses first).
        val reduced = checkBve(
            6,
            listOf(
                clause(pos(0), pos(1)),
                clause(pos(0), pos(2)),
                clause(neg(0), pos(3)),
                clause(neg(0), pos(4)),
                clause(neg(0), pos(5)),
            ),
            objectiveBoolVars = setOf(1, 2, 3, 4, 5),
        )
        assertTrue(mentions(reduced, 0), "the unbounded variable is left in place")
    }

    @Test
    fun `never eliminates an objective variable`() {
        // a would resolve out to (b ∨ c) if unprotected; the neighbours are protected too so nothing else
        // removes a's clauses, isolating the objective guard on a.
        val reduced = checkBve(
            3,
            listOf(clause(pos(0), pos(1)), clause(neg(0), pos(2))),
            objectiveBoolVars = setOf(0, 1, 2),
        )
        assertTrue(mentions(reduced, 0), "the objective variable is protected")
    }

    @Test
    fun `chained eliminations reconstruct in the right order`() {
        // Two eliminable variables whose clauses interlock, exercising reverse-order reconstruction.
        checkBve(
            5,
            listOf(
                clause(pos(0), pos(1)),
                clause(neg(0), pos(2)),
                clause(pos(1), pos(3)),
                clause(neg(1), pos(4)),
            ),
        )
    }
}
