package com.eignex.klause.factor

import com.eignex.klause.brute.BruteForceParams
import com.eignex.klause.brute.BruteForceSolver
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.intdomain.intDomainFromSurvivors
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.invariantProjection
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.propagation.bake
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FactorPropagationOracleTest {

    private val deductions: Map<String, (PropagationState) -> Boolean> = mapOf(
        "min" to { it.tightenIntMin(0, 1L) },
        "max" to { it.tightenIntMax(0, 1L) },
        "pin" to { it.tightenIntMin(0, 1L) && it.tightenIntMax(0, 1L) },
        "hole" to { it.excludeIntValue(0, 1L) },
    )

    private fun overPruned(prune: (PropagationState) -> Boolean): Problem {
        val relation = Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 2)
        // Keep the relation's semantics and replace only its deductions with a faulty projection.
        val faulty = object : Factor by relation, Invariant by relation.invariantProjection(), Propagator {
            override fun propagate(state: PropagationState, factorId: Int): Boolean = prune(state)
        }
        return Problem(0, 1, arrayOf(IntDomain(0, 2)), arrayOf<Factor>(faulty))
    }

    @Test
    fun `declared reference rejects unsound root pruning`() {
        for ((label, prune) in deductions) {
            val problem = overPruned(prune)
            val bakedSolutions = BruteForceSolver(problem.bake()).enumerate(BruteForceParams(randomSeed = 0L)).toList()

            val soundness = assertFailsWith<AssertionError> { FactorPropagationOracle.assertSound(problem, label) }
            val gac = assertFailsWith<AssertionError> { FactorPropagationOracle.assertGac(problem, label) }
            assertTrue(soundness.message.orEmpty().contains("satisfying assignment"), label)
            assertTrue(gac.message.orEmpty().contains("satisfying assignment"), label)

            assertEquals(if (label == "pin") 1 else 2, bakedSolutions.size, label)
            assertEquals(3L, problem.finiteIntDomain(0).valueCount, label)
        }
    }

    @Test
    fun `declared holes and both Boolean values reach direct semantics`() {
        val problem = Problem(1, 1, arrayOf(IntDomain(0, 2).excludeValue(1L)), emptyArray())
        val visited = HashSet<Pair<Boolean, Long>>()

        FactorPropagationOracle.assertGac(problem) { sample ->
            visited.add(sample.bools.single() to sample.ints.single())
            true
        }

        assertEquals(setOf(false to 0L, false to 2L, true to 0L, true to 2L), visited)
    }

    @Test
    fun `direct semantics reject a faulty invariant`() {
        val relation = Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 1)
        val faulty = object : Factor by relation, Invariant, Propagator {}
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 2)), arrayOf<Factor>(faulty))

        val failure = assertFailsWith<AssertionError> {
            FactorPropagationOracle.assertSound(problem) { it.ints.single() <= 1L }
        }

        assertTrue(failure.message.orEmpty().contains("invariant disagrees with direct semantics"))
    }

    @Test
    fun `a baked model cannot masquerade as original declarations`() {
        val problem = overPruned(deductions.getValue("min")).bake()

        assertFailsWith<IllegalArgumentException> { FactorPropagationOracle.assertSound(problem) }
    }

    @Test
    fun `the enumeration cap applies before sound root pruning`() {
        val problem = Problem(
            0, 1, arrayOf(IntDomain(0, 1L shl 18)),
            arrayOf<Factor>(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 0)),
        )

        assertFailsWith<IllegalArgumentException> { FactorPropagationOracle.assertSound(problem) }
    }

    @Test
    fun `the empty assignment is classified exactly once`() {
        val problem = Problem(0, 0, emptyArray(), emptyArray())
        var visited = 0

        FactorPropagationOracle.assertGac(problem) {
            visited++
            true
        }

        assertEquals(1, visited)
    }

    @Test
    fun `wide sparse declarations select exact invariant semantics`() {
        val magnitude = 1L shl 61
        val problem = Problem(
            0, 1, arrayOf(intDomainFromSurvivors(longArrayOf(-magnitude, 0L, magnitude))),
            arrayOf<Factor>(Linear(intArrayOf(5), intArrayOf(0), LinearOp.LE, 0)),
        )
        val visited = HashSet<Long>()

        FactorPropagationOracle.assertGac(problem) { sample ->
            val value = sample.ints.single()
            visited.add(value)
            value <= 0L
        }

        assertEquals(setOf(-magnitude, 0L, magnitude), visited)
    }
}
