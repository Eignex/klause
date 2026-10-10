package com.eignex.klause.localsearch

import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiteralDefinitionTest {
    @Test
    fun `hinted literal channels follow free Boolean inputs`() {
        for (bound in listOf(0, 1)) {
            val problem = Problem(
                1, 1, arrayOf(IntDomain(0, 1)),
                arrayOf<Factor>(ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, bound)),
            )
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
            state.invariants = assertNotNull(DefinitionalSweep.infer(problem, intArrayOf(0))).network(1, 1)
            state.assignment.setBool(0, false)
            state.assignment.setInt(0, if (bound == 1) 0L else 1L)
            state.recompute()

            val predicted = state.netDelta(Move.BoolFlip(0))
            state.apply(Move.BoolFlip(0))

            assertEquals(if (bound == 1) 1L else 0L, state.assignment.intValue(0))
            assertEquals(0L, predicted)
            assertEquals(0L, state.cost)
            state.recompute()
            assertEquals(0L, state.cost)
        }
    }

    @Test
    fun `clipped channels preserve source predicates during seeding`() {
        val problem = Problem(
            1, 2, arrayOf(IntDomain(0, 2), IntDomain(1, 1)),
            arrayOf<Factor>(
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 0),
                ReifiedLinear(0, intArrayOf(1), intArrayOf(1), LinearOp.EQ, 0),
            ),
        )
        val sweep = assertNotNull(DefinitionalSweep.infer(problem, intArrayOf(1)))
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
        state.assignment.setInt(0, 0)
        state.assignment.setBool(0, false)

        sweep.sweep(state.assignment, state.rootDomains, problem.factors)
        state.recompute()

        assertTrue(state.assignment.boolValue(0))
        assertEquals(1L, state.assignment.intValue(1))
        assertEquals(0, state.factorDegree[0])
        assertTrue(state.factorDegree[1] > 0)
        assertTrue(state.cost > 0L)
    }

    @Test
    fun `predicates constrained outside channel cones retain Boolean repairs`() {
        val problem = Problem(
            1, 2, arrayOf(IntDomain(0, 2), IntDomain(0, 1)),
            arrayOf<Factor>(
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 0),
                ReifiedLinear(0, intArrayOf(1), intArrayOf(1), LinearOp.EQ, 1),
                Clause(intArrayOf(Lit.make(0, true))),
            ),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
        state.invariants = assertNotNull(DefinitionalSweep.infer(problem, intArrayOf(1))).network(2, 1)
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 1)
        state.assignment.setBool(0, true)
        state.recompute()

        state.moveSink.addBoolFlip(0)
        val move = state.moveSink.list.single()
        val predicted = state.netDelta(move)
        state.apply(move)

        assertEquals(0L, state.assignment.intValue(1))
        assertTrue(state.factorDegree[0] > 0)
        assertEquals(0, state.factorDegree[1])
        assertTrue(state.factorDegree[2] > 0)
        assertEquals(state.cost, predicted)
        val cost = state.cost
        state.recompute()
        assertEquals(cost, state.cost)
    }

    @Test
    fun `unhinted and wider channels remain searchable`() {
        for (hinted in listOf(false, true)) {
            val problem = Problem(
                1, 1, arrayOf(IntDomain(0, if (hinted) 2L else 1L)),
                arrayOf<Factor>(ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 1)),
            )
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
            state.invariants = DefinitionalSweep.infer(problem, if (hinted) intArrayOf(0) else intArrayOf())?.network(1, 1)

            state.moveSink.addIntSet(0, 1)

            assertEquals(Move.IntSet(0, 1), state.moveSink.list.single())
        }
    }

    @Test
    fun `competing channel definitions remain searchable`() {
        val problem = Problem(
            1, 1, arrayOf(IntDomain(0, 1)),
            arrayOf<Factor>(
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 0),
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 1),
            ),
        )

        val sweep = DefinitionalSweep.infer(problem, intArrayOf(0))

        assertNull(sweep)
    }

    @Test
    fun `cyclic predicate channel and product cones remain searchable`() {
        val problem = Problem(
            1, 3, arrayOf(IntDomain(0, 1), IntDomain(1, 1), IntDomain(0, 1)),
            arrayOf<Factor>(
                ReifiedLinear(0, intArrayOf(1), intArrayOf(2), LinearOp.EQ, 1),
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 1),
                Product(0, 1, 2),
            ),
        )

        val sweep = DefinitionalSweep.infer(problem, intArrayOf(0))

        assertNull(sweep)
    }

    @Test
    fun `functional gradients decline literal channel cones`() {
        val problem = Problem(
            1, 3, arrayOf(IntDomain(0, 1), IntDomain(3, 3), IntDomain(0, 3)),
            arrayOf<Factor>(
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 1),
                Product(0, 1, 2),
            ),
        )
        val sweep = assertNotNull(DefinitionalSweep.infer(problem, intArrayOf(0)))

        val objective = sweep.functionalObjective(intArrayOf(2), longArrayOf(1), 0, true)

        assertNull(objective)
    }
}
