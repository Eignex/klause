package com.eignex.klause.solver.pipeline

import com.eignex.klause.localsearch.strategy.AxisEdits
import com.eignex.klause.localsearch.strategy.AxisToken
import com.eignex.klause.meta.coreguided.CoreRefinementMode
import com.eignex.klause.presolve.AffinePivotOrder

/** A typed execution setting. Values describe policy; workers own live heuristic state. */
class ExecutionOption<T> private constructor(internal val id: String, private val kind: ValueKind) {
    /** Bind this option to a value without command-line syntax. */
    @Suppress("UNCHECKED_CAST")
    operator fun invoke(value: T): ExecutionSetting<T> {
        val captured = if (value is List<*>) value.toList() else value
        val entry = try {
            if (kind == ValueKind.SCALAR_AXIS) scalarTokens(captured as List<AxisToken>, id)
            if (kind == ValueKind.POSITIVE_LONG) positiveLong(captured as Long)
            if (kind == ValueKind.ALLOWANCE && captured != null && (captured as Long) < 0) {
                pipelineConfigError("engine param `$id` expects a non-negative allowance")
            }
            OptionValue.Valid(captured)
        } catch (failure: PipelineConfigException) {
            OptionValue.Invalid(requireNotNull(failure.message), configuration = true)
        }
        return ExecutionSetting(this, entry)
    }

    @Suppress("UNCHECKED_CAST")
    internal fun decode(params: EngineParams): T? = when (kind) {
        ValueKind.LONG -> params.long(id)

        ValueKind.POSITIVE_LONG -> params.long(id)?.let { positiveLong(it) }

        ValueKind.INT -> params.int(id)

        ValueKind.DOUBLE -> params.double(id)

        ValueKind.BOOLEAN -> params.bool(id)

        ValueKind.STRING -> params.string(id)

        ValueKind.AFFINE_PIVOT_ORDER -> params.string(id)?.let { raw ->
            AffinePivotOrder.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                ?: pipelineConfigError(
                    "engine param `$id` expects " +
                        AffinePivotOrder.entries.joinToString("|") { it.name.lowercase() } + ", got `$raw`",
                )
        }

        ValueKind.CORE_REFINEMENT -> params.string(id)?.let { raw ->
            CoreRefinementMode.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                ?: pipelineConfigError("engine param `$id` expects none|deletion|reasons, got `$raw`")
        }

        ValueKind.VARIABLE_SELECTOR -> params.varSelectorKind(id)

        ValueKind.VALUE_SELECTOR -> params.valSelectorKind(id)

        ValueKind.SOURCES -> params.string(id)?.let { AxisEdits.tokens(it) }

        ValueKind.SCALAR_AXIS -> params.string(id)?.let { scalarTokens(it, id) }

        ValueKind.LABELS -> params.string(id)?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }

        ValueKind.ALLOWANCE -> params.string(id)?.let { raw ->
            when (raw.lowercase()) {
                "unlimited" -> null

                else -> raw.toLongOrNull()?.takeIf { it >= 0 }
                    ?: pipelineConfigError(
                        "engine param `$id` expects auto, unlimited or a non-negative integer, got `$raw`",
                    )
            }
        }

        ValueKind.OPEN_BRANCHING -> params.string(id)?.let { raw ->
            OpenBranching.of(raw) ?: pipelineConfigError(
                "engine param `open-branching` expects one of ${OpenBranching.entries.joinToString(", ") { it.id }}, " +
                    "got `$raw`",
            )
        }
    } as T?

    private fun positiveLong(value: Long): Long {
        if (value <= 0L) pipelineConfigError("engine param `$id` expects a positive integer, got `$value`")
        return value
    }

    private enum class ValueKind {
        CORE_REFINEMENT,
        LONG,
        POSITIVE_LONG,
        INT,
        DOUBLE,
        BOOLEAN,
        STRING,
        VARIABLE_SELECTOR,
        VALUE_SELECTOR,
        SOURCES,
        SCALAR_AXIS,
        LABELS,
        ALLOWANCE,
        OPEN_BRANCHING,
        AFFINE_PIVOT_ORDER,
    }

    internal val allowance: Boolean get() = kind == ValueKind.ALLOWANCE

    /** Tuning and resource settings, separate from source declarations. */
    companion object {
        /** Stop after supported source or finite preparation without constructing search workers. */
        val dryRunPresolve: ExecutionOption<Boolean> = ExecutionOption("dry-run-presolve", ValueKind.BOOLEAN)

        /** Ordering of pivots during source affine elimination. */
        val affinePivotOrder: ExecutionOption<AffinePivotOrder> =
            ExecutionOption("affine-pivot-order", ValueKind.AFFINE_PIVOT_ORDER)

        /** Keep structured readers and binary partitions during affine and structural rewriting. */
        val presolvePreserveStructure: ExecutionOption<Boolean> =
            ExecutionOption("presolve-preserve-structure", ValueKind.BOOLEAN)

        /** Factor/entry visit cap per rewrite impact observation; zero disables it. */
        val presolveStructuralImpact: ExecutionOption<Int> =
            ExecutionOption("presolve-structural-impact", ValueKind.INT)

        /** Minimum relative gain that keeps a presolve round active. */
        val presolveAbortFraction: ExecutionOption<Double> = ExecutionOption(
            "presolve-abort-fraction",
            ValueKind.DOUBLE,
        )

        /** Maximum presolve rounds. */
        val presolveMaxRounds: ExecutionOption<Int> = ExecutionOption("presolve-max-rounds", ValueKind.INT)

        /** Per-variable presolve probe allowance. */
        val presolveProbePerVar: ExecutionOption<Int> = ExecutionOption("presolve-probe-per-var", ValueKind.INT)

        /** Total presolve probe allowance. */
        val presolveProbeTotal: ExecutionOption<Int> = ExecutionOption("presolve-probe-total", ValueKind.INT)

        /** Shared decision and heuristic instruction allowance; null means unlimited. */
        val nodeLimit: ExecutionOption<Long?> = ExecutionOption("node-limit", ValueKind.ALLOWANCE)

        /** Native open arithmetic work, independent of the shared node allowance. */
        val openWorkLimit: ExecutionOption<Long?> = ExecutionOption("open-work-limit", ValueKind.ALLOWANCE)

        /** Cumulative source LP work, including preparation, numerical advances and certificates. */
        val sourceLpWorkLimit: ExecutionOption<Long?> = ExecutionOption("source-lp-work-limit", ValueKind.ALLOWANCE)

        /** Cumulative source preparation, proof replay and modeled retained coverage allocation in bytes. */
        val sourceLpAllocationLimit: ExecutionOption<Long?> =
            ExecutionOption("source-lp-allocation-limit", ValueKind.ALLOWANCE)

        /** Source LP operation ceiling for controlled comparisons. */
        val sourceLpOperations: ExecutionOption<Int> = ExecutionOption("source-lp-operations", ValueKind.INT)

        /** Checked numerical source-row separation control. */
        val sourceCuts: ExecutionOption<Boolean> = ExecutionOption("source-cuts", ValueKind.BOOLEAN)

        /** Point-based selection of checked numerical source rows. */
        val sourcePointCuts: ExecutionOption<Boolean> = ExecutionOption("source-point-cuts", ValueKind.BOOLEAN)

        /** Bounded numerical source-bound aggregation control. */
        val sourceAggregateCuts: ExecutionOption<Boolean> = ExecutionOption("source-aggregate-cuts", ValueKind.BOOLEAN)

        /** Bounded advisory incumbent completion under the exact companion's existing allowance. */
        val sourceIncumbentCompletion: ExecutionOption<Boolean> =
            ExecutionOption("source-incumbent-completion", ValueKind.BOOLEAN)

        /** Invocation-local checked numerical refutation reuse control. */
        val sourceRefutationReuse: ExecutionOption<Boolean> =
            ExecutionOption("source-refutation-reuse", ValueKind.BOOLEAN)

        /** Invocation-local checked numerical unit-prefix deduction control. */
        val sourceRefutationPropagation: ExecutionOption<Boolean> =
            ExecutionOption("source-refutation-propagation", ValueKind.BOOLEAN)

        /** Checked resolution over named numerical source-bound predicates. */
        val sourceRefutationResolution: ExecutionOption<Boolean> =
            ExecutionOption("source-refutation-resolution", ValueKind.BOOLEAN)

        /** Native polynomial or function arithmetic work; null means unlimited. */
        val nraWorkLimit: ExecutionOption<Long?> = ExecutionOption("nra-work-limit", ValueKind.ALLOWANCE)

        /** Dense polynomial coefficient capacity; null means unlimited. */
        val nraDenseCoefficients: ExecutionOption<Long?> =
            ExecutionOption("nra-dense-coefficients", ValueKind.ALLOWANCE)

        /** Polynomial matrix capacity; null means unlimited. */
        val nraMatrixCells: ExecutionOption<Long?> = ExecutionOption("nra-matrix-cells", ValueKind.ALLOWANCE)

        /** Nonlinear integer candidate attempts; null means unlimited. */
        val niaCandidates: ExecutionOption<Long?> = ExecutionOption("nia-candidates", ValueKind.ALLOWANCE)

        /** Nonlinear integer heuristic moves; null means unlimited. */
        val niaMoves: ExecutionOption<Long?> = ExecutionOption("nia-moves", ValueKind.ALLOWANCE)

        /** Bounded checked univariate producer for nonlinear integer completions. */
        val niaCellRefinement: ExecutionOption<Boolean> = ExecutionOption("nia-cell-refinement", ValueKind.BOOLEAN)

        /** Reuse source-scoped cells to filter unvisited fixed integer tuples. */
        val niaCellReuse: ExecutionOption<Boolean> = ExecutionOption("nia-cell-reuse", ValueKind.BOOLEAN)

        /** Alternate integer proposals with exact checked source-cell complements. */
        val niaCellSteering: ExecutionOption<Boolean> = ExecutionOption("nia-cell-steering", ValueKind.BOOLEAN)

        /** Admit complete integer root sets of mandatory unary polynomial equalities. */
        val niaIntegerRoots: ExecutionOption<Boolean> = ExecutionOption("nia-integer-roots", ValueKind.BOOLEAN)

        /** Refute integer equality subsystems and exclude impossible atoms by bounded residue checks. */
        val niaResidues: ExecutionOption<Boolean> = ExecutionOption("nia-residues", ValueKind.BOOLEAN)

        /** Native heuristic proposal cap. */
        val maxFlips: ExecutionOption<Long> = ExecutionOption("max-flips", ValueKind.LONG)

        /** Native heuristic and finite traversal instruction cap. */
        val maxInstructions: ExecutionOption<Long> = ExecutionOption("max-instructions", ValueKind.LONG)

        /** ALNS destroy and repair iteration cap. */
        val alnsIterations: ExecutionOption<Int> = ExecutionOption("alns-iterations", ValueKind.INT)

        /** Shared open traversal restart cadence. */
        val sharedRestart: ExecutionOption<Long> = ExecutionOption("shared-restart", ValueKind.LONG)

        /** Open traversal branch policy. */
        val openBranching: ExecutionOption<OpenBranching> = ExecutionOption("open-branching", ValueKind.OPEN_BRANCHING)

        /** Optional open Boolean hint producer allowance. */
        val openHintFlips: ExecutionOption<Long> = ExecutionOption("open-hint-flips", ValueKind.LONG)

        /** Splits required before the open Boolean hint producer runs. */
        val openHintMinSplits: ExecutionOption<Long> = ExecutionOption("open-hint-min-splits", ValueKind.LONG)

        /** The `max-decisions` execution setting. */
        val maxDecisions: ExecutionOption<Long> = ExecutionOption("max-decisions", ValueKind.LONG)

        /** Strictly positive Luby restart unit. */
        val luby: ExecutionOption<Long> = ExecutionOption("luby", ValueKind.POSITIVE_LONG)

        /** The `adaptive-restart` execution setting. */
        val adaptiveRestart: ExecutionOption<Boolean> = ExecutionOption("adaptive-restart", ValueKind.BOOLEAN)

        /** The `ema-restart` execution setting. */
        val emaRestart: ExecutionOption<Boolean> = ExecutionOption("ema-restart", ValueKind.BOOLEAN)

        /** The `mode-switching-restart` execution setting. */
        val modeSwitchingRestart: ExecutionOption<Boolean> =
            ExecutionOption("mode-switching-restart", ValueKind.BOOLEAN)

        /** The `phase-saving` execution setting. */
        val phaseSaving: ExecutionOption<Boolean> = ExecutionOption("phase-saving", ValueKind.BOOLEAN)

        /** The `target-phasing` execution setting. */
        val targetPhasing: ExecutionOption<Boolean> = ExecutionOption("target-phasing", ValueKind.BOOLEAN)

        /** The `solution-phasing` execution setting. */
        val solutionPhasing: ExecutionOption<Boolean> = ExecutionOption("solution-phasing", ValueKind.BOOLEAN)

        /** The `rephase-interval` execution setting. */
        val rephaseInterval: ExecutionOption<Long> = ExecutionOption("rephase-interval", ValueKind.LONG)

        /** The `max-learned` execution setting. */
        val maxLearned: ExecutionOption<Int> = ExecutionOption("max-learned", ValueKind.INT)

        /** The `learned-memory-bytes` execution setting. */
        val learnedMemoryBytes: ExecutionOption<Long> = ExecutionOption("learned-memory-bytes", ValueKind.LONG)

        /** The `learned-memory-work` execution setting. */
        val learnedMemoryWork: ExecutionOption<Long> = ExecutionOption("learned-memory-work", ValueKind.LONG)

        /** The `learned-memory-scratch-bytes` execution setting. */
        val learnedMemoryScratchBytes: ExecutionOption<Long> =
            ExecutionOption("learned-memory-scratch-bytes", ValueKind.LONG)

        /** The `lbd-glue` execution setting. */
        val lbdGlue: ExecutionOption<Int> = ExecutionOption("lbd-glue", ValueKind.INT)

        /** The `tiered-db` execution setting. */
        val tieredDb: ExecutionOption<Boolean> = ExecutionOption("tiered-db", ValueKind.BOOLEAN)

        /** The `mid-lbd` execution setting. */
        val midLbd: ExecutionOption<Int> = ExecutionOption("mid-lbd", ValueKind.INT)

        /** The `vivification` execution setting. */
        val vivification: ExecutionOption<Boolean> = ExecutionOption("vivification", ValueKind.BOOLEAN)

        /** The `vivify-batch` execution setting. */
        val vivifyBatch: ExecutionOption<Int> = ExecutionOption("vivify-batch", ValueKind.INT)

        /** The `subsumption` execution setting. */
        val subsumption: ExecutionOption<Boolean> = ExecutionOption("subsumption", ValueKind.BOOLEAN)

        /** The `subsume-batch` execution setting. */
        val subsumeBatch: ExecutionOption<Int> = ExecutionOption("subsume-batch", ValueKind.INT)

        /** The `inprocessing-cadence` execution setting. */
        val inprocessingCadence: ExecutionOption<Int> = ExecutionOption("inprocessing-cadence", ValueKind.INT)

        /** Enable stable-ID permanent-root clause simplification. */
        val rootSimplification: ExecutionOption<Boolean> = ExecutionOption("root-simplification", ValueKind.BOOLEAN)

        /** Maximum clauses examined per root simplification slice. */
        val rootSimplifyBatch: ExecutionOption<Int> = ExecutionOption("root-simplify-batch", ValueKind.INT)

        /** Bounded propositional opportunity scan; zero disables capture. */
        val rootRebuildCaptureVisits: ExecutionOption<Int> = ExecutionOption(
            "root-rebuild-capture-visits", ValueKind.INT,
        )

        /** Failed-literal probes per scheduled restart; zero disables probing. */
        val rootProbeBatch: ExecutionOption<Int> = ExecutionOption("root-probe-batch", ValueKind.INT)

        /** Maximum propagation work per root probe. */
        val rootProbeWork: ExecutionOption<Long> = ExecutionOption("root-probe-work", ValueKind.POSITIVE_LONG)

        /** Containing work ceiling for one scheduled simplification slice. */
        val inprocessingWork: ExecutionOption<Long> = ExecutionOption("inprocessing-work", ValueKind.LONG)
        /** Cheap root scan work ceiling. */
        val inprocessingRootWork: ExecutionOption<Long> = ExecutionOption("inprocessing-root-work", ValueKind.LONG)
        /** Failed-literal probe work ceiling. */
        val inprocessingProbeWork: ExecutionOption<Long> = ExecutionOption("inprocessing-probe-work", ValueKind.LONG)
        /** Subsumption preparation and matching work ceiling. */
        val inprocessingSubsumeWork: ExecutionOption<Long> = ExecutionOption(
            "inprocessing-subsume-work", ValueKind.LONG,
        )
        /** Vivification work ceiling. */
        val inprocessingVivifyWork: ExecutionOption<Long> = ExecutionOption("inprocessing-vivify-work", ValueKind.LONG)
        /** Non-inprocessing work between safe-root opportunities; zero disables the trigger. */
        val inprocessingInterval: ExecutionOption<Long> = ExecutionOption("inprocessing-interval", ValueKind.LONG)
        /** Learned-clause growth between safe-root opportunities; zero disables the trigger. */
        val inprocessingGrowth: ExecutionOption<Int> = ExecutionOption("inprocessing-growth", ValueKind.INT)
        /** Non-inprocessing work required after a simplification attempt. */
        val inprocessingCooldown: ExecutionOption<Long> = ExecutionOption("inprocessing-cooldown", ValueKind.LONG)

        /** The `lp-objective-cone` execution setting. */
        val lpObjectiveCone: ExecutionOption<Boolean> = ExecutionOption("lp-objective-cone", ValueKind.BOOLEAN)

        /** The `lp-auto-off-reprobe` execution setting. */
        val lpAutoOffReprobe: ExecutionOption<Boolean> = ExecutionOption("lp-auto-off-reprobe", ValueKind.BOOLEAN)

        /** The `lp-knapsack-lagrangian` execution setting. */
        val lpKnapsackLagrangian: ExecutionOption<Boolean> =
            ExecutionOption("lp-knapsack-lagrangian", ValueKind.BOOLEAN)

        /** The `lp-component-split` execution setting. */
        val lpComponentSplit: ExecutionOption<Boolean> = ExecutionOption("lp-component-split", ValueKind.BOOLEAN)

        /** The `lp-component-ownership` execution setting. */
        val lpComponentOwnership: ExecutionOption<Boolean> =
            ExecutionOption("lp-component-ownership", ValueKind.BOOLEAN)

        /** The `lp-bound-max-pivots` execution setting. */
        val lpBoundMaxPivots: ExecutionOption<Int> = ExecutionOption("lp-bound-max-pivots", ValueKind.INT)

        /** The `lp-adaptive-work` execution setting. */
        val lpAdaptiveWork: ExecutionOption<Boolean> = ExecutionOption("lp-adaptive-work", ValueKind.BOOLEAN)

        /** The `lp-branching` execution setting. */
        val lpBranching: ExecutionOption<Boolean> = ExecutionOption("lp-branching", ValueKind.BOOLEAN)

        /** The `lp-directional-gains` execution setting. */
        val lpDirectionalGains: ExecutionOption<Boolean> = ExecutionOption("lp-directional-gains", ValueKind.BOOLEAN)

        /** The `lp-variable-shaving` execution setting. */
        val lpVariableShaving: ExecutionOption<Boolean> = ExecutionOption("lp-variable-shaving", ValueKind.BOOLEAN)

        /** The `lp-prune-hulls` execution setting. */
        val lpPruneHulls: ExecutionOption<Boolean> = ExecutionOption("lp-prune-hulls", ValueKind.BOOLEAN)

        /** The `lp-root-cuts` execution setting. */
        val lpRootCuts: ExecutionOption<Boolean> = ExecutionOption("lp-root-cuts", ValueKind.BOOLEAN)

        /** The `pb-learning` execution setting. */
        val pbLearning: ExecutionOption<Boolean> = ExecutionOption("pb-learning", ValueKind.BOOLEAN)

        /** The `integer-row-learning` execution setting. */
        val integerRowLearning: ExecutionOption<Boolean> = ExecutionOption("integer-row-learning", ValueKind.BOOLEAN)
        /** Collect bounded propagation and explanation value diagnostics. */
        val propagationAttribution: ExecutionOption<Boolean> =
            ExecutionOption("propagation-attribution", ValueKind.BOOLEAN)
        /** Retry bounded historical Table explanations. */
        val historicalTableReasons: ExecutionOption<Boolean> =
            ExecutionOption("historical-table-reasons", ValueKind.BOOLEAN)

        /** The `pb-objective-cutoff` execution setting. */
        val pbObjectiveCutoff: ExecutionOption<Boolean> = ExecutionOption("pb-objective-cutoff", ValueKind.BOOLEAN)

        /** The `objective-guided-values` execution setting. */
        val objectiveGuidedValues: ExecutionOption<Boolean> =
            ExecutionOption("objective-guided-values", ValueKind.BOOLEAN)

        /** The `var-selector` execution setting. */
        val variableSelector: ExecutionOption<VarSelectorKind> =
            ExecutionOption("var-selector", ValueKind.VARIABLE_SELECTOR)

        /** The `val-selector` execution setting. */
        val valueSelector: ExecutionOption<ValSelectorKind> = ExecutionOption("val-selector", ValueKind.VALUE_SELECTOR)

        /** The `seed` execution setting. */
        val seed: ExecutionOption<Long> = ExecutionOption("seed", ValueKind.LONG)

        /** The `dry-run-solver` execution setting. */
        val dryRunSolver: ExecutionOption<Boolean> = ExecutionOption("dry-run-solver", ValueKind.BOOLEAN)

        /** The `noise` execution setting. */
        val noise: ExecutionOption<Double> = ExecutionOption("noise", ValueKind.DOUBLE)

        /** The `cb` execution setting. */
        val cb: ExecutionOption<Double> = ExecutionOption("cb", ValueKind.DOUBLE)

        /** The `skew-alpha` execution setting. */
        val skewAlpha: ExecutionOption<Double> = ExecutionOption("skew-alpha", ValueKind.DOUBLE)

        /** The `initial-temp` execution setting. */
        val initialTemp: ExecutionOption<Double> = ExecutionOption("initial-temp", ValueKind.DOUBLE)

        /** The `cooling-rate` execution setting. */
        val coolingRate: ExecutionOption<Double> = ExecutionOption("cooling-rate", ValueKind.DOUBLE)

        /** The `min-temp` execution setting. */
        val minTemp: ExecutionOption<Double> = ExecutionOption("min-temp", ValueKind.DOUBLE)

        /** The `smooth-prob` execution setting. */
        val smoothProb: ExecutionOption<Double> = ExecutionOption("smooth-prob", ValueKind.DOUBLE)

        /** The `smooth-factor` execution setting. */
        val smoothFactor: ExecutionOption<Double> = ExecutionOption("smooth-factor", ValueKind.DOUBLE)

        /** The `tabu-tenure` execution setting. */
        val tabuTenure: ExecutionOption<Int> = ExecutionOption("tabu-tenure", ValueKind.INT)

        /** The `sources` execution setting. */
        val sources: ExecutionOption<List<AxisToken>> = ExecutionOption("sources", ValueKind.SOURCES)

        /** The `scoring` execution setting. */
        val scoring: ExecutionOption<List<AxisToken>> = ExecutionOption("scoring", ValueKind.SCALAR_AXIS)

        /** The `acceptance` execution setting. */
        val acceptance: ExecutionOption<List<AxisToken>> = ExecutionOption("acceptance", ValueKind.SCALAR_AXIS)

        /** The `restart` execution setting. */
        val restart: ExecutionOption<List<AxisToken>> = ExecutionOption("restart", ValueKind.SCALAR_AXIS)

        /** The `strategy` execution setting. */
        val strategy: ExecutionOption<String> = ExecutionOption("strategy", ValueKind.STRING)

        /** The `arm` execution setting. */
        val localSearchArm: ExecutionOption<String> = ExecutionOption("arm", ValueKind.STRING)

        /** Explicit local-search objective policies, cycled across the selected worker slots. */
        val objectiveGuidance: ExecutionOption<String> = ExecutionOption("objective-guidance", ValueKind.STRING)

        /** The `lambda` execution setting. */
        val lambda: ExecutionOption<Double> = ExecutionOption("lambda", ValueKind.DOUBLE)

        /** The `arms` execution setting. */
        val arms: ExecutionOption<Int> = ExecutionOption("arms", ValueKind.INT)

        /** The `ls` execution setting. */
        val localSearchArms: ExecutionOption<Int> = ExecutionOption("ls", ValueKind.INT)

        /** The `bt` execution setting. */
        val backtrackArms: ExecutionOption<Int> = ExecutionOption("bt", ValueKind.INT)

        /** The `clause-share-lbd` execution setting. */
        val clauseShareLbd: ExecutionOption<Int> = ExecutionOption("clause-share-lbd", ValueKind.INT)

        /** The `clause-share-len` execution setting. */
        val clauseShareLen: ExecutionOption<Int> = ExecutionOption("clause-share-len", ValueKind.INT)

        /** The `slice-work` execution setting. */
        val sliceWork: ExecutionOption<Long> = ExecutionOption("slice-work", ValueKind.LONG)

        /** The `slice-max-work` execution setting. */
        val sliceMaxWork: ExecutionOption<Long> = ExecutionOption("slice-max-work", ValueKind.LONG)

        /** The `slice-growth` execution setting. */
        val sliceGrowth: ExecutionOption<Double> = ExecutionOption("slice-growth", ValueKind.DOUBLE)

        /** Opt-in single-lane portfolio diagnostic work allowance. */
        val portfolioWork: ExecutionOption<Long> = ExecutionOption("portfolio-work", ValueKind.LONG)

        /** Enable bounded sharing-memory observations independently of scheduling. */
        val sharingMemory: ExecutionOption<Boolean> = ExecutionOption("sharing-memory", ValueKind.BOOLEAN)

        /** Maximum charged visits per sharing-memory observation. */
        val sharingMemoryWork: ExecutionOption<Long> = ExecutionOption("sharing-memory-work", ValueKind.LONG)

        /** Maximum deduplication entries per sharing-memory observation. */
        val sharingMemoryScratch: ExecutionOption<Int> = ExecutionOption("sharing-memory-scratch", ValueKind.INT)

        /** Optional modeled byte limit per shared learned-clause pool. */
        val sharingMemoryBytes: ExecutionOption<Long> = ExecutionOption("sharing-memory-bytes", ValueKind.LONG)

        /** Allow the curated bounded core worker before the first incumbent. Default false. */
        val coreEarly: ExecutionOption<Boolean> = ExecutionOption("core-early", ValueKind.BOOLEAN)

        /** Opt-in general-integer/continuous admission for bounded numerical companions. */
        val companionIntegers: ExecutionOption<Boolean> = ExecutionOption("companion-integers", ValueKind.BOOLEAN)

        /** Defer finite numerical companion queries until a checked pool incumbent exists. */
        val companionAfterIncumbent: ExecutionOption<Boolean> =
            ExecutionOption("companion-after-incumbent", ValueKind.BOOLEAN)

        /** Active companion lifetime in milliseconds, contained by the ordinary solve deadline. */
        val companionActiveMillis: ExecutionOption<Long> = ExecutionOption("companion-active-ms", ValueKind.LONG)

        /** Stream physical phase and lifecycle progress independently of deterministic work diagnostics. */
        val portfolioProgress: ExecutionOption<Boolean> = ExecutionOption("portfolio-progress", ValueKind.BOOLEAN)

        /** Select bounded private objective-core refinement. Default [CoreRefinementMode.NONE]. */
        val coreRefinement: ExecutionOption<CoreRefinementMode> =
            ExecutionOption("core-refinement", ValueKind.CORE_REFINEMENT)

        /** The `phase-retention` execution setting. */
        val phaseRetention: ExecutionOption<Double> = ExecutionOption("phase-retention", ValueKind.DOUBLE)

        /** The `reseed-stale-threshold` execution setting. */
        val reseedStaleThreshold: ExecutionOption<Int> = ExecutionOption("reseed-stale-threshold", ValueKind.INT)

        /** The `ls-residuals` execution setting. */
        val lsResiduals: ExecutionOption<Boolean> = ExecutionOption("ls-residuals", ValueKind.BOOLEAN)

        /** The `ls-phase-times` execution setting. */
        val lsPhaseTimes: ExecutionOption<Boolean> = ExecutionOption("ls-phase-times", ValueKind.BOOLEAN)

        /** The `bt-arm` execution setting. */
        val backtrackArmsByLabel: ExecutionOption<List<String>> = ExecutionOption("bt-arm", ValueKind.LABELS)

        internal val byId by lazy {
            listOf(
                dryRunPresolve, affinePivotOrder, presolvePreserveStructure, presolveStructuralImpact,
                presolveAbortFraction, presolveMaxRounds,
                presolveProbePerVar, presolveProbeTotal,
                nodeLimit, openWorkLimit, sourceLpWorkLimit, sourceLpAllocationLimit, sourceLpOperations, sourceCuts,
                sourcePointCuts, sourceAggregateCuts, sourceIncumbentCompletion, sourceRefutationReuse,
                sourceRefutationPropagation,
                sourceRefutationResolution,
                nraWorkLimit, nraDenseCoefficients, nraMatrixCells, niaCandidates, niaMoves,
                niaCellRefinement, niaCellReuse, niaCellSteering, niaIntegerRoots, niaResidues,
                maxFlips, maxInstructions, alnsIterations, sharedRestart, openBranching,
                openHintFlips, openHintMinSplits,
                maxDecisions, luby, adaptiveRestart, emaRestart, modeSwitchingRestart,
                phaseSaving, targetPhasing, solutionPhasing, rephaseInterval, maxLearned,
                learnedMemoryBytes, learnedMemoryWork, learnedMemoryScratchBytes,
                lbdGlue, tieredDb, midLbd, vivification, vivifyBatch,
                subsumption, subsumeBatch, inprocessingCadence, rootSimplification, rootSimplifyBatch,
                rootRebuildCaptureVisits,
                rootProbeBatch, rootProbeWork, inprocessingWork, inprocessingRootWork, inprocessingProbeWork,
                inprocessingSubsumeWork, inprocessingVivifyWork, inprocessingInterval, inprocessingGrowth,
                inprocessingCooldown, lpObjectiveCone, lpAutoOffReprobe,
                lpKnapsackLagrangian, lpComponentSplit, lpComponentOwnership, lpBoundMaxPivots, lpAdaptiveWork,
                lpBranching, lpDirectionalGains,
                lpVariableShaving, lpPruneHulls, lpRootCuts, pbLearning, integerRowLearning,
                propagationAttribution, historicalTableReasons, pbObjectiveCutoff,
                objectiveGuidedValues, variableSelector, valueSelector, seed, dryRunSolver,
                noise, cb, skewAlpha, initialTemp, coolingRate,
                minTemp, smoothProb, smoothFactor, tabuTenure, sources,
                scoring, acceptance, restart, strategy, localSearchArm, objectiveGuidance,
                lambda, arms, localSearchArms, backtrackArms, clauseShareLbd,
                clauseShareLen, sliceWork, sliceMaxWork, sliceGrowth, portfolioWork, coreEarly, companionIntegers,
                sharingMemory, sharingMemoryWork, sharingMemoryScratch, sharingMemoryBytes,
                coreRefinement, companionAfterIncumbent, companionActiveMillis, portfolioProgress,
                phaseRetention, reseedStaleThreshold, lsResiduals, lsPhaseTimes, backtrackArmsByLabel,
            ).associateBy { it.id }
        }
    }
}

/** One immutable typed execution setting, created by invoking an [ExecutionOption]. */
class ExecutionSetting<T> internal constructor(
    internal val option: ExecutionOption<T>,
    internal val value: OptionValue,
)

/**
 * Immutable execution configuration. Repeated tuning settings use the last value. The same options
 * can resolve independent plans; each plan owns its consumption view and each worker owns its selectors.
 * Source execution resolves resource settings once into the containing invocation's ledgers.
 */
class ExecutionOptions private constructor(
    private val values: Map<String, OptionValue>,
    private val malformed: String?,
    internal val originalPairs: List<String>,
    internal val numericalCompletion: Boolean,
) {
    /** Capture typed settings. Library callers need no frontend strings or consumption ordering. */
    constructor(vararg settings: ExecutionSetting<*>) : this(
        captureSettings(settings),
        null,
        emptyList(),
        false,
    )

    /** Restrict finite workers to numerical continuous completion without changing tuning or allowances. */
    fun withNumericalCompletion(): ExecutionOptions = ExecutionOptions(values, malformed, originalPairs, true)

    /** Whether an arm count or engine-mix count was supplied explicitly. */
    val explicitArms: Boolean get() = listOf("arms", "ls", "bt").any { it in values }

    internal fun reader(): ExecutionOptionReader {
        malformed?.let { pipelineConfigError(it) }
        return CapturedExecutionOptionReader(values.toMutableMap())
    }

    internal fun without(vararg keys: String): ExecutionOptions = ExecutionOptions(
        values - keys.toSet(),
        malformed,
        originalPairs.filter { it.substringBefore('=') !in keys },
        numericalCompletion,
    )

    internal fun overriddenBy(other: ExecutionOptions): ExecutionOptions = ExecutionOptions(
        values + other.values,
        other.malformed ?: malformed,
        originalPairs + other.originalPairs,
        numericalCompletion || other.numericalCompletion,
    )

    internal fun validateKnown() {
        val reader = reader()
        for (key in values.keys) {
            val option = ExecutionOption.byId[key] ?: continue
            reader[option]
        }
        reader.finish("source", ExecutionOption.byId.keys.joinToString())
    }

    /** Frontend syntax capture for immutable execution settings. */
    companion object {
        /**
         * Capture repeatable frontend `key=value` parameters once. Invalid values retain their
         * diagnostic until the selected route consumes them, preserving dry-run and annotation rules.
         */
        fun parse(pairs: List<String>): ExecutionOptions {
            val captured = pairs.toList()
            val raw = linkedMapOf<String, String>()
            val conflicting = mutableSetOf<String>()
            for (pair in captured) {
                val eq = pair.indexOf('=')
                if (eq <= 0) {
                    return ExecutionOptions(
                    emptyMap(),
                    "malformed engine param `$pair`; expected key=value",
                    captured,
                    false,
                )
                }
                val key = pair.take(eq)
                val value = pair.substring(eq + 1)
                if (ExecutionOption.byId[key]?.allowance == true && raw[key]?.lowercase()?.let {
                        it != value.lowercase() &&
                            (it.toLongOrNull() == null || it.toLongOrNull() != value.toLongOrNull())
                    } == true
                ) {
                    conflicting += key
                }
                raw[key] = value
            }
            val values = raw.mapValues { (key, value) ->
                val option = ExecutionOption.byId[key]
                if (key in conflicting) {
                    OptionValue.Invalid("conflicting engine param `$key`", configuration = true)
                } else if (option?.allowance == true && value.lowercase() == "auto") {
                    OptionValue.Automatic
                } else if (option == null) {
                    OptionValue.Valid(Unit)
                } else {
                    try {
                    OptionValue.Valid(option.decode(EngineParams(key, value)))
                } catch (failure: PipelineConfigException) {
                    OptionValue.Invalid(requireNotNull(failure.message), configuration = true)
                } catch (failure: IllegalArgumentException) {
                    OptionValue.Invalid(requireNotNull(failure.message), configuration = false)
                }
                }
            }
            return ExecutionOptions(values, null, captured, false)
        }

        private fun captureSettings(settings: Array<out ExecutionSetting<*>>): Map<String, OptionValue> {
            val values = linkedMapOf<String, OptionValue>()
            for (setting in settings) {
                val previous = values[setting.option.id]
                val value = setting.value
                values[setting.option.id] = if (setting.option.allowance && previous != null &&
                    (previous !is OptionValue.Valid || value !is OptionValue.Valid || previous.value != value.value)
                ) {
                    OptionValue.Invalid("conflicting engine param `${setting.option.id}`", configuration = true)
                } else {
                    value
                }
            }
            return values
        }
    }
}

internal sealed class OptionValue {
    data object Automatic : OptionValue()
    class Valid(val value: Any?) : OptionValue()
    class Invalid(val message: String, val configuration: Boolean) : OptionValue()
}

internal abstract class ExecutionOptionReader {
    abstract operator fun <T> get(option: ExecutionOption<T>): T?
    open fun <T> defaulted(option: ExecutionOption<T>, default: T): T = get(option) ?: default
    abstract fun finish(engine: String, valid: String)
}

private class CapturedExecutionOptionReader(private val values: MutableMap<String, OptionValue>) :
    ExecutionOptionReader() {
    @Suppress("UNCHECKED_CAST")
    override operator fun <T> get(option: ExecutionOption<T>): T? = when (val entry = values.remove(option.id)) {
        null, OptionValue.Automatic -> null

        is OptionValue.Valid -> entry.value as T?

        is OptionValue.Invalid -> if (entry.configuration) {
            pipelineConfigError(entry.message)
        } else {
            throw IllegalArgumentException(entry.message)
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> defaulted(option: ExecutionOption<T>, default: T): T =
        if (values[option.id] is OptionValue.Valid) get(option) as T else get(option) ?: default

    override fun finish(engine: String, valid: String) {
        if (values.isNotEmpty()) {
            pipelineConfigError("unknown $engine engine param(s) ${values.keys.joinToString()}; valid keys: $valid")
        }
    }
}
