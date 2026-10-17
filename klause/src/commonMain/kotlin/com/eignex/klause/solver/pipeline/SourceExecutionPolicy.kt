package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.lp.bounding.LpEmphasis
import com.eignex.klause.lp.engine.LpZeroObjectivePricing
import com.eignex.klause.portfolio.ArmFault
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.result.SearchEvent
import com.eignex.klause.util.Cancellation

/** Caller policy captured independently of source declarations and resolved before worker construction. */
class SourceExecutionPolicy(
    lpCeiling: LpConfig? = null,
    portfolioLpCeiling: LpConfig? = null,
    /** Application default recipe count for portfolio routes; explicit arm settings take precedence. */
    val portfolioArms: Int? = null,
    /** Requested zero-objective LP pricing, absent when the application uses the library default. */
    val zeroObjectivePricing: LpZeroObjectivePricing? = null,
    /** Whether the caller explicitly selected a presolve emphasis override. */
    val explicitPresolve: Boolean = false,
) {
    /** Requested optional LP ceiling, including per-technique overrides. */
    val lpCeiling: LpConfig? = lpCeiling?.let { LpConfig(it.emphasis, it.overrides.toMap()) }

    /** Application default LP ceiling for portfolio routes. */
    val portfolioLpCeiling: LpConfig? = portfolioLpCeiling?.let { LpConfig(it.emphasis, it.overrides.toMap()) }

    init {
        if (portfolioArms != null && portfolioArms <= 0) {
            pipelineConfigError("portfolio arm count must be positive")
        }
    }

    /** Bind finite defaults and explicit overrides while retaining the caller's hooks and containing allowances. */
    fun finiteRequest(
        shape: FiniteSolveShape,
        engine: FiniteEngine,
        presolveConfig: PresolveConfig = PresolveConfig.DEFAULT,
        solutionSetSensitive: Boolean = false,
        cancellation: Cancellation = Cancellation.Never,
        presolveCancellation: Cancellation = cancellation,
        presolveBudget: PresolveBudget? = null,
        cores: Int = 1,
        executionOptions: ExecutionOptions = ExecutionOptions(),
        randomSeed: Long? = null,
        nodeBudget: NodeBudget? = null,
        solveBudgetMillis: Long? = null,
        allSolutions: Boolean = false,
        solutionCap: Long? = null,
        deadlineExceeded: () -> Boolean = { false },
        onEvent: ((SearchEvent) -> Unit)? = null,
        onPortfolioEvent: ((worker: String, event: SearchEvent) -> Unit)? = null,
        onPortfolioFault: ((ArmFault) -> Unit)? = null,
        prepareOnly: Boolean = false,
        toleranceCheck: ((Sample) -> Boolean)? = null,
    ): FiniteSolveRequest {
        require(cores > 0)
        if (engine == FiniteEngine.FIXED && cores > 1) {
            pipelineConfigError("engine 'fixed' is single-core; use 'backtrack' for a parallel pool")
        }
        val reader = executionOptions.reader()
        val preparation = reader.presolve(presolveConfig)
        val seed = reader[ExecutionOption.seed] ?: randomSeed ?: 0L
        return FiniteSolveRequest(
            shape, engine, preparation.config, explicitPresolve, solutionSetSensitive,
            cancellation, presolveCancellation, presolveBudget, cores,
            options(engine, executionOptions.withoutPresolve()),
            seed, arms(engine, cores), lpConfig(engine),
            zeroObjectivePricing ?: LpZeroObjectivePricing.MIN_BOUND_SUPPORT, nodeBudget,
            solveBudgetMillis.takeIf { engine == FiniteEngine.FIXED }, allSolutions, solutionCap,
            deadlineExceeded, onEvent, onPortfolioEvent, onPortfolioFault, prepareOnly, toleranceCheck,
        )
    }

    internal fun validate(kind: SourceExecutionKind, engine: FiniteEngine, presolveTuning: Boolean = false) {
        val finite = kind == SourceExecutionKind.FINITE || kind == SourceExecutionKind.REFUTED
        if (!finite) {
            if (lpCeiling != null || (engine != FiniteEngine.FIXED && portfolioLpCeiling != null)) {
                pipelineConfigError("--lp requires finite source execution; selected ${kind.name.lowercase()}")
            }
            if (zeroObjectivePricing != null) {
                pipelineConfigError("--lp-pricing requires finite source execution; selected ${kind.name.lowercase()}")
            }
            if (engine != FiniteEngine.FIXED && portfolioArms != null) {
                pipelineConfigError("portfolio arm defaults do not apply to source ${kind.name.lowercase()}")
            }
        }
        if ((explicitPresolve || presolveTuning) && !finite && kind != SourceExecutionKind.LINEAR) {
            pipelineConfigError(
                "--presolve requires finite or linear source execution; selected ${kind.name.lowercase()}",
            )
        }
        if (finite) lpConfig(engine)
    }

    internal fun options(engine: FiniteEngine, options: ExecutionOptions): ExecutionOptions =
        if (engine != FiniteEngine.FIXED && portfolioArms != null && !options.explicitArms) {
            options.overriddenBy(ExecutionOptions(ExecutionOption.arms(portfolioArms)))
        } else {
            options
        }

    internal fun arms(engine: FiniteEngine, cores: Int, fallback: Int = autoArms(cores)): Int =
        if (engine == FiniteEngine.FIXED) 1 else portfolioArms ?: fallback

    internal fun lpConfig(engine: FiniteEngine, fallback: LpConfig? = null): LpConfig {
        if (engine == FiniteEngine.FIXED) {
            if (lpCeiling != null &&
                (lpCeiling.emphasis != LpEmphasis.OFF || lpCeiling.overrides.values.any { it })
            ) {
                pipelineConfigError("--lp: engine 'fixed' accepts only off")
            }
            return LpConfig.OFF
        }
        val ceiling = lpCeiling ?: portfolioLpCeiling
        return if (fallback != null && ceiling != null) {
            fallback.cappedUnder(ceiling)
        } else {
            fallback ?: ceiling ?: LpConfig.AGGRESSIVE
        }
    }
}
