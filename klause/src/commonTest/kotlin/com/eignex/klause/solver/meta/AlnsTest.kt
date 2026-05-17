package com.eignex.klause.solver.meta

import com.eignex.klause.solver.localsearch.meta.Alns
import com.eignex.klause.solver.localsearch.meta.DestroyOperator
import com.eignex.klause.solver.localsearch.meta.FreedVars
import com.eignex.klause.solver.localsearch.meta.RouletteWheelBandit

import com.eignex.klause.solver.LinearObjective
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.localsearch.AcceptanceCriterion
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.solver.localsearch.LocalSearchSolver
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AlnsTest {

    @Test
    fun `roulette wheel bandit updates weights on rewarded picks`() {
        val bandit = RouletteWheelBandit(numOperators = 2, reactionFactor = 0.5, segmentLength = 4)
        val rng = Random(42)
        val initialW = bandit.weights.copyOf()
        // Reward operator 0 repeatedly; bandit segment should bump its weight up over the
        // tied initial allocation.
        repeat(4) {
            bandit.pick(rng)  // observe a pick; not used here
            bandit.reward(0, reward = 3.0)
            bandit.reward(1, reward = 0.0)
            bandit.advance()
        }
        assertTrue(bandit.weights[0] > initialW[0], "operator 0 weight should grow: ${bandit.weights[0]} vs ${initialW[0]}")
        assertTrue(bandit.weights[0] > bandit.weights[1], "operator 0 should now outweigh operator 1")
    }

    @Test
    fun `roulette wheel respects min weight floor`() {
        val bandit = RouletteWheelBandit(numOperators = 2, reactionFactor = 1.0, segmentLength = 1, minWeight = 0.5)
        // Give operator 0 zero rewards for many segments — weight should not drop below minWeight.
        repeat(20) {
            bandit.reward(0, 0.0)
            bandit.advance()
        }
        assertTrue(bandit.weights[0] >= 0.5, "weight escaped minWeight floor: ${bandit.weights[0]}")
    }

    @Test
    fun `random destroy returns expected fraction`() {
        val problem = Problem(numBoolVars = 10, numIntVars = 0, intDomains = emptyArray(), factors = emptyList())
        val incumbent = Sample(BooleanArray(10) { false }, IntArray(0))
        val obj = LinearObjective(boolWeights = DoubleArray(10) { 1.0 })
        val freed = DestroyOperator.Random.destroy(Random(0), problem, incumbent, obj, fraction = 0.3)
        assertEquals(3, freed.bools.size, "expected 3 freed bools (fraction 0.3 of 10)")
        // All distinct
        assertEquals(freed.bools.toSet().size, freed.bools.size, "freed bools should be distinct")
    }

    @Test
    fun `adjacency related destroy stays inside connected components`() {
        // Two disconnected sub-problems sharing nothing:
        //   factor A: AtLeastOne over bool vars 0..3
        //   factor B: AtLeastOne over bool vars 4..7
        // Adjacency BFS from a seed in factor A should free vars only from A (until it
        // exhausts the component, at which point it re-seeds; with small `fraction` we
        // stay within one component).
        val fA = Cardinality.atLeastOne(IntArray(4) { Lit.make(it, true) })
        val fB = Cardinality.atLeastOne(IntArray(4) { Lit.make(it + 4, true) })
        val problem = Problem(numBoolVars = 8, numIntVars = 0, intDomains = emptyArray(), factors = listOf(fA, fB))
        val incumbent = Sample(BooleanArray(8) { false }, IntArray(0))
        val obj = LinearObjective(boolWeights = DoubleArray(8) { 1.0 })
        // Free 2 of 8 = fraction 0.25. Starts from one component and stays inside it.
        val freed = DestroyOperator.AdjacencyRelated.destroy(Random(0), problem, incumbent, obj, fraction = 0.25)
        assertEquals(2, freed.bools.size)
        val componentA = freed.bools.all { it in 0..3 }
        val componentB = freed.bools.all { it in 4..7 }
        assertTrue(componentA || componentB, "freed vars should be in one component: ${freed.bools.toList()}")
    }

    @Test
    fun `adjacency related destroy reaches fraction across components when needed`() {
        // Same two disconnected components, but free 6 of 8 (fraction 0.75) — adjacency
        // BFS must re-seed into the second component after exhausting the first.
        val fA = Cardinality.atLeastOne(IntArray(4) { Lit.make(it, true) })
        val fB = Cardinality.atLeastOne(IntArray(4) { Lit.make(it + 4, true) })
        val problem = Problem(numBoolVars = 8, numIntVars = 0, intDomains = emptyArray(), factors = listOf(fA, fB))
        val incumbent = Sample(BooleanArray(8) { false }, IntArray(0))
        val obj = LinearObjective(boolWeights = DoubleArray(8) { 1.0 })
        val freed = DestroyOperator.AdjacencyRelated.destroy(Random(0), problem, incumbent, obj, fraction = 0.75)
        assertEquals(6, freed.bools.size)
        val fromA = freed.bools.count { it in 0..3 }
        val fromB = freed.bools.count { it in 4..7 }
        assertTrue(fromA > 0 && fromB > 0, "expected vars from both components, got A=$fromA B=$fromB")
    }

    @Test
    fun `worst objective destroy picks high contribution vars`() {
        // 4 bool vars, only var 3 is set true and has the largest weight; destroy fraction 0.25 → 1 var.
        // WorstObjective should pick var 3.
        val problem = Problem(numBoolVars = 4, numIntVars = 0, intDomains = emptyArray(), factors = emptyList())
        val incumbent = Sample(booleanArrayOf(false, false, false, true), IntArray(0))
        val obj = LinearObjective(boolWeights = doubleArrayOf(1.0, 2.0, 3.0, 100.0))
        val freed = DestroyOperator.WorstObjective.destroy(Random(0), problem, incumbent, obj, fraction = 0.25)
        assertEquals(1, freed.bools.size)
        assertEquals(3, freed.bools[0], "expected var 3 (highest weighted-and-set)")
    }

    @Test
    fun `alns minimizes weighted exactly-one`() {
        val factor = Cardinality.exactlyOne(intArrayOf(
            Lit.make(0, true), Lit.make(1, true), Lit.make(2, true), Lit.make(3, true),
        ))
        val problem = Problem(4, 0, emptyArray(), listOf(factor))
        val objective = LinearObjective(boolWeights = doubleArrayOf(10.0, 5.0, 8.0, 3.0))
        val inner = LocalSearchSolver(problem)
        val alns = Alns(
            inner = inner,
            destroyFraction = 0.5,
            maxIterations = 20,
            flipsPerIteration = 200L,
            acceptance = AcceptanceCriterion.BetterOrEqual,
        )
        val sample = alns.minimize(objective, LocalSearchParams(maxFlips = 5_000L, randomSeed = 1L))
        assertNotNull(sample)
        assertEquals(3.0, objective.evaluate(sample))
    }

    @Test
    fun `freed vars empty triggers reward and continue`() {
        // Edge case: destroy op returns empty set. ALNS should reward (rejectedReward) and not crash.
        val problem = Problem(numBoolVars = 1, numIntVars = 0, intDomains = emptyArray(),
            factors = listOf(Cardinality.atLeastOne(intArrayOf(Lit.make(0, true)))))
        val objective = LinearObjective(boolWeights = doubleArrayOf(1.0))
        val emptyOp = DestroyOperator { _, _, _, _, _ -> FreedVars(IntArray(0), IntArray(0)) }
        val inner = LocalSearchSolver(problem)
        val alns = Alns(inner = inner, destroyOperators = listOf(emptyOp), maxIterations = 5, flipsPerIteration = 100L)
        val sample = alns.minimize(objective, LocalSearchParams(maxFlips = 1_000L, randomSeed = 0L))
        assertNotNull(sample, "ALNS should still return the initial solve's incumbent")
    }
}
