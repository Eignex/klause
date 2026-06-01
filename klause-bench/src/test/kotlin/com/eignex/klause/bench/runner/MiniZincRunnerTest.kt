package com.eignex.klause.bench.runner

import com.eignex.klause.bench.catalog.Catalog
import com.eignex.klause.bench.catalog.Expected
import com.eignex.klause.choco.ChocoParams
import com.eignex.klause.choco.ChocoSolver
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.localsearch.LocalSearchState
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Exercises the MiniZinc pipeline end-to-end: compile `.mzn`→`.fzn` via the `minizinc` CLI,
 * parse in-process, and solve with the Choco reference. Asserts the reference reaches each
 * model's recorded feasibility and that every solution it returns validates under klause's
 * own constraint checker — i.e. the Choco translation is faithful. Independent of klause's
 * own solver verdicts (which parity compares separately).
 *
 * Skips silently when `minizinc` isn't on PATH so bare CI images stay green.
 */
class MiniZincRunnerTest {

    @Test
    fun `choco reference solves the minizinc smoke set`() {
        if (!minizincOnPath()) { println("[mzn-runner] minizinc not on PATH — skipping"); return }
        val runner = MiniZincRunner()
        for (ref in Catalog.suite("mzn-smoke").problems) {
            val resolved = runner.resolve(ref)
            assertTrue(resolved.problem.numIntVars + resolved.problem.numBoolVars > 0, "${ref.name}: empty problem")
            if (resolved.objective != null) continue // optimization handled by parity, not here
            val r = ChocoSolver(resolved.problem).solve(ChocoParams(timeoutMillis = 30_000))
            if (ref.expected == Expected.Sat) {
                assertTrue(r is SolveResult.Sat, "${ref.name}: choco failed to find expected solution ($r)")
                assertTrue(satisfies(resolved.problem, r), "${ref.name}: choco solution violates klause constraints")
            }
        }
    }

    private fun satisfies(p: com.eignex.klause.solver.Problem, r: SolveResult.Sat): Boolean {
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
