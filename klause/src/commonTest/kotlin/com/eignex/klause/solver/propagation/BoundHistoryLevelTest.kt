package com.eignex.klause.solver.propagation

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Direct coverage for [PropagationState.minLevelForGe] / [PropagationState.maxLevelForLe] —
 * the binary-searched monotone-history lookups (#97) that the conflict analyzer relies on
 * for accurate atom levels (#76). Bounds are tightened at known decision levels and the
 * "level the bound first reached k" answers are asserted across every threshold.
 */
class BoundHistoryLevelTest {

    private fun state(): PropagationState {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 20)),
            factors = arrayOf<Factor>(),
        )
        return PropagationState(problem, Assumptions.None).also { it.undoLogging = true }
    }

    @Test
    fun `minLevelForGe returns the level the min first reached the threshold`() {
        val s = state()
        // min rises 0 → 3 (lvl 1) → 7 (lvl 2) → 12 (lvl 4)
        s.currentLevel = 1
        s.tightenIntMin(0, 3)
        s.currentLevel = 2
        s.tightenIntMin(0, 7)
        s.currentLevel = 4
        s.tightenIntMin(0, 12)

        assertEquals(0, s.minLevelForGe(0, 0), "≤ root min → global fact")
        assertEquals(1, s.minLevelForGe(0, 1), "first min ≥ 1 reached at level 1 (min went 0→3)")
        assertEquals(1, s.minLevelForGe(0, 3), "exact step boundary 3 → level 1")
        assertEquals(2, s.minLevelForGe(0, 4), "between 3 and 7 → next step, level 2")
        assertEquals(2, s.minLevelForGe(0, 7), "exact boundary 7 → level 2")
        assertEquals(4, s.minLevelForGe(0, 8), "between 7 and 12 → level 4")
        assertEquals(4, s.minLevelForGe(0, 12), "exact boundary 12 → level 4")
        assertEquals(maxOf(s.intLevel[0], 0), s.minLevelForGe(0, 13), "beyond the deepest tighten → fallback")
    }

    @Test
    fun `maxLevelForLe returns the level the max first reached the threshold`() {
        val s = state()
        // max falls 20 → 15 (lvl 1) → 9 (lvl 3) → 5 (lvl 4)
        s.currentLevel = 1
        s.tightenIntMax(0, 15)
        s.currentLevel = 3
        s.tightenIntMax(0, 9)
        s.currentLevel = 4
        s.tightenIntMax(0, 5)

        assertEquals(0, s.maxLevelForLe(0, 20), "≥ root max → global fact")
        assertEquals(1, s.maxLevelForLe(0, 19), "first max ≤ 19 reached at level 1 (max went 20→15)")
        assertEquals(1, s.maxLevelForLe(0, 15), "exact boundary 15 → level 1")
        assertEquals(3, s.maxLevelForLe(0, 14), "between 15 and 9 → level 3")
        assertEquals(3, s.maxLevelForLe(0, 9), "exact boundary 9 → level 3")
        assertEquals(4, s.maxLevelForLe(0, 8), "between 9 and 5 → level 4")
        assertEquals(4, s.maxLevelForLe(0, 5), "exact boundary 5 → level 4")
        assertEquals(maxOf(s.intLevel[0], 0), s.maxLevelForLe(0, 4), "below the deepest tighten → fallback")
    }

    @Test
    fun `level lookups fall back to intLevel when no history is present`() {
        val s = state()
        // No tighten history; thresholds inside the root domain bounds use the fallback.
        assertEquals(0, s.minLevelForGe(0, 5), "no history, intLevel is -1 → fallback maxOf(-1,0) = 0")
        assertEquals(0, s.maxLevelForLe(0, 5))
    }
}
