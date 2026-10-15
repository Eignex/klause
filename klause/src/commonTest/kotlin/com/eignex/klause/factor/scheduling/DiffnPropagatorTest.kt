package com.eignex.klause.factor.scheduling

import com.eignex.klause.factor.ConflictReasonOracle
import com.eignex.klause.factor.PropagationReasonOracle
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.factorAt
import com.eignex.klause.propagation.propagate
import com.eignex.klause.propagation.reasonOf
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DiffnPropagatorTest {

    @Test
    fun `sweep should clear a width-two box past a two-rectangle wall`() {
        // The box dodges each wall square alone by switching rows, so no single pair forces x >= 2.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 6,
            intDomains = arrayOf(
                IntDomain(0, 3),
                IntDomain(0, 1),
                IntDomain(1, 1),
                IntDomain(0, 0),
                IntDomain(1, 1),
                IntDomain(1, 1),
            ),
            factors = arrayOf<Factor>(
                Diffn(
                    xs = intArrayOf(0, 2, 4),
                    ys = intArrayOf(1, 3, 5),
                    widths = longArrayOf(2, 1, 1),
                    heights = longArrayOf(1, 1, 1),
                ),
            ),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        state.currentFactor = 0
        assertTrue(problem.propagators[0].propagate(state, 0))
        assertEquals(2, state.intDomains[0].min)
    }

    @Test
    fun `pairwise conflict reason should be a sound nogood citing only the overlapping pair`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 6,
            intDomains = Array(6) { IntDomain(0, 2) },
            factors = arrayOf<Factor>(
                Diffn(
                    xs = intArrayOf(0, 1, 2),
                    ys = intArrayOf(3, 4, 5),
                    widths = longArrayOf(2, 2, 2),
                    heights = longArrayOf(2, 2, 2),
                ),
            ),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        state.currentLevel = 1
        for (v in intArrayOf(0, 3, 1, 4)) {
            check(state.tightenIntMax(v, 0))
            check(state.tightenIntMin(v, 0))
        }
        check(state.tightenIntMin(2, 2))
        check(state.tightenIntMin(5, 2))
        state.currentFactor = 0
        assertFalse(problem.propagators[0].propagate(state, 0))
        val reason = problem.propagators[0].conflictReason(state, 0)!!
        val citedVars = reason.map { state.atoms.intVar[Lit.variable(it) - problem.numBoolVars] }.toSet()
        assertTrue(citedVars.all { it in setOf(0, 3, 1, 4) }, "$citedVars")
        ConflictReasonOracle.assertEntailed(problem, state, 0, "diffn-pair")
    }

    @Test
    fun `propagation should shave the separating axis when overlap is forced on the other`() {
        val factor = Diffn(
            xs = intArrayOf(0, 1),
            ys = intArrayOf(2, 3),
            widths = longArrayOf(3, 3),
            heights = longArrayOf(2, 2),
        )
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(
                IntDomain(0, 1),
                IntDomain(0, 1),
                IntDomain(0, 0),
                IntDomain(0, 5),
            ),
            factors = arrayOf<Factor>(factor),
        )
        val r = problem.propagate()
        val implied = assertIs<PropagationResult.Implied>(r)
        assertEquals(2, implied.intMinOrNullCompat(3))
    }

    @Test
    fun `diffn deductions should be implied by their reasons under carved holes`() {
        val rng = Random(0xD1FF)
        repeat(100) { iter ->
            val rects = 3
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = 2 * rects,
                intDomains = Array(2 * rects) { IntDomain(0, 3) },
                factors = arrayOf<Factor>(
                    Diffn(
                        xs = IntArray(rects) { 2 * it },
                        ys = IntArray(rects) { 2 * it + 1 },
                        widths = LongArray(rects) { 1L + rng.nextInt(2) },
                        heights = LongArray(rects) { 1L + rng.nextInt(2) },
                    ),
                ),
            )
            PropagationReasonOracle.assertReasonsImply(problem, "diffn#$iter") { state ->
                (0 until 6).all {
                    val v = rng.nextInt(2 * rects)
                    when (rng.nextInt(3)) {
                        0 -> state.excludeIntValue(v, rng.nextInt(4).toLong())
                        1 -> state.tightenIntMin(v, 1L + rng.nextInt(2))
                        else -> state.tightenIntMax(v, rng.nextInt(2).toLong())
                    }
                }
            }
        }
    }

    @Test
    fun `a sweep move should cite only the compulsory parts in its way`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 6,
            intDomains = Array(6) { IntDomain(0, 9) },
            factors = arrayOf<Factor>(
                Diffn(
                    xs = intArrayOf(0, 2, 4),
                    ys = intArrayOf(1, 3, 5),
                    widths = longArrayOf(2, 2, 2),
                    heights = longArrayOf(2, 2, 2),
                ),
            ),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        state.currentLevel = 1
        check(state.tightenIntMax(0, 0) && state.tightenIntMax(1, 0) && state.tightenIntMax(3, 1))
        check(state.tightenIntMin(4, 6) && state.tightenIntMin(5, 6))
        state.currentFactor = 0

        check(state.factorAt(0).propagate(state, 0))

        assertEquals(2, state.intDomains[2].min)
        val cited = state.reasonOf(state.intMinAntecedents[2])!!.map { state.atoms.intVar[Lit.variable(it)] }.toSet()
        assertEquals(setOf(0, 1, 3), cited)
    }
}
