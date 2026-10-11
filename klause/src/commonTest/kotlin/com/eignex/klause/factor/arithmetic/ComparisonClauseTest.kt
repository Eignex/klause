package com.eignex.klause.factor.arithmetic

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.PropagationReasonOracle
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ComparisonClauseTest {

    @Test
    fun `literal bound repairs respect holes and predict committed cost`() {
        for (op in listOf(LinearOp.LE, LinearOp.GE, LinearOp.EQ)) {
            for (hole in listOf(false, true)) {
                val domain = if (hole) IntDomain(-50, 50).excludeValue(4) else IntDomain(-50, 50)
                val problem = Problem(
                    0, 1, arrayOf(domain),
                    arrayOf<Factor>(ComparisonClause(intArrayOf(0, 0), arrayOf(op, op), longArrayOf(4, 4))),
                )
                val state = LocalSearchState(LocalSearchModel.open(problem), Random(0))
                state.assignment.setInt(0, if (op == LinearOp.LE) 50L else -50L)
                state.recompute()
                state.weights.factorWeights[0] = 3.0
                val before = state.assignment.snapshot()
                val cost = state.cost

                state.factors[0].proposeRepairMoves(state, 0, state.moveSink)

                assertEquals(before, state.assignment.snapshot())
                assertTrue(state.moveSink.list.all { (it as Move.IntSet).newValue in domain })
                if (hole && op == LinearOp.EQ) {
                    assertTrue(state.moveSink.list.all { state.netDelta(it) + cost > 0L })
                } else {
                    val target = if (!hole) 4L else if (op == LinearOp.LE) 3L else 5L
                    val move = state.moveSink.list.first { it == Move.IntSet(0, target) }
                    val predicted = state.netDelta(move)
                    val weighted = state.weightedNetDelta(move)
                    assertEquals(before, state.assignment.snapshot())
                    state.apply(move)

                    assertEquals(0L, state.cost)
                    assertEquals(state.cost - cost, predicted)
                    assertEquals(3.0 * (state.cost - cost), weighted)
                    state.recompute()
                    assertEquals(0L, state.cost)
                }
            }
        }
    }

    @Test
    fun `disequality repairs skip holes at long endpoints`() {
        for (bound in listOf(Long.MIN_VALUE, Long.MAX_VALUE)) {
            val domain = if (bound == Long.MIN_VALUE) {
                IntDomain(bound, bound + 3).excludeValue(bound + 1)
            } else {
                IntDomain(bound - 3, bound).excludeValue(bound - 1)
            }
            val problem = Problem(
                0, 1, arrayOf(domain),
                arrayOf<Factor>(ComparisonClause(intArrayOf(0), arrayOf(LinearOp.NE), longArrayOf(bound))),
            )
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(0))
            state.assignment.setInt(0, bound)
            state.recompute()

            state.factors[0].proposeRepairMoves(state, 0, state.moveSink)
            state.apply(state.moveSink.list.single())

            assertEquals(if (bound == Long.MIN_VALUE) bound + 2 else bound - 2, state.assignment.intValue(0))
            assertEquals(0L, state.cost)
            state.recompute()
            assertEquals(0L, state.cost)
        }
    }

    @Test
    fun `literal bound repairs coordinate indicators and maintained products`() {
        for (bound in listOf(0, 1)) {
            val problem = Problem(
                1, 4, arrayOf(IntDomain(0, 10), IntDomain(0, 1), IntDomain(3, 3), IntDomain(0, 3)),
                arrayOf<Factor>(
                    ComparisonClause(intArrayOf(0), arrayOf(LinearOp.EQ), longArrayOf(8)),
                    ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 8),
                    ReifiedLinear(0, intArrayOf(1), intArrayOf(1), LinearOp.EQ, bound),
                    Product(1, 2, 3),
                ),
            )
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(0))
            state.invariants = assertNotNull(DefinitionalSweep.infer(problem.factors, 4)).network(4, 1)
            state.assignment.setInt(0, 0)
            state.assignment.setInt(1, 1L - bound)
            state.assignment.setInt(2, 3)
            state.assignment.setInt(3, 3L * (1L - bound))
            state.assignment.setBool(0, false)
            state.recompute()
            val before = state.assignment.snapshot()
            val cost = state.cost

            state.factors[0].proposeRepairMoves(state, 0, state.moveSink)
            val move = state.moveSink.list.filterIsInstance<Move.Compound>().first {
                Move.IntSet(0, 8) in it.parts
            }
            val predicted = state.netDelta(move)
            assertEquals(before, state.assignment.snapshot())
            state.apply(move)

            assertEquals(8L, state.assignment.intValue(0))
            assertTrue(state.assignment.boolValue(0))
            assertEquals(bound.toLong(), state.assignment.intValue(1))
            assertEquals(3L * bound, state.assignment.intValue(3))
            assertEquals(0L, state.cost)
            assertEquals(state.cost - cost, predicted)
            state.recompute()
            assertEquals(0L, state.cost)
        }
    }

    @Test
    fun `protected clause sources do not produce indicator only repairs`() {
        for (protection in listOf("pin", "owner")) {
            val problem = Problem(
                1, 1, arrayOf(IntDomain(0, 10)),
                arrayOf<Factor>(
                    ComparisonClause(intArrayOf(0), arrayOf(LinearOp.EQ), longArrayOf(8)),
                    ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 8),
                ),
            )
            val assumptions = if (protection == "pin") Assumptions.None.withInt(0, 0) else Assumptions.None
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(0), assumptions)
            if (protection == "owner") state.moveSink.setOwners(intArrayOf(7))
            state.moveSink.proposer = 0
            state.assignment.setInt(0, 0)
            state.assignment.setBool(0, false)
            state.recompute()
            val before = state.assignment.snapshot()

            state.factors[0].proposeRepairMoves(state, 0, state.moveSink)

            assertTrue(state.moveSink.list.isEmpty())
            assertEquals(before, state.assignment.snapshot())
        }
    }

    @Test
    fun `integer move scores match committed comparison clause cost`() {
        for (op in LinearOp.entries) {
            for ((initial, target) in listOf(-5L to 4L, 4L to 10L, 10L to -10L, -10L to 0L)) {
                val problem = Problem(
                    0, 2, Array(2) { IntDomain(-20, 20) },
                    arrayOf<Factor>(
                        ComparisonClause(
                            intArrayOf(0, 0, 1), arrayOf(op, LinearOp.LE, LinearOp.EQ), longArrayOf(4, -8, 0),
                        ),
                    ),
                )
                val state = LocalSearchState(LocalSearchModel.open(problem), Random(0))
                state.assignment.setInt(0, initial)
                state.assignment.setInt(1, 20)
                state.recompute()
                state.weights.factorWeights[0] = 3.0
                val before = state.cost
                val move = Move.IntSet(0, target)

                val predicted = state.netDelta(move)
                val weighted = state.weightedNetDelta(move)
                assertEquals(initial, state.assignment.intValue(0))
                assertEquals(before, state.cost)
                state.apply(move)

                assertEquals(state.cost - before, predicted, "$op: $initial to $target")
                assertEquals(3.0 * (state.cost - before), weighted, "$op: $initial to $target")
                val committed = state.cost
                state.recompute()
                assertEquals(committed, state.cost)
            }
        }
    }

    private fun le(v: Int, c: Long) = Triple(v, LinearOp.LE, c)
    private fun ge(v: Int, c: Long) = Triple(v, LinearOp.GE, c)

    private fun clauseOf(lits: List<Triple<Int, LinearOp, Long>>) = ComparisonClause(
        vars = IntArray(lits.size) { lits[it].first },
        ops = Array(lits.size) { lits[it].second },
        consts = LongArray(lits.size) { lits[it].third },
    )

    @Test
    fun `unit propagation enforces the surviving literal`() {
        // (x <= 0) v (y >= 5), x in [1,3] forces the first literal false, so y >= 5 must hold; with
        // y in [0,4] that is impossible -> UNSAT, exercising the enforce-then-conflict path.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(1, 3), IntDomain(0, 4)),
            factors = arrayOf<Factor>(clauseOf(listOf(le(0, 0), ge(1, 5)))),
        )
        assertIs<SolveResult.Unsat>(BacktrackSolver(problem.bake()).solve(BacktrackParams(randomSeed = 1L)))
    }

    @Test
    fun `comparison clause deductions are implied by their reasons under carved holes`() {
        val rng = Random(0xCC10)
        repeat(300) { iter ->
            val n = 3
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = n,
                intDomains = Array(n) { IntDomain(0, 3) },
                factors = arrayOf<Factor>(
                    ComparisonClause(
                        vars = IntArray(n) { rng.nextInt(n) },
                        ops = Array(n) { LinearOp.entries[rng.nextInt(LinearOp.entries.size)] },
                        consts = LongArray(n) { rng.nextInt(4).toLong() },
                    ),
                ),
            )
            PropagationReasonOracle.assertReasonsImply(problem, "comparison-clause#$iter") { state ->
                (0 until 5).all {
                    val v = rng.nextInt(n)
                    when (rng.nextInt(3)) {
                        0 -> state.excludeIntValue(v, rng.nextInt(4).toLong())
                        1 -> state.tightenIntMin(v, 1L + rng.nextInt(2))
                        else -> state.tightenIntMax(v, 1L + rng.nextInt(2))
                    }
                }
            }
        }
    }
}
