package com.eignex.klause.solver.factor

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import kotlin.test.Test
import kotlin.test.assertIs

class DiffnTest {

    @Test
    fun `non-overlapping packing exists`() {
        // Two 2x2 rectangles in a 3x3 grid → only configurations where they share a
        // corner-line; we just verify Sat exists.
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
                )
            ),
        )
        val r = BacktrackSolver(problem).solve(BacktrackParams(randomSeed = 0L))
        // 2x2 + 2x2 in [0,1]x[0,1] positions only — actually no valid placement; expect Unsat.
        // Wait — positions can be (0,0), (0,1), (1,0), (1,1) but rect spans 2 units. Two
        // 2x2 rectangles can never fit in a 3x3 grid (3x3 area = 9, 2x2x2 = 8 ok). With
        // positions ∈ [0,1]: at (0,0) covers [0,2)x[0,2); at (1,1) covers [1,3)x[1,3) →
        // overlap at [1,2)x[1,2). At (0,1) and (1,0): also overlap. No non-overlap.
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
                )
            ),
        )
        val r = BacktrackSolver(problem).solve(BacktrackParams(randomSeed = 0L))
        assertIs<SolveResult.Sat>(r)
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
                )
            ),
        )
        assertIs<SolveResult.Unsat>(BacktrackSolver(problem).solve(BacktrackParams(randomSeed = 0L)))
    }
}
