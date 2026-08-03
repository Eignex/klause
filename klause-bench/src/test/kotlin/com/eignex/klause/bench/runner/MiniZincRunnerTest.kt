package com.eignex.klause.bench.runner

import com.eignex.klause.backtrack.BacktrackPresets
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.bench.catalog.Catalog
import com.eignex.klause.bench.catalog.Expected
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Exercises the MiniZinc pipeline end-to-end: compile `.mzn`→`.fzn` via the `minizinc` CLI, parse
 * in-process, and solve the resulting klause [Problem] with klause's backtracker. Asserts the
 * satisfiable smoke models are reached and that the returned assignment validates under klause's
 * own constraint checker — i.e. the FlatZinc→Problem translation is faithful and solvable.
 *
 * Skips silently when `minizinc` isn't on PATH so bare CI images stay green.
 */
class MiniZincRunnerTest {

    @Test
    fun `klause solves the minizinc smoke set`() {
        if (!minizincOnPath()) {
            println("[mzn-runner] minizinc not on PATH — skipping")
            return
        }
        val runner = MiniZincRunner()
        for (ref in Catalog.suite("mzn-smoke").problems) {
            val resolved = runner.resolve(ref)
            assertTrue(resolved.problem.numIntVars + resolved.problem.numBoolVars > 0, "${ref.name}: empty problem")
            if (resolved.objective != null) continue // optimization measured by the solve metric, not here
            val deadline = System.currentTimeMillis() + 30_000
            val params = BacktrackPresets.conflictDriven(
                randomSeed = 0L,
                cancellation = Cancellation { System.currentTimeMillis() > deadline },
            )
            val r = BacktrackSolver(resolved.problem.bake()).solve(params)
            if (ref.expected == Expected.Sat) {
                assertTrue(r is SolveResult.Sat, "${ref.name}: klause failed to find expected solution ($r)")
                assertTrue(satisfies(resolved.problem, r), "${ref.name}: solution violates klause constraints")
            }
        }
    }

    private fun satisfies(p: Problem, r: SolveResult.Sat): Boolean {
        val st = LocalSearchState(p, Random(0))
        for (b in 0 until p.numBoolVars) st.assignment.setBool(b, r.assignment.bools[b])
        for (i in 0 until p.numIntVars) st.assignment.setInt(i, r.assignment.ints[i])
        st.recompute()
        return st.cost == 0L
    }

    private fun minizincOnPath(): Boolean = runCatching {
        ProcessBuilder("minizinc", "--version").redirectErrorStream(true).start().waitFor() == 0
    }.getOrDefault(false)
}
