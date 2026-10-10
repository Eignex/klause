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
import com.eignex.klause.propagation.propagate
import com.eignex.klause.propagation.reasonOf
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GlobalCardinalityPropagatorTest {

    @Test
    fun `global cardinality deductions are implied by their reasons under carved holes`() {
        val rng = Random(0x6CC0)
        repeat(300) { iter ->
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

    /**
     * Flow-deficiency conflicts must cite the count vars whose search-derived lower bounds
     * form the unmet demand. Two values each demand one taker (count mins raised at search
     * levels) while only one var can still serve either — per-value counts stay locally
     * consistent, so only the Régin flow detects the deficit. The demand-side cover nodes
     * are not residual-reachable from the cut, and a reach-filtered citation drops exactly
     * the count premises — the learned clause then claims the var bounds alone are
     * contradictory and prunes feasible assignments (surfaced as a false UNSAT on
     * oocsp_racks).
     */
    @Test
    fun `flow deficiency conflict cites the count var demand bounds`() {
        // ints: xs = 0,1 over 0..2; countVars 2 (value 1) and 3 (value 2) over 0..2.
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

        assertFalse(problem.propagators[0].propagate(state, 0), "demand 2 vs supply 1 must conflict")
        val reason = problem.propagators[0].conflictReason(state, 0)
        assertNotNull(reason, "flow-deficiency conflict must carry a reason")
        val citedInts = buildSet {
            for (lit in reason) {
                val v = Lit.variable(lit)
                if (v >= problem.numBoolVars) add(state.atoms.intVar[v - problem.numBoolVars])
            }
        }
        assertTrue(2 in citedInts && 3 in citedInts, "reason must cite both count vars; cited $citedInts")
    }

    @Test
    fun `a flow prune cites only the variables of the hall set it leaves`() {
        // Each value at most once: x0 and x1 take {0, 1} between them, so x2 leaves both; x4's carved hole at 3
        // plays no part. x2's lower bound climbs past 0 and then 1, so its final move also cites the bound it left.
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
    fun `global cardinality deductions with count vars are implied by their reasons under carved holes`() {
        val rng = Random(0x6CC1)
        repeat(300) { iter ->
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
