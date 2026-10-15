package com.eignex.klause.localsearch

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.FactorReduction
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.objective.FunctionalObjective
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LocalSearchStateChannelingTest {
    @Test
    fun `scaled maintained literal channels should backsolve exact binary targets`() {
        for ((bound, outputCoefficient) in listOf(0 to 1L, 1 to 1L, 0 to -1L, 1 to -1L)) {
            val domains = arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(3, 3), IntDomain(0, 3))
            val rewrite = Product(1, 2, 3).structuralReduce(domains)
            assertTrue(rewrite is FactorReduction.Rewrite)
            val problem = Problem(
                1,
                4,
                domains,
                arrayOf<Factor>(
                    ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 0),
                    ReifiedLinear(0, intArrayOf(1), intArrayOf(1), LinearOp.EQ, bound),
                    rewrite.replacement.single(),
                ),
            )
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
            val network = assertNotNull(DefinitionalSweep.infer(problem, intArrayOf(1))).network(4, 1)
            state.invariants = InvariantNetwork(
                List(network.size) { network.node(it) } + listOf(
                    DefinitionalSweep.SweepNode.IntDef(
                        FunctionalObjective.Lin(
                            3,
                            outputCoefficient,
                            longArrayOf(-3 * outputCoefficient),
                            arrayOf(FunctionalObjective.Operand.v(1)),
                            0,
                        ),
                        intArrayOf(1),
                    ),
                ),
                4,
                1,
            )
            state.assignment.setInt(0, if (bound == 1) 0 else 1)
            state.assignment.setInt(1, 1)
            state.assignment.setInt(2, 3)
            state.assignment.setInt(3, 3)
            state.assignment.setBool(0, bound == 1)
            state.recompute()

            state.moveSink.addChannelingIntSet(state, 3, 1)
            assertTrue(state.moveSink.list.isEmpty())

            for (target in listOf(0L, 3L)) {
                state.moveSink.clear()
                state.moveSink.addChannelingIntSet(state, 3, target)
                val move = assertNotNull(state.moveSink.list.firstOrNull())
                val predicted = state.netDelta(move)
                state.apply(move)

                assertEquals(target, state.assignment.intValue(3))
                assertEquals(target / 3, state.assignment.intValue(1))
                assertEquals(0L, predicted)
                assertEquals(0L, state.cost)
            }
        }
    }

    @Test
    fun `scaled literal inverses should preserve protected coordinates and target domains`() {
        for (protection in listOf(
            "choice pin", "choice owner", "channel pin", "channel owner", "indicator pin",
            "output pin", "output owner", "output domain", "channel domain",
        )) {
            val domains = arrayOf(
                IntDomain(0, 1),
                IntDomain(if (protection == "channel domain") 1 else 0, 1),
                IntDomain(3, 3),
                IntDomain(if (protection == "output domain") 1 else 0, 3),
            )
            val problem = Problem(
                1,
                4,
                domains,
                arrayOf<Factor>(
                    ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 0),
                    ReifiedLinear(0, intArrayOf(1), intArrayOf(1), LinearOp.EQ, 1),
                    Linear(intArrayOf(1, -3), intArrayOf(3, 1), LinearOp.EQ, 0),
                ),
            )
            val assumptions = when (protection) {
                "choice pin" -> Assumptions.None.withInt(0, 0)
                "channel pin" -> Assumptions.None.withInt(1, 1)
                "indicator pin" -> Assumptions.None.withBool(0, true)
                "output pin" -> Assumptions.None.withInt(3, 3)
                else -> Assumptions.None
            }
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(3), assumptions)
            val network = assertNotNull(DefinitionalSweep.infer(problem, intArrayOf(1))).network(4, 1)
            state.invariants = InvariantNetwork(
                List(network.size) { network.node(it) } + listOf(
                    DefinitionalSweep.SweepNode.IntDef(
                        FunctionalObjective.Lin(
                            3,
                            1,
                            longArrayOf(-3),
                            arrayOf(FunctionalObjective.Operand.v(1)),
                            0,
                        ),
                        intArrayOf(1),
                    ),
                ),
                4,
                1,
            )
            val owned = when (protection) {
                "choice owner" -> 0
                "channel owner" -> 1
                "output owner" -> 3
                else -> -1
            }
            if (owned >= 0) state.moveSink.setOwners(IntArray(4) { if (it == owned) 4 else -1 })
            for ((id, value) in longArrayOf(0, 1, 3, 3).withIndex()) state.assignment.setInt(id, value)
            state.assignment.setBool(0, true)
            state.recompute()
            val snapshot = state.assignment.snapshot()

            state.moveSink.addChannelingIntSet(state, 3, 0)

            assertTrue(state.moveSink.list.isEmpty(), protection)
            assertEquals(snapshot, state.assignment.snapshot(), protection)
            assertEquals(0L, state.cost, protection)
        }
    }

    @Test
    fun `duplicate equality rows should maintain the indicator and its binary channel`() {
        val problem = Problem(
            1,
            2,
            arrayOf(IntDomain(0, 2), IntDomain(0, 1)),
            arrayOf<Factor>(
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 1),
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 1),
                ReifiedLinear(0, intArrayOf(1), intArrayOf(1), LinearOp.EQ, 1),
            ),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 0)
        state.assignment.setBool(0, false)
        state.recompute()

        state.apply(state.synthesizeChannelingMove(0, 1))

        assertTrue(state.assignment.boolValue(0))
        assertEquals(1L, state.assignment.intValue(1))
        assertEquals(0L, state.cost)
    }

    @Test
    fun `indicator channels and their product outputs should follow coordinate moves`() {
        for (bound in listOf(0, 1)) {
            val problem = Problem(
                1,
                6,
                arrayOf(
                    IntDomain(0, 2),
                    IntDomain(0, 1),
                    IntDomain(3, 3),
                    IntDomain(0, 3),
                    IntDomain(0, 1),
                    IntDomain(0, 3),
                ),
                arrayOf<Factor>(
                    ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 1),
                    ReifiedLinear(0, intArrayOf(1), intArrayOf(1), LinearOp.EQ, bound),
                    Product(1, 2, 3),
                    ReifiedLinear(0, intArrayOf(1), intArrayOf(4), LinearOp.EQ, 1 - bound),
                    Product(4, 2, 5),
                ),
            )
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
            state.assignment.setInt(0, 0)
            state.assignment.setBool(0, false)
            state.assignment.setInt(1, 1L - bound)
            state.assignment.setInt(2, 3)
            state.assignment.setInt(3, 3L * (1L - bound))
            state.assignment.setInt(4, bound.toLong())
            state.assignment.setInt(5, 3L * bound)
            state.invariants = assertNotNull(DefinitionalSweep.infer(problem.factors, 6)).network(6, 1)
            state.recompute()

            for (coordinate in listOf(1L, 2L, 1L)) {
                val move = state.synthesizeChannelingMove(0, coordinate)
                val predicted = state.netDelta(move)
                state.apply(move)
                val cost = state.cost
                val expected = if (coordinate == 1L) bound.toLong() else 1L - bound

                assertEquals(expected, state.assignment.intValue(1))
                assertEquals(3L * expected, state.assignment.intValue(3))
                assertEquals(1L - expected, state.assignment.intValue(4))
                assertEquals(3L * (1L - expected), state.assignment.intValue(5))
                assertEquals(0L, predicted)
                assertEquals(0L, cost)
                state.recompute()
                assertEquals(cost, state.cost)
            }
        }
    }

    @Test
    fun `indicator moves should retain protected channel coordinates`() {
        for (protection in listOf("pin", "owner", "definition")) {
            val problem = Problem(
                1,
                3,
                arrayOf(IntDomain(0, 2), IntDomain(0, 1), IntDomain(0, 0)),
                arrayOf<Factor>(
                    ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 1),
                    ReifiedLinear(0, intArrayOf(1), intArrayOf(1), LinearOp.EQ, 1),
                    Product(2, 2, 1),
                ),
            )
            val assumptions = if (protection == "pin") Assumptions.None.withInt(1, 0) else Assumptions.None
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(3), assumptions)
            if (protection == "owner") state.moveSink.setOwners(intArrayOf(-1, 1, -1))
            if (protection == "definition") {
                state.invariants = assertNotNull(DefinitionalSweep.infer(problem.factors, 3)).network(3, 1)
            }
            state.assignment.setInt(0, 0)
            state.assignment.setInt(1, 0)
            state.assignment.setInt(2, 0)
            state.assignment.setBool(0, false)
            state.recompute()

            state.apply(state.synthesizeChannelingMove(0, 1))

            assertEquals(0L, state.assignment.intValue(1))
            assertEquals(1L, state.cost)
        }
    }

    @Test
    fun `nonbinary equality inputs should retain their independent value`() {
        val problem = Problem(
            1,
            2,
            arrayOf(IntDomain(0, 2), IntDomain(0, 2)),
            arrayOf<Factor>(
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 1),
                ReifiedLinear(0, intArrayOf(1), intArrayOf(1), LinearOp.EQ, 1),
            ),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 2)
        state.assignment.setBool(0, false)
        state.recompute()

        state.apply(state.synthesizeChannelingMove(0, 1))

        assertEquals(2L, state.assignment.intValue(1))
        assertEquals(1L, state.cost)
    }

    @Test
    fun `indicator moves should not set a channel outside its root domain`() {
        val problem = Problem(
            1,
            2,
            arrayOf(IntDomain(0, 2), IntDomain(0, 0)),
            arrayOf<Factor>(
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 1),
                ReifiedLinear(0, intArrayOf(1), intArrayOf(1), LinearOp.EQ, 1),
            ),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 0)
        state.assignment.setBool(0, false)
        state.recompute()

        state.apply(state.synthesizeChannelingMove(0, 1))

        assertEquals(0L, state.assignment.intValue(1))
        assertTrue(state.intValuesInDomain())
        assertEquals(1L, state.cost)
    }

    @Test
    fun `Linear EQ channeling should skip partners whose targets are outside their domains`() {
        for ((domain, current) in listOf(IntDomain(0, 1) to 0L, IntDomain(0, 2).excludeValue(1) to 2L)) {
            val problem = Problem(
                0,
                3,
                arrayOf(IntDomain(0, 1), domain, IntDomain(0, 1)),
                arrayOf<Factor>(Linear(longArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.EQ, current + 1)),
            )
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
            state.assignment.setInt(0, 0)
            state.assignment.setInt(1, current)
            state.assignment.setInt(2, 1)
            state.recompute()

            state.moveSink.addChannelingIntSet(state, 0, 1)
            val move = state.moveSink.list.single()
            val predicted = state.netDelta(move)
            state.apply(move)

            assertEquals(1L, state.assignment.intValue(0))
            assertEquals(current, state.assignment.intValue(1))
            assertEquals(0L, state.assignment.intValue(2))
            assertEquals(0L, predicted)
            assertEquals(0L, state.cost)
            assertTrue(state.intValuesInDomain())
            state.recompute()
            assertEquals(0L, state.cost)
        }
    }

    @Test
    fun `Linear EQ channeling should skip protected partners`() {
        for (protection in listOf("pin", "owner", "definition")) {
            val problem = Problem(
                0,
                5,
                arrayOf(
                    IntDomain(0, 1),
                    IntDomain(0, 1),
                    IntDomain(0, 1),
                    IntDomain(1, 1),
                    IntDomain(1, 1),
                ),
                arrayOf<Factor>(
                    Linear(intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.EQ, 2),
                    Product(3, 4, 1),
                ),
            )
            val assumptions = if (protection == "pin") Assumptions.None.withInt(1, 1) else Assumptions.None
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(3), assumptions)
            if (protection == "owner") state.moveSink.setOwners(intArrayOf(-1, 7, -1, -1, -1))
            if (protection == "definition") {
                state.invariants = assertNotNull(DefinitionalSweep.infer(problem.factors, 5)).network(5, 0)
            }
            for ((variable, value) in longArrayOf(0, 1, 1, 1, 1).withIndex()) {
                state.assignment.setInt(variable, value)
            }
            state.recompute()

            state.moveSink.addChannelingIntSet(state, 0, 1)
            val move = state.moveSink.list.single()
            val predicted = state.netDelta(move)
            state.apply(move)

            assertEquals(1L, state.assignment.intValue(0))
            assertEquals(1L, state.assignment.intValue(1))
            assertEquals(0L, state.assignment.intValue(2))
            assertEquals(0L, predicted)
            assertEquals(0L, state.cost)
            state.recompute()
            assertEquals(0L, state.cost)
        }
    }

    @Test
    fun `Linear EQ channeling should prefer an admissible lower coefficient partner`() {
        val problem = Problem(
            0,
            3,
            arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 2)),
            arrayOf<Factor>(Linear(intArrayOf(2, 2, 1), intArrayOf(0, 1, 2), LinearOp.EQ, 4)),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 1)
        state.assignment.setInt(2, 2)
        state.recompute()

        state.moveSink.addChannelingIntSet(state, 0, 1)
        val move = state.moveSink.list.single()
        val predicted = state.netDelta(move)
        state.apply(move)

        assertEquals(1L, state.assignment.intValue(0))
        assertEquals(1L, state.assignment.intValue(1))
        assertEquals(0L, state.assignment.intValue(2))
        assertEquals(0L, predicted)
        assertEquals(0L, state.cost)
        state.recompute()
        assertEquals(0L, state.cost)
    }

    @Test
    fun `Linear EQ channeling should retain the driving move when no partner is admissible`() {
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 1), IntDomain(0, 1)),
            arrayOf<Factor>(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 0)),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 0)
        state.recompute()

        state.moveSink.addChannelingIntSet(state, 0, 1)
        val move = state.moveSink.list.single()
        val predicted = state.netDelta(move)
        state.apply(move)

        assertEquals(Move.IntSet(0, 1), move)
        assertEquals(1L, state.assignment.intValue(0))
        assertEquals(0L, state.assignment.intValue(1))
        assertEquals(1L, predicted)
        assertEquals(1L, state.cost)
        state.recompute()
        assertEquals(1L, state.cost)
    }

    @Test
    fun `Linear EQ channeling should never counter-shift a sibling into a hole`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 5), IntDomain(0, 5).excludeValue(3)),
            factors = arrayOf<Factor>(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 4)),
        )
        val state = LocalSearchState(problem.bake(), Random(1))
        state.assignment.setInt(0, 2)
        state.assignment.setInt(1, 2)
        state.recompute()

        val move = state.synthesizeChannelingMove(intVar = 0, newValue = 1)

        assertEquals(Move.IntSet(0, 1), move, "absorbing the drift would put y on the hole at 3")
    }
}
