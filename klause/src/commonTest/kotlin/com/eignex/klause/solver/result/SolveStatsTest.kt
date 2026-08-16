package com.eignex.klause.solver.result

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.selector.IndomainMax
import com.eignex.klause.backtrack.selector.IndomainMin
import com.eignex.klause.backtrack.selector.InputOrder
import com.eignex.klause.compile.compile
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.model.IntCmpOp
import com.eignex.klause.model.IntCompare
import com.eignex.klause.model.IntRef
import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.schema.allDifferent
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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

        val rows by constraint { allDifferent(q0, q1, q2, q3, q4, q5) }
    }

    @Test
    fun `backtrack populates stats with non-zero nodes and depth`() {
        val schema = Queens6()
        val compiled = schema.compile()
        val result = BacktrackSolver(compiled.problem.bake()).solve(BacktrackParams())
        assertTrue(result is SolveResult.Sat, "expected SAT for 6-row alldiff")
        val stats = result.stats
        assertEquals("backtrack", stats.run.backend)
        assertTrue(
            stats.search.nodes.sum > 0.0,
            "backtrack should visit at least one decision node; nodes=${stats.search.nodes.sum}",
        )
        assertTrue(stats.search.peakDepth.max >= 1.0, "peak depth should be ≥1; got ${stats.search.peakDepth.max}")
        assertTrue(stats.search.depthMean.totalWeights >= 1.0, "mean depth should have received ≥1 update")
    }

    @Test
    fun `local-search populates backend tag and wall time`() {
        val schema = Queens6()
        val compiled = schema.compile()
        val result = LocalSearchSolver(
            compiled.problem.bake(),
        ).solve(LocalSearchParams(maxFlips = 5_000, randomSeed = 7))
        // Either SAT or Unknown — both should carry the ls backend tag.
        val stats = result.stats
        assertEquals("ls", stats.run.backend)
        assertTrue(stats.run.wallMs >= 0L, "wallMs should be non-negative")
    }

    /**
     * Pigeonhole over weak binary `!=` (decomposed, *not* the global alldifferent): `n` int
     * vars in `0..n-2` with all pairs constrained unequal. Weak propagation can't prove this at
     * the root, so the engine must branch and hit propagation conflicts — exactly the situation
     * where failures must be counted.
     */
    private fun pigeonhole(n: Int): Problem {
        val factors = buildList {
            for (i in 0 until n) {
                for (j in i + 1 until n) {
                    add(Linear(intArrayOf(1, -1), intArrayOf(i, j), LinearOp.NE, 0))
                }
            }
        }
        return Problem(
            numBoolVars = 0,
            numIntVars = n,
            intDomains = Array(n) { IntDomain(0, (n - 2).toLong()) },
            factors = factors.toTypedArray(),
        )
    }

    @Test
    fun `backtrack counts failures while proving UNSAT`() {
        // 4 pigeons, 3 holes: infeasible, but only the weak binary != constraints — so the
        // proof requires branching and backtracking on conflicts, not a root wipeout.
        val result = BacktrackSolver(pigeonhole(4).bake()).solve(BacktrackParams(randomSeed = 0L))
        assertIs<SolveResult.Unsat>(result, "4-into-3 pigeonhole is infeasible")
        assertTrue(
            result.stats.search.fails.sum > 0.0,
            "an UNSAT proof that branches must record failures; got fails=${result.stats.search.fails.sum}",
        )
    }

    @Test
    fun `branch-and-bound counts failures while proving optimal`() {
        // Three vars in 0..3 with pairwise sum >= 4, minimising x+y+z. IndomainMin tries the
        // smallest values first, which violate the >= sums and conflict before the descent
        // climbs to a feasible leaf — so the optimality proof genuinely backtracks on conflicts
        // (the optimum is x=y=z=2, total 6). A search like this cannot honestly report zero
        // failures (#509).
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = Array(3) { IntDomain(0, 3) },
            factors = arrayOf(
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 4),
                Linear(intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.GE, 4),
                Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.GE, 4),
            ),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(1L, 1L, 1L))
        val result = BacktrackSolver(problem.bake()).minimize(
            obj,
            BacktrackParams(randomSeed = 0L, variableSelector = InputOrder, valueSelector = IndomainMax),
        )
        val optimal = assertIs<MinimizeResult.Optimal>(result, "expected a proven optimum")
        assertEquals(6.0, optimal.objective, "minimum of x+y+z under the pairwise sum bounds is 6")
        assertTrue(
            optimal.stats.search.fails.sum > 0.0,
            "a branch-and-bound optimality proof that searches must record failures; " +
                "got fails=${optimal.stats.search.fails.sum}",
        )
    }

    @Test
    fun `branch-and-bound counts bound-pruned nodes as failures`() {
        // Two unconstrained vars, minimising x + y. With no constraints there is never a
        // propagation conflict, and a two-variable objective has no single objective var to pin
        // as a domain bound — so the optimum (0) is found immediately and every remaining subtree
        // dies purely by the objective lower bound (linearLowerBound >= incumbent). Those
        // bound-pruned nodes are failed nodes too and must be counted (#509), matching solvers
        // that post the objective bound as a constraint.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3)),
            factors = emptyArray(),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(1L, 1L))
        val result = BacktrackSolver(problem.bake()).minimize(
            obj,
            BacktrackParams(randomSeed = 0L, variableSelector = InputOrder, valueSelector = IndomainMin),
        )
        val optimal = assertIs<MinimizeResult.Optimal>(result, "expected a proven optimum")
        assertEquals(0.0, optimal.objective, "minimum of x + y is 0")
        assertTrue(
            optimal.stats.search.fails.sum > 0.0,
            "a proof that kills subtrees by the objective bound must record those as failures; " +
                "got fails=${optimal.stats.search.fails.sum}",
        )
    }

    @Test
    fun `default SolveStats EMPTY is zero everywhere`() {
        val s = SolveStats.EMPTY
        assertEquals("", s.run.backend)
        assertEquals(0.0, s.search.nodes.sum)
        assertEquals(0L, s.run.wallMs)
        assertEquals(false, s.run.timedOut)
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
        val r = BacktrackSolver(compiled.problem.bake()).solve(BacktrackParams())
        assertTrue(r is SolveResult.Unsat, "expected UNSAT, got $r")
        assertEquals("backtrack", r.stats.run.backend)
        assertEquals(false, r.stats.run.timedOut)
    }
}
