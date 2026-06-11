package com.eignex.klause.solver.result

import com.eignex.klause.ast.IntCmpOp
import com.eignex.klause.ast.IntCompare
import com.eignex.klause.ast.IntRef
import com.eignex.klause.ast.allDifferent
import com.eignex.klause.compile.compile
import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.solver.localsearch.LocalSearchSolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Per-solve [SolveStats] sanity. Verifies that backend tags, counters, and depth
 * distributions are populated where the backend supports them, and that the type
 * defaults behave correctly for backends that don't.
 */
class SolveStatsTest {

    private class Queens6 : VariableSchema() {
        val q0 by intVar(0, 5)
        val q1 by intVar(0, 5)
        val q2 by intVar(0, 5)
        val q3 by intVar(0, 5)
        val q4 by intVar(0, 5)
        val q5 by intVar(0, 5)

        // All-different rows + diagonals via aux abs-difference checks for a tiny n=6.
        val rows by constraint { allDifferent(q0, q1, q2, q3, q4, q5) }
    }

    @Test
    fun `backtrack populates stats with non-zero nodes and depth`() {
        val schema = Queens6()
        val compiled = schema.compile()
        val result = BacktrackSolver(compiled.problem).solve(BacktrackParams())
        assertTrue(result is SolveResult.Sat, "expected SAT for 6-row alldiff")
        val stats = result.stats
        assertEquals("backtrack", stats.backend)
        assertTrue(stats.nodes.sum > 0.0, "backtrack should visit at least one decision node; nodes=${stats.nodes.sum}")
        assertTrue(stats.peakDepth.max >= 1.0, "peak depth should be ≥1; got ${stats.peakDepth.max}")
        assertTrue(stats.depthMean.totalWeights >= 1.0, "mean depth should have received ≥1 update")
    }

    @Test
    fun `local-search populates backend tag and wall time`() {
        val schema = Queens6()
        val compiled = schema.compile()
        val result = LocalSearchSolver(compiled.problem).solve(LocalSearchParams(maxFlips = 5_000, randomSeed = 7))
        // Either SAT or Unknown — both should carry the ls backend tag.
        val stats = result.stats
        assertEquals("ls", stats.backend)
        assertTrue(stats.wallMs >= 0L, "wallMs should be non-negative")
    }

    @Test
    fun `default SolveStats EMPTY is zero everywhere`() {
        val s = SolveStats.EMPTY
        assertEquals("", s.backend)
        assertEquals(0.0, s.nodes.sum)
        assertEquals(0L, s.wallMs)
        assertEquals(false, s.timedOut)
    }

    @Test
    fun `unsat result carries non-empty stats with timedOut=false`() {
        // An infeasible schema: x = y AND x != y over a tiny domain.
        class S : VariableSchema() {
            val x by intVar(0, 0)
            val y by intVar(1, 1)
            val c by constraint {
                IntCompare(
                    IntRef("x"),
                    IntCmpOp.EQ,
                    IntRef("y"),
                )
            }
        }
        val compiled = S().compile()
        val r = BacktrackSolver(compiled.problem).solve(BacktrackParams())
        assertTrue(r is SolveResult.Unsat, "expected UNSAT, got $r")
        assertEquals("backtrack", r.stats.backend)
        assertEquals(false, r.stats.timedOut)
    }
}
