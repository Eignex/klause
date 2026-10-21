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
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertContentEquals
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
    fun `four width three clauses should not expand into sixteen resolvent literals`() {
        for (duplicates in listOf(false, true)) {
            val clauses = listOf(
                clause(pos(0), pos(1), pos(2)),
                clause(pos(0), pos(3), pos(4)),
                clause(neg(0), pos(5), pos(6)),
                clause(neg(0), pos(7), pos(8)),
            ).map { if (duplicates) Clause(it.literals + it.literals) else it }
            val problem = Problem(9, 0, emptyArray(), clauses)
            val protected = (1..8).toSet()

            val finite = BoundedVariableElimination.eliminate(problem, protected)
            val source = BoundedVariableElimination.eliminateSource(problem, protected)

            assertTrue(finite.isEmpty)
            assertTrue(source.isEmpty)
        }
    }

    @Test
    fun `resolvent width should be capped even when literal volume shrinks`() {
        for (width in listOf(64, 65)) {
            val clauses = listOf(
                Clause(intArrayOf(pos(0)) + (1..32).map(::pos)),
                Clause(intArrayOf(neg(0)) + (33..width).map(::pos)),
            )
            val problem = Problem(width + 1, 0, emptyArray(), clauses)

            val delta = BoundedVariableElimination.eliminateSource(problem, (1..width).toSet())

            assertEquals(width > 64, delta.isEmpty)
            if (width == 64) {
                assertEquals(64, (delta.addedFactors.single() as Clause).literals.size)
                assertContentEquals(intArrayOf(0, 1), delta.droppedIndices)
            }
        }
    }

    @Test
    fun `duplicate literals and resolvents should count once in an accepted pivot`() {
        val clauses = listOf(
            clause(pos(0), pos(0), pos(1), pos(1)),
            clause(pos(1), pos(0)),
            clause(pos(0), pos(1)),
            clause(neg(0), neg(0), pos(2), pos(2)),
            clause(pos(2), neg(0)),
            clause(neg(0), pos(2)),
        )
        val problem = Problem(3, 0, emptyArray(), clauses)

        val delta = BoundedVariableElimination.eliminateSource(problem, setOf(1, 2))

        assertContentEquals(intArrayOf(0, 1, 2, 3, 4, 5), delta.droppedIndices)
        assertEquals(setOf(pos(1), pos(2)), (delta.addedFactors.single() as Clause).literals.toSet())
        val reduced = assertNotNull(problem.withSourcePassDelta(delta))
        for (mask in 0 until 8) {
            val solved = BooleanArray(3) { (mask shr it) and 1 == 1 }
            if (!sat(reduced.factors, solved)) continue
            delta.rebuild.rebuildInto(solved)
            assertTrue(sat(problem.factors, solved))
        }
    }

    @Test
    fun `resolution work should be capped despite duplicate resolvents and a bounded pair count`() {
        val positive = Clause(intArrayOf(pos(0)) + (1..32).map(::pos))
        val negative = Clause(intArrayOf(neg(0)) + (33..64).map(::pos))
        val problem = Problem(65, 0, emptyArray(), List(32) { positive } + List(32) { negative })
        var charged = 0L
        val meter = object : Cancellation {
            override fun isCancelled(): Boolean = false
            override fun workMeter(): WorkMeter = WorkMeter { charged += it }
        }

        val delta = BoundedVariableElimination.eliminateSource(problem, (1..64).toSet(), meter)

        assertTrue(delta.isEmpty)
        assertEquals(655_360L, charged)
    }

    @Test
    fun `unused candidates should charge work and stop on the phase allowance`() {
        val problem = Problem(64, 0, emptyArray(), emptyArray())
        var charged = 0L
        val stop = object : Cancellation {
            override fun isCancelled(): Boolean = charged >= 30L
            override fun workMeter(): WorkMeter = WorkMeter { charged += it }
        }

        val delta = BoundedVariableElimination.eliminateSource(problem, cancellation = stop)

        assertTrue(delta.isEmpty)
        assertEquals(30L, charged)
    }

    @Test
    fun `a rejected later pivot should retain a completed elimination`() {
        val problem = Problem(
            12,
            0,
            emptyArray(),
            listOf(
                clause(pos(0), pos(2)),
                clause(neg(0), pos(3)),
                clause(pos(1), pos(4), pos(5)),
                clause(pos(1), pos(6), pos(7)),
                clause(neg(1), pos(8), pos(9)),
                clause(neg(1), pos(10), pos(11)),
            ),
        )

        val delta = BoundedVariableElimination.eliminateSource(problem, (2..11).toSet())

        assertContentEquals(intArrayOf(0, 1), delta.droppedIndices)
        assertEquals(setOf(pos(2), pos(3)), (delta.addedFactors.single() as Clause).literals.toSet())
        val reduced = assertNotNull(problem.withSourcePassDelta(delta))
        val reconstructed = BooleanArray(12) { it == 3 || it == 4 || it == 6 }
        assertTrue(sat(reduced.factors, reconstructed))
        delta.rebuild.rebuildInto(reconstructed)
        assertTrue(sat(problem.factors, reconstructed))
    }

    @Test
    fun `interruption during resolution should publish only completed earlier pivots`() {
        val positive = clause(pos(1), pos(2), pos(3))
        val negative = clause(neg(1), pos(4), pos(5))
        val problem = Problem(
            6,
            0,
            emptyArray(),
            listOf(clause(pos(0))) + List(16) { positive } + List(16) { negative },
        )
        var charged = 0L
        val stop = object : Cancellation {
            override fun isCancelled(): Boolean = charged >= 2_000L
            override fun workMeter(): WorkMeter = WorkMeter { charged += it }
        }

        val delta = BoundedVariableElimination.eliminateSource(problem, (2..5).toSet(), stop)

        assertContentEquals(intArrayOf(0), delta.droppedIndices)
        assertTrue(delta.addedFactors.isEmpty())
        assertTrue(charged in 2_000L..4_560L)
        val reduced = assertNotNull(problem.withSourcePassDelta(delta))
        for (mask in 0 until 64) {
            val solved = BooleanArray(6) { (mask shr it) and 1 == 1 }
            if (!sat(reduced.factors, solved)) continue
            delta.rebuild.rebuildInto(solved)
            assertTrue(sat(problem.factors, solved))
        }
    }

    @Test
    fun `cancellation after analysis should decline the complete uncommitted pivot`() {
        val problem = Problem(2, 0, emptyArray(), listOf(clause(pos(0), pos(1))))
        var charged = 0L
        val stop = object : Cancellation {
            override fun isCancelled(): Boolean = charged > 0L
            override fun workMeter(): WorkMeter = WorkMeter { charged += it }
        }

        val delta = BoundedVariableElimination.eliminateSource(problem, setOf(1), stop)

        assertTrue(delta.isEmpty)
        assertTrue(charged > 0L)
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
