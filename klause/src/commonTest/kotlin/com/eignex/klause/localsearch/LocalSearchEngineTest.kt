package com.eignex.klause.localsearch

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.result.TerminationReason
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LocalSearchEngineTest {

    // x + k = 2.5 and x ≤ 1 over a continuous x in [0, 10] and an integer k in [0, 5]: k ≥ 2 and x = 2.5 - k.
    private fun mixedProblem(): Problem = Problem(
        numBoolVars = 0,
        numIntVars = 1,
        intDomains = arrayOf(IntDomain(0, 5)),
        factors = arrayOf<Factor>(
            Linear(intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), doubleArrayOf(1.0), LinearOp.EQ, 2.5),
            Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), LinearOp.LE, 1.0),
        ),
        numRealVars = 1,
        realLower = doubleArrayOf(0.0),
        realUpper = doubleArrayOf(10.0),
    )

    private fun engine(completion: CandidateCompletion) =
        LocalSearchEngine(LocalSearchModel.of(mixedProblem().bake()), completion = completion)

    @Test
    fun `a model with continuous columns is declined without a completion`() {
        val result = LocalSearchSolver(mixedProblem().bake()).solve(LocalSearchParams(maxFlips = 100, randomSeed = 1))

        assertEquals(TerminationReason.Unsupported, assertIs<SolveResult.Unknown>(result).reason)
    }

    @Test
    fun `a candidate over continuous columns satisfies its rows within tolerance`() {
        val result = engine { candidate, _ ->
            Completion.Witness(
                candidate,
            )
        }.solve(LocalSearchParams(maxFlips = 5_000, randomSeed = 2), null)

        val sample = assertIs<SolveResult.Sat>(result).assignment
        val x = sample.approximateRealValue(0)
        val k = sample.ints[0]
        assertTrue(abs(x + k - 2.5) <= 1e-6 && x <= 1.0 + 1e-6, "x=$x k=$k")
    }

    @Test
    fun `search goes on past a refuted candidate`() {
        var calls = 0
        val result = engine { candidate, _ ->
            calls++
            if (calls == 1) Completion.Refuted(intArrayOf(0)) else Completion.Witness(candidate)
        }.solve(LocalSearchParams(maxFlips = 5_000, randomSeed = 2), null)

        assertIs<SolveResult.Sat>(result)
        assertEquals(2, calls)
    }

    @Test
    fun `the work a completion reports is charged to the search budget`() {
        var calls = 0
        val result = engine { _, _ ->
            calls++
            Completion.Refuted(work = 2_000L)
        }.solve(LocalSearchParams(maxFlips = 5_000, randomSeed = 2), null)

        assertIs<SolveResult.Unknown>(result)
        assertTrue(calls in 1..3, "calls=$calls")
    }

    @Test
    fun `decided candidates are counted in the stats`() {
        var calls = 0
        val result = engine { candidate, _ ->
            calls++
            if (calls == 1) Completion.Refuted() else Completion.Witness(candidate)
        }.solve(LocalSearchParams(maxFlips = 5_000, randomSeed = 2), null)

        val ls = assertIs<SolveResult.Sat>(result).stats.ls
        assertEquals(2.0, ls.completions.sum)
        assertEquals(1.0, ls.completionsRefuted.sum)
    }
}
