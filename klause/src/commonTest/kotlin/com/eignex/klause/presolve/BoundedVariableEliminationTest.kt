package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Cardinality
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
        val problem = Problem(numBool, 0, emptyArray(), clauses).bake()
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
    fun `never eliminates a variable a non-clause factor mentions`() {
        // a resolves out cleanly on the clause side, but the cardinality also constrains it and no
        // reconstruction can restore a value that satisfies it — so the clauses must stay.
        val problem = Problem(
            3,
            0,
            emptyArray(),
            listOf(
                clause(pos(0), pos(1)),
                clause(neg(0), pos(2)),
                Cardinality(intArrayOf(pos(0), pos(1)), min = 1, max = 1),
            ),
        )
        val baked = problem.bake()
        val reduced = baked.withPassDelta(Presolve.eliminateBoolVars(baked, emptySet()), BakeConfig.NONE)
        assertTrue(mentions(reduced, 0), "a variable outside the clause database must survive BVE")
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

    // ---- the source lane ----

    @Test
    fun `an eliminated variable is recovered on a model with an open column`() {
        // Resolution reads only the clause database, so an open integer column beside it is untouched —
        // the reduction the finite lane cannot reach on this model.
        val open = Bits(1).also { it.set(0) }
        val problem = Problem(
            numBoolVars = 3,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(0), null, open),
            factors = arrayOf<Factor>(
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 7),
                clause(pos(0), pos(1)),
                clause(neg(0), pos(2)),
            ),
        )

        val delta = Presolve.eliminateSourceBoolVars(problem, emptySet(), Cancellation.Never)

        val reduced = assertNotNull(problem.withSourcePassDelta(delta), "the elimination must not refute")
        assertTrue(!delta.rebuild.isEmpty, "the eliminated column is carried as a rebuild")
        // Every assignment of the reduced model lifts to one the original satisfies.
        for (mask in 0 until (1 shl problem.numBoolVars)) {
            val solved = BooleanArray(problem.numBoolVars) { ((mask shr it) and 1) == 1 }
            if (!sat(reduced.factors, solved)) continue
            val lifted = solved.copyOf().also { delta.rebuild.rebuildInto(it) }
            assertTrue(sat(problem.factors, lifted), "rebuild produced ${lifted.toList()}, not a solution")
        }
    }

    @Test
    fun `an integer column keeps a boolean the rows read out of reach`() {
        // b0 is read by a reified row, so it is ineligible however its clauses look — the rule that makes
        // resolution safe before a finite projection exists.
        val open = Bits(1).also { it.set(0) }
        val problem = Problem(
            numBoolVars = 2,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(0), null, open),
            factors = arrayOf<Factor>(
                ReifiedLinear(Lit.make(0, true), intArrayOf(1), intArrayOf(0), LinearOp.LE, 3),
                clause(pos(0), pos(1)),
            ),
        )

        val delta = Presolve.eliminateSourceBoolVars(problem, emptySet(), Cancellation.Never)

        val reduced = assertNotNull(problem.withSourcePassDelta(delta))
        assertTrue(
            reduced.factors.any { f -> f.boolVars.any { it == 0 } },
            "a boolean an integer row reads stays referenced rather than being resolved away",
        )
    }
}
