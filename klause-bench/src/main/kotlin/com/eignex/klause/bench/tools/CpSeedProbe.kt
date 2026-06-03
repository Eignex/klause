package com.eignex.klause.bench.tools

import com.eignex.klause.formats.flatzinc.SolveDirective
import com.eignex.klause.formats.flatzinc.parseFlatZinc
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.MinimizeResult
import com.eignex.klause.solver.Objective
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.localsearch.CostShaping
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.solver.localsearch.LocalSearchSolver
import com.eignex.klause.solver.localsearch.strategy.AspirationCriterion
import com.eignex.klause.solver.localsearch.strategy.Cbls
import com.eignex.klause.solver.localsearch.strategy.TabuFilter
import com.eignex.klause.solver.maximizeInt
import com.eignex.klause.solver.minimizeInt
import java.io.File

/**
 * Hybrid CP-seeding probe: does handing LS a CP/backtrack-found *feasible* point unlock the
 * #54 optimization misses (which reach feasibility trivially under CP but never under LS)?
 * Runs three stages and prints the comparison:
 *   1. BacktrackSolver → first feasible assignment (budget `klause.cpseed.cpms`).
 *   2. LS minimize **cold** (random restart) for `klause.cpseed.lsms`.
 *   3. LS minimize **seeded** from the CP point (via [LocalSearchParams.initialAssignment]).
 *
 * NB: CP-seeding is OFF in the shipped LS path (the FZN CLI never sets `initialAssignment`),
 * keeping pure local search free of any CP dependency; this is a bench-only experiment.
 *
 * Run: `./gradlew :klause-bench:bench --args="diag:cpseed <fzn>" -Dklause.cpseed.cpms=4000 -Dklause.cpseed.lsms=8000`
 */
object CpSeedProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val path = System.getProperty("klause.cpseed.file") ?: args.getOrNull(0) ?: error("set -Dklause.cpseed.file=<fzn>")
        val cpMs = System.getProperty("klause.cpseed.cpms")?.toLong() ?: 4000L
        val lsMs = System.getProperty("klause.cpseed.lsms")?.toLong() ?: 8000L
        val prog = parseFlatZinc(File(path).readText())
        val problem = prog.problem
        val (objName, maximize) = when (val s = prog.solve) {
            is SolveDirective.Minimize -> s.objVar to false
            is SolveDirective.Maximize -> s.objVar to true
            else -> error("diag:cpseed needs an optimization instance (min/max), got ${prog.solve}")
        }
        val objVarId = prog.intVarsByName[objName] ?: error("objective var '$objName' not in int map")
        val linear = if (maximize) problem.maximizeInt(objVarId) else problem.minimizeInt(objVarId)
        val objective: Objective = prog.lsObjective ?: linear
        println(
            "=== ${File(path).name}  bool=${problem.numBoolVars} int=${problem.numIntVars} " +
                "factors=${problem.numFactors}  ${if (maximize) "max" else "min"} $objName ===",
        )

        // Stage 1: CP feasible seed.
        val cpStart = System.currentTimeMillis()
        val cpDeadline = cpStart + cpMs
        val cpResult = BacktrackSolver(problem).solve(
            BacktrackParams(randomSeed = 1L, cancellation = Cancellation { System.currentTimeMillis() > cpDeadline }),
        )
        val cpTook = System.currentTimeMillis() - cpStart
        val seed: Sample? = (cpResult as? SolveResult.Sat)?.assignment
        if (seed != null) {
            println("  CP feasible: yes @${cpTook}ms  (objective at seed = ${linear.evaluate(seed)})")
        } else {
            println("  CP feasible: NO @${cpTook}ms — nothing to seed; LS-seeded stage skipped")
        }

        val tabu = TabuFilter(tenure = 10, aspiration = AspirationCriterion.OrImproving)
        fun runLs(label: String, initial: Sample?) {
            val solver = LocalSearchSolver(problem, strategy = Cbls(tabu = tabu), optimizeStrategy = Cbls(tabu = tabu))
            val start = System.currentTimeMillis()
            val deadline = start + lsMs
            val params = LocalSearchParams(
                randomSeed = 1L,
                costShaping = CostShaping.Linear(lambda = 1.0),
                cancellation = Cancellation { System.currentTimeMillis() > deadline },
                initialAssignment = initial,
            )
            var best: Double? = null
            var firstMs = -1L
            var n = 0
            for (r in solver.improvements(objective, params)) {
                if (r is MinimizeResult.WithSample) {
                    if (firstMs < 0) firstMs = System.currentTimeMillis() - start
                    if (best == null || r.objective < best) best = r.objective
                    n++
                }
            }
            println(
                "  %-12s feasible=%-5s best=%-10s first=%sms n=%d".format(
                    label, (best != null).toString(), best?.toString() ?: "—",
                    if (firstMs < 0) "—" else firstMs.toString(), n,
                ),
            )
        }
        runLs("LS cold", null)
        if (seed != null) runLs("LS seeded", seed)
    }
}
