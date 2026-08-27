package com.eignex.klause.solver.integration

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.solver.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Learned fids live in the atom-literal watcher lists and, after a conflict return, in
 * the in-flight propagation queues. The forget compaction must remap both: a stale fid
 * surviving either indexes past the compacted clause array on the next drain (an
 * index-out-of-bounds at exactly the learned-clause cap), and a stale watcher silently
 * stops waking its clause. A tight cap with restarts on a conflict-heavy int model
 * exercises forget across live queues every few conflicts.
 */
class ForgetStaleFidTest {

    @Test
    fun `forgetting with tiny cap survives conflict-heavy atom learning`() {
        // 6 vars over 5 values, pairwise distinct: infeasible pigeonhole. Every conflict
        // learns atom-literal clauses; cap 2 forces forgetting at every Luby restart.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 6,
            intDomains = Array(6) { IntDomain(0, 4) },
            factors = arrayOf<Factor>(AllDifferent(intArrayOf(0, 1, 2, 3, 4, 5), domainMin = 0, domainSize = 5)),
        )
        val obj = LinearObjective(intCoefficients = LongArray(6) { 1L })
        val r = BacktrackSolver(problem.bake()).minimize(
            obj,
            BacktrackParams(
                randomSeed = 1L,
                lubyRestartBase = 1L,
                maxLearnedClauses = 2,
                maxDecisions = 50_000,
            ),
        )
        assertTrue(r is MinimizeResult.Infeasible, "pigeonhole must prove infeasible, got $r")
    }
}
