package com.eignex.klause.cli

import com.eignex.klause.config.KlauseConfig
import com.eignex.klause.formats.flatzinc.SolveDirective
import com.eignex.klause.formats.flatzinc.parseFlatZinc
import com.eignex.klause.formats.flatzinc.writeFlatZincSolution
import com.eignex.klause.formats.minizinc.OznApplier
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.maximizeInt
import com.eignex.klause.solver.objective.minimizeInt
import com.eignex.klause.solver.result.SolveStats

/**
 * MiniZinc / FlatZinc front-end. The default mode: MiniZinc invokes this binary via the
 * `klause.msc` wrapper with MiniZinc-standard flags and a `.fzn` file. Emits MiniZinc's
 * standard solution protocol (`----------` per solution, `==========` for completed search,
 * `=====UNSATISFIABLE=====` / `=====UNKNOWN=====`, `%%%mzn-stat` for `-s`).
 */
internal object MiniZincMode : CliMode {
    override val names = listOf("minizinc", "fzn", "flatzinc")
    override val extensions = listOf("fzn")
    override fun newSession(): ModeSession = Session()

    private class Session : ModeSession {
        private var oznPath: String? = null
        private var unboundedIntLo: Int? = null
        private var unboundedIntHi: Int? = null
        private var outputObjective = false

        override fun flags(): List<FlagSpec> = listOf(
            FlagSpec(
                listOf("--ozn"),
                true,
                FlagGroup.MODE,
                valueLabel = "file",
                help = "MiniZinc output model (.ozn) for solution reconstruction",
            ) { oznPath = it },
            // Advanced unbounded-`var int` range knobs (also env-configurable); hidden from --help.
            FlagSpec(listOf("--unbounded-int-lo"), true) { unboundedIntLo = requireNotNull(it).toInt() },
            FlagSpec(listOf("--unbounded-int-hi"), true) { unboundedIntHi = requireNotNull(it).toInt() },
            // Like `minizinc --output-objective`: append `_objective = <value>;` to each solution
            // so a parser reading the raw solution stream can recover the optimised objective even
            // when the objective var is not in the model's `output` section. Off by default — the
            // standard MiniZinc flow renders objectives through solns2out, not this binary.
            FlagSpec(listOf("--output-objective"), false) { outputObjective = true },
        )

        override fun load(path: String, common: CommonOptions): Solvable {
            // The ambient config was installed once in `main`. Unbounded `var int` resolution:
            // CLI flag → KlauseConfig → built-in default (matches Gecode/Chuffed).
            val config = KlauseConfig.current
            val source = readTextFile(path)
            // Honor `-t` during the construction-time bake (Problem.computeBaked): a wide-domain
            // model can otherwise wedge the bake before any solver/cancellation exists.
            val bakeCancel =
                common.deadlineAtMs?.let { d -> Cancellation { nowMillis() > d } } ?: Cancellation.Never
            val program = parseFlatZinc(
                source = source,
                floatBuckets = config.floatBuckets,
                floatScale = config.floatScale,
                unboundedIntLo = unboundedIntLo ?: config.unboundedIntLo,
                unboundedIntHi = unboundedIntHi ?: config.unboundedIntHi,
                cancellation = bakeCancel,
            )
            cliLogger(common.verbose).v {
                "parsed $path: bools=${program.problem.numBoolVars} ints=${program.problem.numIntVars} " +
                    "factors=${program.problem.numFactors}"
            }
            val applier = oznPath?.let { OznApplier(readTextFile(it)) }
            val render: (Sample) -> String =
                { s -> applier?.render(program, s) ?: writeFlatZincSolution(program, s, outputObjective) }

            return when (val solve = program.solve) {
                is SolveDirective.Satisfy -> Solvable(
                    problem = program.problem,
                    optimize = false,
                    maximize = false,
                    lsObjective = null,
                    linearObjective = null,
                    objVarId = null,
                    definitionalSweep = program.definitionalSweep,
                    render = render,
                    objectiveValue = null,
                    annotatedBacktrackParams = program.defaultBacktrackParams,
                )

                is SolveDirective.Minimize, is SolveDirective.Maximize -> {
                    val (objName, maximize) = when (solve) {
                        is SolveDirective.Minimize -> solve.objVar to false
                        is SolveDirective.Maximize -> solve.objVar to true
                    }
                    val objVarId = program.intVarsByName[objName]
                        ?: error("objective variable '$objName' not found in int var map")
                    // The complete backends bound on the LinearObjective; LS workers descend the
                    // functional (defines_var cone) objective when the model provides one.
                    val linear = if (maximize) {
                        program.problem.maximizeInt(objVarId)
                    } else {
                        program.problem.minimizeInt(objVarId)
                    }
                    Solvable(
                        problem = program.problem,
                        optimize = true,
                        maximize = maximize,
                        lsObjective = program.lsObjective,
                        linearObjective = linear,
                        objVarId = objVarId,
                        definitionalSweep = program.definitionalSweep,
                        render = render,
                        // The MiniZinc protocol carries the objective inside the rendered solution, so
                        // onSolution ignores this value — but the same sign-corrected objective (the
                        // canonical linear objective in original units, see the generic builder in
                        // CliMode) feeds arm attribution and the LS incumbent statistic, which the engine
                        // produces in its internal minimise frame. Reuse one lambda for all three.
                        objectiveValue = { s -> linear.evaluateLong(s).let { if (maximize) -it else it } },
                        annotatedBacktrackParams = program.defaultBacktrackParams,
                    )
                }
            }
        }

        override fun output(common: CommonOptions): OutputProtocol = MiniZincOutput()
    }
}

/** MiniZinc's standard FZN solution protocol. */
internal class MiniZincOutput : OutputProtocol {
    override fun onSolution(rendered: String, objective: Long?) {
        // `writeFlatZincSolution` / `OznApplier.render` already include the per-solution
        // `----------` terminator; objective is carried in the rendered text.
        print(rendered)
    }

    override fun onComplete(verdict: Verdict) {
        when (verdict) {
            Verdict.SATISFIABLE, Verdict.OPTIMAL -> println("==========")

            // Incumbents already streamed; printing `==========` would falsely claim optimality.
            Verdict.BEST_FOUND -> Unit

            Verdict.UNSATISFIABLE -> println("=====UNSATISFIABLE=====")

            Verdict.UNKNOWN -> println("=====UNKNOWN=====")
        }
    }

    /**
     * `-s`: MiniZinc-standard `%%%mzn-stat: key=value` lines closed by `%%%mzn-stat-end` (#141).
     * [stats] is [SolveStats.EMPTY] when the verdict reports no engine counters (enumeration /
     * portfolio); the CLI-side time and solution count are always available.
     */
    override fun onStatistics(stats: SolveStats, solveTimeMs: Long, solutions: Long) {
        println("%%%mzn-stat: solveTime=${solveTimeMs / 1000.0}")
        println("%%%mzn-stat: solutions=$solutions")
        // Presolve summary is backend-independent (presolve runs before any engine), so emit it here.
        printStatPairs("%%%mzn-stat:", presolveStatPairs(stats))
        if (stats.run.backend.isNotEmpty()) {
            // Complete (backtrack/CDCL) counters are meaningful only when the systematic engine did work;
            // a pure-LS solve has none, so its block stays empty rather than a wall of zeros. A `mixed`
            // portfolio with a backtrack arm reports them alongside the LS block below.
            val complete =
                stats.search.nodes.sum > 0.0 || stats.search.propagations.sum > 0.0 || stats.run.backend == "backtrack"
            if (complete) {
                printStatPairs("%%%mzn-stat:", searchStatPairs(stats))
                printStatPairs("%%%mzn-stat:", caStatPairs(stats))
                printStatPairs("%%%mzn-stat:", lpStatPairs(stats))
            }
            printStatPairs("%%%mzn-stat:", lsStatPairs(stats))
        }
        println("%%%mzn-stat-end")
    }
}
