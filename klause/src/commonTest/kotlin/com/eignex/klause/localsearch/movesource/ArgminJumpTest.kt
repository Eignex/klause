package com.eignex.klause.localsearch.movesource

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.table.Element
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.propagation.Assumptions
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ArgminJumpTest {

    /** A single `x0 = 3` constraint over 0..5: from the all-zero start the unique argmin jump is
     *  `x0 ← 3` (which zeroes the constraint). */
    private fun eqProblem(): Problem = Problem(
        numBoolVars = 0,
        numIntVars = 1,
        intDomains = arrayOf(IntDomain(0, 5)),
        factors = arrayOf<Factor>(Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 3)),
    )

    /** Two coupled sum constraints so a hot-spot jump has a non-trivial weighted argmin. */
    private fun coupledProblem(): Problem = Problem(
        numBoolVars = 0,
        numIntVars = 2,
        intDomains = arrayOf(IntDomain(0, 5), IntDomain(0, 5)),
        factors = arrayOf<Factor>(
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 6),
            Linear(intArrayOf(2, 1), intArrayOf(0, 1), LinearOp.EQ, 8),
        ),
    )

    private fun jumps(state: LocalSearchState, candidateVars: Int): List<Move> {
        val sink = MoveSink(state.assumptions)
        sink.setInvariants(state.invariants)
        sink.setOwners(state.seeding.ownerInt)
        sink.clear()
        ArgminJump(candidateVars).generate(state, sink)
        return sink.list
    }

    @Test
    fun `jumps to the violation-minimizing value`() {
        val state = freshState(eqProblem(), 7L)
        assertTrue(state.cost > 0L, "all-zero start violates x0 = 3")
        val moves = jumps(state, candidateVars = 1)
        assertTrue(
            moves.contains(Move.IntSet(0, 3)),
            "the argmin jump must set x0 to 3 (got $moves)",
        )
    }

    @Test
    fun `emitted jumps never increase weighted violation`() {
        for (seed in longArrayOf(1L, 7L, 42L, 1234L)) {
            val state = freshState(coupledProblem(), seed)
            for (m in jumps(state, candidateVars = 4)) {
                assertTrue(
                    state.weightedNetDelta(m) <= 0.0,
                    "ArgminJump emitted a violation-increasing move $m (Δ=${state.weightedNetDelta(m)})",
                )
            }
        }
    }

    @Test
    fun `maintained integer indicators jump through their source`() {
        val problem = Problem(
            1, 2, arrayOf(IntDomain(0, 1), IntDomain(0, 1)),
            arrayOf<Factor>(
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 0),
                ReifiedLinear(0, intArrayOf(1), intArrayOf(1), LinearOp.EQ, 1),
                Linear(intArrayOf(1), intArrayOf(1), LinearOp.EQ, 0),
            ),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
        state.invariants = assertNotNull(DefinitionalSweep.infer(problem, intArrayOf(1))).network(2, 1)
        state.assignment.setInt(1, 1)
        state.assignment.setBool(0, true)
        state.recompute()
        val before = state.assignment.snapshot()

        val move = jumps(state, candidateVars = 1).single()

        assertEquals(before, state.assignment.snapshot())
        assertEquals(-1.0, state.weightedNetDelta(move))
        state.apply(move)
        assertEquals(1L, state.assignment.intValue(0))
        assertEquals(false, state.assignment.boolValue(0))
        assertEquals(0L, state.assignment.intValue(1))
        assertEquals(0L, state.cost)
    }

    @Test
    fun `maintained Boolean predicates choose the best inverse repair`() {
        val source = Problem(
            1, 2, arrayOf(IntDomain(0, 3), IntDomain(0, 1)),
            arrayOf<Factor>(
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 0),
                ReifiedLinear(0, intArrayOf(1), intArrayOf(1), LinearOp.EQ, 1),
            ),
        )
        val lowered = Problem(
            1, 2, arrayOf(IntDomain(0, 3), IntDomain(0, 0)),
            arrayOf<Factor>(
                source.factors[0],
                Clause(intArrayOf(Lit.make(0, false))),
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 0),
            ),
        )
        val state = LocalSearchState(LocalSearchModel.open(lowered), Random(3), Assumptions.None.withInt(1, 0))
        state.invariants = assertNotNull(DefinitionalSweep.infer(source, intArrayOf(1))).network(2, 1)
        state.assignment.setBool(0, true)
        state.recompute()
        state.weights.factorWeights[1] = 5.0
        val before = state.assignment.snapshot()
        val cost = state.cost

        val moves = jumps(state, candidateVars = 1)

        assertTrue(moves.isNotEmpty())
        assertEquals(before, state.assignment.snapshot())
        assertEquals(cost, state.cost)
        for (move in moves) {
            assertEquals(-4.0, state.weightedNetDelta(move))
        }
        state.apply(moves.first())
        assertEquals(1L, state.assignment.intValue(0))
        assertEquals(false, state.assignment.boolValue(0))
        assertEquals(0L, state.assignment.intValue(1))
        assertEquals(1L, state.cost)
    }

    @Test
    fun `integer jumps account for maintained output penalties`() {
        val problem = Problem(
            0, 2, arrayOf(IntDomain(0, 2), IntDomain(0, 4)),
            arrayOf<Factor>(
                Product(0, 0, 1),
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 2),
                Linear(intArrayOf(1), intArrayOf(1), LinearOp.EQ, 0),
            ),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
        state.invariants = assertNotNull(DefinitionalSweep.infer(problem)).network(2, 0)
        state.recompute()
        state.weights.factorWeights[1] = 4.0
        state.weights.factorWeights[2] = 20.0

        assertTrue(jumps(state, candidateVars = 1).isEmpty())
        assertEquals(0L, state.assignment.intValue(0))
        assertEquals(0L, state.assignment.intValue(1))
    }

    @Test
    fun `Boolean jumps account for maintained channel penalties`() {
        val problem = Problem(
            1, 1, arrayOf(IntDomain(0, 1)),
            arrayOf<Factor>(
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 1),
                Clause(intArrayOf(Lit.make(0, true))),
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 0),
            ),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
        state.invariants = assertNotNull(DefinitionalSweep.infer(problem, intArrayOf(0))).network(1, 1)
        state.recompute()
        state.weights.factorWeights[1] = 3.0
        state.weights.factorWeights[2] = 10.0

        assertTrue(jumps(state, candidateVars = 1).isEmpty())
        assertEquals(false, state.assignment.boolValue(0))
        assertEquals(0L, state.assignment.intValue(0))
    }

    @Test
    fun `integer jumps grade the emitted channel compound`() {
        for (upper in listOf(2, 32)) {
            val problem = Problem(
                1, 1, arrayOf(IntDomain(0, upper.toLong())),
                arrayOf<Factor>(
                    ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 0),
                    Clause(intArrayOf(Lit.make(0, true))),
                    Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, upper),
                ),
            )
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
            state.assignment.setBool(0, true)
            state.recompute()
            state.weights.factorWeights[1] = 100.0
            state.weights.factorWeights[2] = 2.0

            assertTrue(jumps(state, candidateVars = 1).isEmpty())
        }
    }

    @Test
    fun `inverse jumps preserve pinned and implicitly owned coordinates`() {
        for (protection in listOf("source pin", "indicator pin", "Boolean pin", "source owner", "indicator owner")) {
            val ownerVar = if (protection == "indicator owner") 1 else 0
            val factors = arrayOf<Factor>(
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 0),
                ReifiedLinear(0, intArrayOf(1), intArrayOf(1), LinearOp.EQ, 1),
                Linear(intArrayOf(1), intArrayOf(1), LinearOp.EQ, 0),
            ) + if (protection.endsWith("owner")) {
                arrayOf<Factor>(AllDifferent(intArrayOf(ownerVar, 2), 0, 2))
            } else {
                emptyArray()
            }
            val problem = Problem(1, 3, Array(3) { IntDomain(0, 1) }, factors)
            val assumptions = when (protection) {
                "source pin" -> Assumptions.None.withInt(0, 0)
                "indicator pin" -> Assumptions.None.withInt(1, 1)
                "Boolean pin" -> Assumptions.None.withBool(0, true)
                else -> Assumptions.None
            }
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(3), assumptions)
            state.invariants = assertNotNull(DefinitionalSweep.infer(problem, intArrayOf(1))).network(3, 1)
            if (protection.endsWith("owner")) state.seedImplicitFeasible()
            state.assignment.setInt(0, 0)
            state.assignment.setInt(1, 1)
            state.assignment.setInt(2, if (ownerVar == 0) 1L else 0L)
            state.assignment.setBool(0, true)
            state.recompute()
            val before = state.assignment.snapshot()

            assertTrue(jumps(state, candidateVars = 1).isEmpty(), protection)
            assertEquals(before, state.assignment.snapshot(), protection)
        }
    }

    @Test
    fun `integer jumps select the improving Element destination variant`() {
        val problem = Problem(
            0, 4, arrayOf(IntDomain(0, 1), IntDomain(1, 1), IntDomain(0, 1), IntDomain(0, 1)),
            arrayOf<Factor>(
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 1),
                Element(0, 1, longArrayOf(2, 3), true, 0),
            ),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
        state.assignment.setInt(1, 1)
        state.assignment.setInt(2, 1)
        state.recompute()
        val before = state.assignment.snapshot()

        val move = jumps(state, candidateVars = 1).single()

        assertEquals(before, state.assignment.snapshot())
        assertEquals(-1.0, state.weightedNetDelta(move))
        state.apply(move)
        assertEquals(1L, state.assignment.intValue(0))
        assertEquals(1L, state.assignment.intValue(3))
        assertEquals(0L, state.cost)
    }

    @Test
    fun `inverse integer jumps stay within sparse source domains`() {
        val problem = Problem(
            1, 2, arrayOf(IntDomain(0, 3).excludeValue(1).excludeValue(2), IntDomain(0, 1)),
            arrayOf<Factor>(
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 0),
                ReifiedLinear(0, intArrayOf(1), intArrayOf(1), LinearOp.EQ, 1),
                Linear(intArrayOf(1), intArrayOf(1), LinearOp.EQ, 0),
            ),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
        state.invariants = assertNotNull(DefinitionalSweep.infer(problem, intArrayOf(1))).network(2, 1)
        state.assignment.setInt(1, 1)
        state.assignment.setBool(0, true)
        state.recompute()

        val move = jumps(state, candidateVars = 1).single()
        state.apply(move)

        assertEquals(3L, state.assignment.intValue(0))
        assertEquals(0L, state.cost)
    }

    @Test
    fun `Boolean inverse jumps preserve source restrictions`() {
        for (protection in listOf("source pin", "Boolean pin", "source owner", "fixed source")) {
            val domain = IntDomain(0, if (protection == "fixed source") 0L else 2L)
            val source = Problem(
                1, 3, arrayOf(domain, IntDomain(0, 1), IntDomain(0, 2)),
                arrayOf<Factor>(
                    ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 0),
                    ReifiedLinear(0, intArrayOf(1), intArrayOf(1), LinearOp.EQ, 1),
                ),
            )
            val factors = arrayOf<Factor>(source.factors[0], Clause(intArrayOf(Lit.make(0, false)))) +
                if (protection == "source owner") {
                    arrayOf<Factor>(AllDifferent(intArrayOf(0, 2), 0, 3))
                } else {
                    emptyArray()
                }
            val lowered = Problem(1, 3, arrayOf(domain, IntDomain(0, 0), IntDomain(0, 2)), factors)
            val assumptions = when (protection) {
                "source pin" -> Assumptions.None.withInt(0, 0)
                "Boolean pin" -> Assumptions.None.withBool(0, true)
                else -> Assumptions.None
            }.withInt(1, 0)
            val state = LocalSearchState(LocalSearchModel.open(lowered), Random(3), assumptions)
            state.invariants = assertNotNull(DefinitionalSweep.infer(source, intArrayOf(1))).network(3, 1)
            if (protection == "source owner") state.seedImplicitFeasible()
            state.assignment.setInt(0, 0)
            state.assignment.setInt(2, 1)
            state.assignment.setBool(0, true)
            state.recompute()
            val before = state.assignment.snapshot()

            assertTrue(jumps(state, candidateVars = 1).isEmpty(), protection)
            assertEquals(before, state.assignment.snapshot(), protection)
        }
    }
}
