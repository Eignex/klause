package com.eignex.klause.solver.backtrack

import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.Cardinality
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EnumerationModeTest {

    private fun exactlyOneOver(n: Int): Problem {
        val factor = Cardinality.exactlyOne((0 until n).map { Lit.make(it, true) }.toIntArray())
        return Problem(n, 0, emptyArray(), listOf(factor))
    }

    @Test
    fun `Dfs enumerates all distinct samples on small space`() {
        val problem = exactlyOneOver(4)
        val models = BacktrackSolver(problem)
            .enumerate(BacktrackParams(
                enumerationMode = EnumerationMode.Dfs,
                minHammingDistance = 0,
            ))
            .toList()
        assertEquals(4, models.size)
        assertEquals(4, models.toSet().size)
    }

    @Test
    fun `RandomRestart yields distinct samples and terminates on exhaustion`() {
        val problem = exactlyOneOver(4)
        val models = BacktrackSolver(problem)
            .enumerate(BacktrackParams(
                enumerationMode = EnumerationMode.RandomRestart,
                randomSeed = 42L,
                minHammingDistance = 0,
            ))
            .toList()
        // 4 distinct models exist. RandomRestart should find them all (since each restart
        // is an independent SAT search) and then bail when consecutive duplicates pile up.
        assertEquals(4, models.size)
        assertEquals(4, models.toSet().size)
    }

    @Test
    fun `RandomRestart yields no duplicates even with many restarts`() {
        val problem = exactlyOneOver(6)
        val models = BacktrackSolver(problem)
            .enumerate(BacktrackParams(
                enumerationMode = EnumerationMode.RandomRestart,
                randomSeed = 7L,
                minHammingDistance = 0,
            ))
            .take(100)
            .toList()
        // The table-forbids post-filter ensures no duplicates.
        assertEquals(models.size, models.toSet().size, "RandomRestart yielded a duplicate: $models")
        assertTrue(models.size <= 6, "should bail out after exhausting the 6-model space")
    }
}
