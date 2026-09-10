package com.eignex.klause.bench.runner

import com.eignex.klause.backtrack.BacktrackPresets
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.bench.catalog.Catalog
import com.eignex.klause.bench.catalog.Expected
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.util.Cancellation
import kotlin.random.Random
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertTrue

@Ignore("integration coverage; run explicitly on a host with minizinc installed")
class MiniZincRunnerTest {

    @Test
    fun `klause solves the minizinc smoke set`() {
        assertTrue(minizincOnPath(), "minizinc must be installed to run this integration test")
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
        val st = LocalSearchState(p.bake(), Random(0))
        for (b in 0 until p.numBoolVars) st.assignment.setBool(b, r.assignment.bools[b])
        for (i in 0 until p.numIntVars) st.assignment.setInt(i, r.assignment.ints[i])
        st.recompute()
        return st.cost == 0L
    }

    private fun minizincOnPath(): Boolean = runCatching {
        ProcessBuilder("minizinc", "--version").redirectErrorStream(true).start().waitFor() == 0
    }.getOrDefault(false)
}
