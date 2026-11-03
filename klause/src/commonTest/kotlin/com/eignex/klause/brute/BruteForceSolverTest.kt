package com.eignex.klause.brute

import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.intdomain.intDomainFromSurvivors
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.ScoringProjection
import com.eignex.klause.localsearch.ScoringProjectionProvider
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.AssignmentCheckCapability
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.solver.ScoringCapability
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.TerminationReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BruteForceSolverTest {
    @Test
    fun `unit equality channels should retain every source witness`() {
        val channel = ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 0)
        val problem = Problem(1, 1, arrayOf(IntDomain(0, 1)), arrayOf<Factor>(channel)).bake()

        val actual = BruteForceSolver(problem).enumerate(BruteForceParams(randomSeed = 0L))
            .map { it.intValue(0) to it.boolValue(0) }.toSet()

        assertEquals(setOf(0L to true, 1L to false), actual)
    }

    @Test
    fun `channel rejection should consume the assignment budget`() {
        val channel = ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 0)
        val solver = BruteForceSolver(Problem(1, 1, arrayOf(IntDomain(0, 1)), arrayOf<Factor>(channel)).bake())

        val partial = solver.minimize(LinearObjective(), BruteForceParams(randomSeed = 0L, maxSteps = 3))
        val complete = solver.minimize(LinearObjective(), BruteForceParams(randomSeed = 0L, maxSteps = 4))

        assertIs<MinimizeResult.BestFound>(partial)
        assertIs<MinimizeResult.Optimal>(complete)
    }

    @Test
    fun `custom scoring should receive every enumerated assignment`() {
        val observed = mutableSetOf<Pair<Long, Boolean>>()
        val channel = ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 0)
        val providers = FactorProjectionProviders(scoring = ScoringProjectionProvider {
            ScoringProjection(ScoringCapability.GRADED, AssignmentCheckCapability.EXACT) { _, _ ->
                object : Invariant {
                    override fun initialize(state: LocalSearchState, factorId: Int) {
                        observed.add(state.assignment.intValue(0) to state.assignment.boolValue(0))
                    }

                    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean =
                        state.assignment.boolValue(0) != (state.assignment.intValue(0) == 0L)
                }
            }
        })
        val problem = Problem(1, 1, arrayOf(IntDomain(0, 1)), arrayOf<Factor>(channel)).bake(providers)

        val actual = BruteForceSolver(problem).enumerate(BruteForceParams(randomSeed = 0L))
            .map { it.intValue(0) to it.boolValue(0) }.toSet()

        assertEquals(setOf(0L to true, 0L to false, 1L to true, 1L to false), observed)
        assertEquals(setOf(0L to true, 1L to false), actual)
    }

    @Test
    fun `zero budget should decline an unenumerable domain without walking it`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, Int.MAX_VALUE.toLong())), emptyArray()).bake()

        val result = BruteForceSolver(problem).solve(BruteForceParams(maxSteps = 0))

        assertIs<SolveResult.Unknown>(result)
    }

    @Test
    fun `minimize should distinguish a full assignment budget from a truncated walk`() {
        val solver = BruteForceSolver(Problem(2, 0, emptyArray(), emptyArray()).bake())

        val partial = solver.minimize(LinearObjective(), BruteForceParams(randomSeed = 0L, maxSteps = 3))
        val complete = solver.minimize(LinearObjective(), BruteForceParams(randomSeed = 0L, maxSteps = 4))

        assertIs<MinimizeResult.BestFound>(partial)
        assertIs<MinimizeResult.Optimal>(complete)
    }

    @Test
    fun `chunked enumeration should retain distinct owned assignments within its budget`() {
        val solver = BruteForceSolver(Problem(61, 0, emptyArray(), emptyArray()).bake())

        val samples = solver.enumerate(BruteForceParams(randomSeed = 0L, maxSteps = 3)).toList()
        val actual = samples.map { it.bools.toList() }

        assertEquals(3, actual.size)
        assertEquals(3, actual.toSet().size)
    }

    @Test
    fun `solve should report unknown when the assignment budget prevents search`() {
        val solver = BruteForceSolver(Problem(1, 0, emptyArray(), emptyArray()).bake())
        val params = listOf(BruteForceParams(maxSteps = 0), BruteForceParams(maxInstructions = 0))

        for (param in params) {
            val result = solver.solve(param)

            assertEquals(TerminationReason.BudgetExhausted, assertIs<SolveResult.Unknown>(result).reason)
        }
    }

    @Test
    fun `minimize should prove optimality when the budget covers the whole assignment space`() {
        val solver = BruteForceSolver(Problem(0, 0, emptyArray(), emptyArray()).bake())

        val result = solver.minimize(LinearObjective(), BruteForceParams(maxSteps = 1))

        assertIs<MinimizeResult.Optimal>(result)
    }

    @Test
    fun `admission should use survivor cardinalities across signed word spans`() {
        val domain = intDomainFromSurvivors(longArrayOf(Long.MIN_VALUE, 0, Long.MAX_VALUE))
        val problem = Problem(0, 2, arrayOf(domain, domain), emptyArray()).bake()

        assertTrue(BruteForceSolver.fits(problem, cap = 9))
        assertFalse(BruteForceSolver.fits(problem, cap = 8))
    }

    @Test
    fun `enumeration should visit every sparse signed word assignment once`() {
        val values = longArrayOf(Long.MIN_VALUE, 0, Long.MAX_VALUE)
        val domain = intDomainFromSurvivors(values)
        val problem = Problem(0, 2, arrayOf(domain, domain), emptyArray()).bake()

        val actual = BruteForceSolver(problem).enumerate(BruteForceParams(randomSeed = 0L))
            .map { it.ints.toList() }.toList()

        val expected = values.flatMap { x -> values.map { y -> listOf(x, y) } }.toSet()
        assertEquals(9, actual.size)
        assertEquals(expected, actual.toSet())
    }
}
