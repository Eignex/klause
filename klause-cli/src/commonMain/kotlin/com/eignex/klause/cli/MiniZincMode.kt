package com.eignex.klause.cli

import com.eignex.klause.formats.flatzinc.OznApplier
import com.eignex.klause.formats.flatzinc.SolveDirective
import com.eignex.klause.formats.flatzinc.flatZincSemantics
import com.eignex.klause.formats.flatzinc.linearObjective
import com.eignex.klause.formats.flatzinc.parseFlatZinc
import com.eignex.klause.formats.flatzinc.prepareSourceExecution
import com.eignex.klause.formats.flatzinc.sourceAttachment
import com.eignex.klause.formats.flatzinc.writeFlatZincSolution
import com.eignex.klause.ir.EffectiveSourceSemantics
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.solver.pipeline.ExecutionOperation
import com.eignex.klause.solver.pipeline.FiniteSolveOutcome
import com.eignex.klause.solver.pipeline.NativeGridOutcome
import com.eignex.klause.solver.pipeline.SourceSetupRequest
import com.eignex.klause.solver.result.LinearOptimizationResult
import com.eignex.klause.solver.result.LpStats
import com.eignex.klause.solver.result.QuadraticOptimizationResult
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import kotlin.time.TimeSource

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
        private var outputObjective = false
        private var floatApproximation = false
        private var sourceSemantics: EffectiveSourceSemantics? = null

        override fun flags(): List<FlagSpec> = listOf(
            FlagSpec(
                listOf("--ozn"),
                true,
                FlagGroup.MODE,
                valueLabel = "file",
                help = "MiniZinc output model (.ozn) for solution reconstruction",
            ) { oznPath = it },
            // Like `minizinc --output-objective`: append `_objective = <value>;` to each solution
            // so a parser reading the raw solution stream can recover the optimised objective even
            // when the objective var is not in the model's `output` section. Off by default — the
            // standard MiniZinc flow renders objectives through solns2out, not this binary.
            FlagSpec(listOf("--output-objective"), false) { outputObjective = true },
        )

        override fun load(path: String, common: CommonOptions): Solvable {
            val config = common.config
            // Stream the .fzn straight from disk: the lexer/parser pull characters incrementally, so the
            // whole source is never held as one String. Parsing only reads; the base bake runs as
            // presolve step 0, bounded by the presolve deadline.
            val overrides = common.sourceOverrides(flatZincSemantics)
            val program = openFileSource(path).useSource { source ->
                parseFlatZinc(
                    settings = config.problemSettings(),
                    source = source,
                    semanticOverrides = overrides,
                )
            }
            val original = program.sourceProgram
            sourceSemantics = original.semantics
            cliLogger(common.verbose).v {
                "parsed $path: bools=${original.problem.numBoolVars} ints=${original.problem.numIntVars} " +
                    "factors=${original.problem.numFactors}"
            }
            val applier = oznPath?.let { OznApplier(readTextFile(it)) }
            val render: (Sample) -> String = { sample ->
                applier?.render(original, sample) ?: writeFlatZincSolution(original, sample, outputObjective)
            }

            val objective = original.linearObjective()
            val maximize = original.solve is SolveDirective.Maximize
            val sourceObjective = if (maximize) objective?.negated() else objective
            val operation = when {
                objective != null -> ExecutionOperation.OPTIMIZE
                common.allSolutions || (common.solutionCap ?: 1L) > 1L -> ExecutionOperation.ENUMERATE
                else -> ExecutionOperation.SATISFY
            }
            val start = TimeSource.Monotonic.markNow()
            var lpStats = LpStats()
            val invocation = common.routingPreparationCancellation().use { stop ->
                program.prepareSourceExecution(
                    SourceSetupRequest(
                        program.sourceAttachment(),
                        operation,
                        common.resolvedEngine(),
                        objective = sourceObjective,
                        maximize = maximize,
                        cancellation = stop,
                        boundCancellation = common.routingCancellation(),
                    ),
                    onLpStats = { lpStats = it },
                )
            }
            val elapsed = start.elapsedNow().inWholeMilliseconds
            common.logRoute(invocation.route, elapsed)
            floatApproximation = invocation.floatProjection != null
            if (invocation.floatProjection != null) {
                val realObjective = sourceObjective?.takeIf {
                    it.realCoefficients.any { coefficient -> coefficient != 0.0 }
                }
                return Solvable(
                    finite = invocation.finiteShape(),
                    render = render,
                    objectiveValue = sourceObjective?.takeIf { realObjective == null }
                        ?.let { linear -> { sample -> linear.evaluateLong(sample) } },
                    continuousObjectiveValue = realObjective?.let { linear ->
                        { sample -> linear.evaluateExact(sample).toDouble() }
                    },
                    routingLpStats = lpStats,
                    routingElapsedMs = elapsed,
                )
            }
            return preparedSourceSolvable(invocation, render, { assignment ->
                applier?.render(original, assignment)
                    ?: writeFlatZincSolution(original, assignment, outputObjective)
            }, lpStats, elapsed)
        }

        override fun output(common: CommonOptions): OutputProtocol = MiniZincOutput(floatApproximation, sourceSemantics)
    }
}

/** MiniZinc's standard FZN solution protocol. */
internal class MiniZincOutput(
    private val floatApproximation: Boolean = false,
    private val sourceSemantics: EffectiveSourceSemantics? = null,
) : OutputProtocol {
    private var context: VerdictContext = VerdictContext()
    private val integerScopeChanged = sourceSemantics?.let {
        it.declared.undeclaredIntegers != it.effective.undeclaredIntegers
    } == true
    private val realScopeChanged = sourceSemantics?.let {
        it.declared.undeclaredReals != it.effective.undeclaredReals
    } == true
    private val declarationScopeChanged = integerScopeChanged || realScopeChanged

    override fun begin(optimize: Boolean, maximize: Boolean) {
        if (integerScopeChanged) {
            val bounds = checkNotNull(sourceSemantics?.effective?.undeclaredIntegers)
            println(
                "% klause-source-semantics: unbounded-int-lo=${bounds.lower ?: "open"} " +
                    "unbounded-int-hi=${bounds.upper ?: "open"}",
            )
        }
        if (realScopeChanged) {
            val bounds = checkNotNull(sourceSemantics?.effective?.undeclaredReals)
            val lower = bounds.lower?.let { BigFraction.of(it.numerator, it.denominator).toString() } ?: "open"
            val upper = bounds.upper?.let { BigFraction.of(it.numerator, it.denominator).toString() } ?: "open"
            println(
                "% klause-source-semantics: unbounded-real-lo=$lower unbounded-real-hi=$upper",
            )
        }
    }

    override fun onVerdictContext(context: VerdictContext) {
        this.context = context
    }

    override fun onSolution(rendered: String, objective: Long?, continuousObjective: Double?) {
        // `writeFlatZincSolution` / `OznApplier.render` already include the per-solution
        // `----------` terminator; objective is carried in the rendered text.
        print(rendered)
    }

    override fun onExactSolution(rendered: String, objective: BigFraction) {
        print(rendered)
    }

    override fun onArithmeticIncumbent(rendered: String, objective: SourceValue): Boolean {
        println("% objective=${arithmeticObjectiveText(objective)}")
        print(rendered)
        flushCliOutput()
        return true
    }

    override fun onQuadraticResult(rendered: String?, result: QuadraticOptimizationResult) {
        rendered?.let { onSolution(it, null) }
        if (result is QuadraticOptimizationResult.UnattainedLimit) {
            unattainedLimit(SourceValue.Algebraic(result.bound.value), result.sense, result.bound.strict)
        }
    }

    override fun onLinearResult(result: LinearOptimizationResult) {
        val claim = result.terminal?.claim as? SourceClaim.Limit ?: return
        val sense = result.scope.objective?.sense ?: return
        unattainedLimit(claim.value, sense, strict = true)
    }

    private fun unattainedLimit(value: SourceValue, sense: ObjectiveSense, strict: Boolean) {
        if (floatApproximation || declarationScopeChanged) return
        val minimize = sense == ObjectiveSense.MINIMIZE
        val status = if (minimize) "unattained-infimum" else "unattained-supremum"
        val direction = if (minimize) "lower" else "upper"
        println("% optimizationStatus=$status")
        println("% objectiveBoundDirection=$direction")
        println("% objectiveBound=${arithmeticObjectiveText(value)}")
        println("% objectiveBoundStrict=$strict")
    }

    override fun onComplete(verdict: Verdict) {
        if (floatApproximation) {
            println("% klause-float-claim: declared finite grid; no global continuous-source proof")
        }
        if (integerScopeChanged) {
            println("% klause-source-claim: caller integer bounds; no default-source proof")
        }
        if (realScopeChanged) {
            println("% klause-source-claim: caller real bounds; no default-source proof")
        }
        val qualified = flatZincSourceVerdict(verdict, declarationScopeChanged ||
            (floatApproximation && verdict == Verdict.UNBOUNDED))
        when (qualified) {
            Verdict.SATISFIABLE, Verdict.OPTIMAL -> println("==========")

            // Incumbents already streamed; printing `==========` would falsely claim optimality.
            Verdict.BEST_FOUND -> Unit

            Verdict.UNBOUNDED -> println("=====UNBOUNDED=====")

            Verdict.UNSATISFIABLE -> println("=====UNSATISFIABLE=====")

            // A `%` line is a comment to every FlatZinc consumer, so the cause rides alongside the
            // standard terminator without changing what the protocol promises.
            Verdict.UNKNOWN -> {
                println("=====UNKNOWN=====")
                val cause = when {
                    integerScopeChanged -> "caller integer bounds changed source scope"
                    realScopeChanged -> "caller real bounds changed source scope"
                    else -> context.softVerdictCause()
                }
                println("% $cause")
            }
        }
    }

    override fun onNativeGridOutcome(result: NativeGridOutcome) {
        val outcome = result.outcome as? FiniteSolveOutcome.Completed ?: return
        println("% klause-grid-verdict: ${outcome.verdict}; scope=private-grid")
        println("% klause-grid-solutions: ${outcome.solutions}")
    }

    override fun onAllowanceStatistics(pairs: List<Pair<String, String>>) {
        printStatPairs("%%%mzn-stat:", pairs)
    }

    /**
     * `-s`: MiniZinc-standard `%%%mzn-stat: key=value` lines closed by `%%%mzn-stat-end`.
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
            }
            printStatPairs("%%%mzn-stat:", openTheoryStatPairs(stats, solveTimeMs))
            printStatPairs("%%%mzn-stat:", lsStatPairs(stats, solveTimeMs))
        }
        printStatPairs("%%%mzn-stat:", lpStatPairs(stats))
        printStatPairs("%%%mzn-stat:", portfolioStatPairs(stats))
        println("%%%mzn-stat-end")
    }
}

internal fun flatZincSourceVerdict(verdict: Verdict, approximation: Boolean): Verdict = if (!approximation) {
    verdict
} else {
    when (verdict) {
        Verdict.SATISFIABLE, Verdict.OPTIMAL -> Verdict.BEST_FOUND
        Verdict.UNSATISFIABLE, Verdict.UNBOUNDED -> Verdict.UNKNOWN
        else -> verdict
    }
}
