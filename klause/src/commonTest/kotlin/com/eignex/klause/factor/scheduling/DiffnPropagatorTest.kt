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
    fun `sweep clears a width-two box past a two-rectangle wall`() {
        // A full-height wall at column 1 is built from two fixed unit squares: A at (1,0) and B at
        // (1,1), covering both rows. A width-2 height-1 box (x∈[0,3], y∈[0,1]) cannot sit at x=0 or
        // x=1 in *either* row, so its origin is forced to x≥2. No single pair forces this — the box
        // can dodge A by taking row 1 and B by taking row 0 — so only the multi-rectangle sweep sees
        // it. Layout: box x=0 y=1; A x=2 y=3; B x=4 y=5.
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
        assertEquals(2, state.intDomains[0].min, "the width-2 box must clear the column-1 wall")
    }

    @Test
    fun `pairwise conflict reason is a sound nogood citing only the overlapping pair`() {
        // Globally satisfiable: two 2×2 boxes fit in a 4-wide board at (0,0) and (2,2). A decision
        // pins A and B both to (0,0), forcing overlap. The sharp reason must cite only A's and B's
        // origin vars (0,3,1,4), never the idle third box C (2,5) — and must be entailed by diffn.
        // Layout: A x=0 y=3, B x=1 y=4, C x=2 y=5.
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
        check(state.tightenIntMin(2, 2)) // C tightened (and idle) so a coarse reason would cite it
        check(state.tightenIntMin(5, 2))
        state.currentFactor = 0
        assertFalse(problem.propagators[0].propagate(state, 0))
        val reason = problem.propagators[0].conflictReason(state, 0)!!
        val citedVars = reason.map { state.atoms.intVar[Lit.variable(it) - problem.numBoolVars] }.toSet()
        assertTrue(citedVars.all { it in setOf(0, 3, 1, 4) }, "reason must cite only the A/B pair, got $citedVars")
        assertTrue(2 !in citedVars && 5 !in citedVars, "idle box C must not appear in the sharp reason")
        ConflictReasonOracle.assertEntailed(problem, state, 0, "diffn-pair")
    }

    @Test
    fun `propagation shaves the separating axis when overlap is forced on the other`() {
        // Two 3-wide rectangles whose x-ranges force them to overlap on x (each spans 3 units
        // from a start in [0,1], so neither can clear the other horizontally). Rect 0's y is
        // pinned to 0 with height 2 → occupies y in [0,2). Rect 1 (height 2) therefore cannot
        // sit at-or-below rect 0, so its y must start at ≥ 2.
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
                IntDomain(0, 1), // rect 0 x
                IntDomain(0, 1), // rect 1 x
                IntDomain(0, 0), // rect 0 y pinned to 0
                IntDomain(0, 5), // rect 1 y free
            ),
            factors = arrayOf<Factor>(factor),
        )
        val r = problem.propagate()
        val implied = assertIs<PropagationResult.Implied>(r)
        assertEquals(2, implied.intMinOrNullCompat(3), "rect 1 y.min must be pushed to 2 (above rect 0)")
    }

    @Test
    fun `diffn deductions are implied by their reasons under carved holes`() {
        val rng = Random(0xD1FF)
        repeat(300) { iter ->
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
    fun `a sweep move cites only the compulsory parts in its way`() {
        // Rectangle 0 sits at the origin and rectangle 1 cannot leave its rows, so 1 moves right of 0; rectangle
        // 2, out at (6, 6), plays no part. Origins: xs = 0, 2, 4 and ys = 1, 3, 5, every side 2.
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
