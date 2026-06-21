package com.eignex.klause.solver.factor.scheduling

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.factor.scheduling.Diffn
import com.eignex.klause.solver.propagation.PropagationResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DiffnPropagatorTest {

    @Test
    fun `non-overlapping packing exists`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = Array(4) { IntDomain(0, 1) },
            factors = arrayOf<Factor>(
                Diffn(
                    xs = intArrayOf(0, 2),
                    ys = intArrayOf(1, 3),
                    widths = intArrayOf(2, 2),
                    heights = intArrayOf(2, 2),
                ),
            ),
        )
        val r = BacktrackSolver(problem).solve(BacktrackParams(randomSeed = 0L))
        // Two 2x2 rectangles with positions in [0,1] always overlap (every placement pair
        // shares at least the [1,2)x[1,2) cell), so there is no non-overlapping packing.
        assertIs<SolveResult.Unsat>(r)
    }

    @Test
    fun `two unit squares pinned non-overlapping is Sat`() {
        // Rect 1 at (0,0) 1x1. Rect 2 at (1,0) 1x1. Disjoint.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(IntDomain(0, 0), IntDomain(0, 0), IntDomain(1, 1), IntDomain(0, 0)),
            factors = arrayOf<Factor>(
                Diffn(
                    xs = intArrayOf(0, 2),
                    ys = intArrayOf(1, 3),
                    widths = intArrayOf(1, 1),
                    heights = intArrayOf(1, 1),
                ),
            ),
        )
        val r = BacktrackSolver(problem).solve(BacktrackParams(randomSeed = 0L))
        assertIs<SolveResult.Sat>(r)
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
            widths = intArrayOf(3, 3),
            heights = intArrayOf(2, 2),
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
        val r = problem.baked
        val implied = assertIs<PropagationResult.Implied>(r)
        assertEquals(2, implied.intMinOrNullCompat(3), "rect 1 y.min must be pushed to 2 (above rect 0)")
    }

    @Test
    fun `overlapping pinned rectangles is Unsat`() {
        // Both rectangles pinned at (0, 0), 2x2 each → identical, overlap.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = Array(4) { IntDomain(0, 0) },
            factors = arrayOf<Factor>(
                Diffn(
                    xs = intArrayOf(0, 2),
                    ys = intArrayOf(1, 3),
                    widths = intArrayOf(2, 2),
                    heights = intArrayOf(2, 2),
                ),
            ),
        )
        assertIs<SolveResult.Unsat>(BacktrackSolver(problem).solve(BacktrackParams(randomSeed = 0L)))
    }
}
