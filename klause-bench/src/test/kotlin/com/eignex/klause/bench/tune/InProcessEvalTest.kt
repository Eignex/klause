package com.eignex.klause.bench.tune

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.bench.catalog.Category
import com.eignex.klause.bench.catalog.Expected
import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.catalog.ProblemSource
import com.eignex.klause.bench.runner.ResolvedProblem
import com.eignex.klause.formats.opb.Opb
import com.eignex.klause.portfolio.LocalSearchCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The CSP (satisfy) path of the in-process eval: a constraints-only OPB has no objective, so the eval
 *  solves for satisfaction and reports feasibility + time-to-first-feasible instead of an objective. */
class InProcessEvalTest {

    /** A CSP instance from a constraints-only OPB (no `min:`) — parsed in-process, objective null. */
    private fun csp(name: String, opb: String): ResolvedProblem {
        val parsed = Opb.parse(opb)
        return ResolvedProblem(
            ref = ProblemRef(name, Format.OPB, ProblemSource.Vendored("test/$name"), Category.CSP, Expected.Unknown),
            problem = parsed.problem,
            objective = null,
        )
    }

    @Test
    fun `evalBt on a satisfiable CSP reports SAT with a time-to-first-feasible`() {
        val sat = csp("sat", "+1 x1 +1 x2 >= 1 ;\n")
        val r = InProcessEval.evalBt(sat, BacktrackParams(), budgetMs = 1000, seed = 1)
        assertTrue(r.feasible, "x1 ∨ x2 is satisfiable")
        assertFalse(r.proven, "a SAT witness is not a proof of optimality/UNSAT")
        assertNotNull(r.firstFeasibleMs, "a SAT result carries the time to the witness")
    }

    @Test
    fun `evalBt on an unsatisfiable CSP proves UNSAT`() {
        // x1 >= 1 and -x1 >= 0 (i.e. x1 <= 0) — no assignment satisfies both.
        val unsat = csp("unsat", "+1 x1 >= 1 ;\n-1 x1 >= 0 ;\n")
        val r = InProcessEval.evalBt(unsat, BacktrackParams(), budgetMs = 1000, seed = 1)
        assertFalse(r.feasible, "the instance is unsatisfiable")
        assertTrue(r.proven, "a complete backend proves the UNSAT")
    }

    @Test
    fun `evalLs on a satisfiable CSP finds a feasible witness`() {
        val sat = csp("sat", "+1 x1 +1 x2 >= 1 ;\n")
        val r = InProcessEval.evalLs(sat, LocalSearchCatalog.byLabel("cbls/fixed"), budgetMs = 1000, seed = 1)
        assertTrue(r.feasible, "local search reaches a feasible assignment")
        assertNotNull(r.firstFeasibleMs, "a SAT witness carries its time")
        assertEquals(null, r.objective, "a CSP eval has no objective")
    }
}
