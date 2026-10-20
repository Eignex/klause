package com.eignex.klause.factor.global

import com.eignex.klause.factor.PropagationReasonOracle
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.AtomKind
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.factorAt
import com.eignex.klause.propagation.mark
import com.eignex.klause.propagation.propagate
import com.eignex.klause.propagation.reasonOf
import com.eignex.klause.propagation.undoTo
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GlobalCardinalityPropagatorTest {

    @Test
    fun `count bounds should reflect sibling domains after undo`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = Array(4) { if (it < 3) IntDomain(0, 2) else IntDomain(0, 3) },
            factors = arrayOf<Factor>(
                GlobalCardinality(xs = intArrayOf(0, 1, 2), cover = longArrayOf(0), countVars = intArrayOf(3)),
            ),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        assertNull(state.runToFixpoint(allFactors = true))
        val root = state.mark()
        state.currentLevel = 1
        check(state.tightenIntMax(0, 0) && state.excludeIntValue(1, 0))
        assertNull(state.runToFixpoint(allFactors = true))
        state.undoTo(root)

        state.currentLevel = 1
        check(state.tightenIntMin(0, 1) && state.tightenIntMin(2, 1))
        assertNull(state.runToFixpoint(allFactors = true))

        assertEquals(0L, state.intDomains[3].min)
        assertEquals(1L, state.intDomains[3].max)
    }

    @Test
    fun `global cardinality deductions should be implied by their reasons under carved holes`() {
        val rng = Random(0x6CC0)
        repeat(100) { iter ->
            val n = 5
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = n,
                intDomains = Array(n) { IntDomain(0, 3) },
                factors = arrayOf<Factor>(
                    GlobalCardinality(
                        xs = IntArray(n) { it },
                        cover = longArrayOf(0, 1, 2, 3),
                        countLow = intArrayOf(0, 1, 0, 1),
                        countHigh = intArrayOf(2, 2, 2, 2),
                        closed = rng.nextBoolean(),
                    ),
                ),
            )
            PropagationReasonOracle.assertReasonsImply(problem, "gcc#$iter") { state ->
                (0 until 6).all { state.excludeIntValue(rng.nextInt(n), rng.nextInt(4).toLong()) }
            }
        }
    }

    // The count bounds are not residual-reachable from the flow cut, yet the conflict depends on them.
    @Test
    fun `flow deficiency conflict should cite the count var demand bounds`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2)),
            factors = arrayOf<Factor>(
                GlobalCardinality(xs = intArrayOf(0, 1), cover = longArrayOf(1, 2), countVars = intArrayOf(2, 3)),
            ),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        state.currentLevel = 1
        check(state.tightenIntMin(2, 1)) { "count-1 demand failed" }
        state.currentLevel = 2
        check(state.tightenIntMin(3, 1)) { "count-2 demand failed" }
        state.currentLevel = 3
        check(state.tightenIntMax(1, 0)) { "x1 restriction failed" }

        assertFalse(problem.propagators[0].propagate(state, 0))
        val reason = problem.propagators[0].conflictReason(state, 0)
        assertNotNull(reason)
        val citedInts = buildSet {
            for (lit in reason) {
                val v = Lit.variable(lit)
                if (v >= problem.numBoolVars) add(state.atoms.intVar[v - problem.numBoolVars])
            }
        }
        assertTrue(2 in citedInts && 3 in citedInts, "$citedInts")
    }

    @Test
    fun `a flow prune should cite only the variables of the hall set it leaves`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 5,
            intDomains = Array(5) { IntDomain(0, 4) },
            factors = arrayOf<Factor>(
                GlobalCardinality(
                    xs = IntArray(5) { it },
                    cover = longArrayOf(0, 1, 2, 3, 4),
                    countLow = IntArray(5) { 0 },
                    countHigh = IntArray(5) { 1 },
                ),
            ),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        state.currentLevel = 1
        check(state.tightenIntMax(0, 1) && state.tightenIntMax(1, 1) && state.excludeIntValue(4, 3))
        state.currentFactor = 0

        check(state.factorAt(0).propagate(state, 0))

        val cited = state.reasonOf(state.intMinAntecedents[2])!!.map { lit ->
            val atom = Lit.variable(lit) - problem.numBoolVars
            Triple(state.atoms.intVar[atom], state.atoms.kind[atom], state.atoms.threshold[atom])
        }
        assertEquals(
            setOf(Triple(0, AtomKind.LE, 1L), Triple(1, AtomKind.LE, 1L), Triple(2, AtomKind.GE, 1L)),
            cited.toSet(),
        )
    }

    @Test
    fun `global cardinality deductions with count vars should be implied by their reasons under carved holes`() {
        val rng = Random(0x6CC1)
        repeat(100) { iter ->
            val n = 4
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = n + 2,
                intDomains = Array(n + 2) { if (it < n) IntDomain(0, 2) else IntDomain(0, n.toLong()) },
                factors = arrayOf<Factor>(
                    GlobalCardinality(
                        xs = IntArray(n) { it },
                        cover = longArrayOf(0, 1),
                        countVars = intArrayOf(n, n + 1),
                        closed = rng.nextBoolean(),
                    ),
                ),
            )
            PropagationReasonOracle.assertReasonsImply(problem, "gcc-counts#$iter") { state ->
                (0 until 5).all {
                    val v = rng.nextInt(n + 2)
                    when (rng.nextInt(3)) {
                        0 -> state.excludeIntValue(v, rng.nextInt(3).toLong())
                        1 -> state.tightenIntMin(v, 1L + rng.nextInt(2))
                        else -> state.tightenIntMax(v, 1L + rng.nextInt(2))
                    }
                }
            }
        }
    }
}
