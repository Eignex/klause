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

class BoundedVariableEliminationTest {
    private fun pos(v: Int) = Lit.make(v, true)
    private fun neg(v: Int) = Lit.make(v, false)
    private fun clause(vararg lits: Int) = Clause(lits)

    private fun sat(factors: Array<Factor>, a: BooleanArray): Boolean =
        factors.filterIsInstance<Clause>().all { c -> c.literals.any { Lit.evaluate(it, a[Lit.variable(it)]) } }

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
    fun `elimination should discard tautological resolvents`() {
        val reduced = checkBve(2, listOf(clause(pos(0), pos(1)), clause(neg(0), neg(1))))
        assertTrue(!mentions(reduced, 0))
    }

    @Test
    fun `elimination should not apply when resolution would grow the clause count`() {
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
        assertTrue(mentions(reduced, 0))
    }

    @Test
    fun `chained eliminations should reconstruct in reverse order`() {
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

    @Test
    fun `an eliminated variable should be recovered on a model with an open column`() {
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

        val reduced = assertNotNull(problem.withSourcePassDelta(delta))
        assertTrue(!delta.rebuild.isEmpty)
        for (mask in 0 until (1 shl problem.numBoolVars)) {
            val solved = BooleanArray(problem.numBoolVars) { ((mask shr it) and 1) == 1 }
            if (!sat(reduced.factors, solved)) continue
            val lifted = solved.copyOf().also { delta.rebuild.rebuildInto(it) }
            assertTrue(sat(problem.factors, lifted), "rebuild produced ${lifted.toList()}, not a solution")
        }
    }

    @Test
    fun `a boolean read by an integer row should not be eliminated`() {
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
        assertTrue(reduced.factors.any { f -> f.boolVars.any { it == 0 } })
    }
}
