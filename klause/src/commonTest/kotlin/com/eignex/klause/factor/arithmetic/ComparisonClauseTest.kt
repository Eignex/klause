package com.eignex.klause.factor.arithmetic

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.PropagationReasonOracle
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ComparisonClauseTest {

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
