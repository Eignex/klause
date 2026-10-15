package com.eignex.klause.solver.pipeline

import com.eignex.klause.localsearch.strategy.AxisEdits
import com.eignex.klause.localsearch.strategy.AxisToken

/** A typed setting accepted by finite execution. Values describe policy; workers own live heuristic state. */
sealed class ExecutionOption<T> private constructor(internal val id: String) {
    /** Bind this option to a value without command-line syntax. */
    operator fun invoke(value: T): ExecutionSetting<T> = ExecutionSetting(this, capture(value))

    internal open fun capture(value: T): T = value

    internal abstract fun decode(params: EngineParams): T?

    /** The `max-decisions` execution setting. */
    data object MaxDecisions : ExecutionOption<Long>("max-decisions") {
        override fun decode(params: EngineParams): Long? = params.long(id)
    }

    /** The `luby` execution setting. */
    data object Luby : ExecutionOption<Long>("luby") {
        override fun decode(params: EngineParams): Long? = params.long(id)
    }

    /** The `adaptive-restart` execution setting. */
    data object AdaptiveRestart : ExecutionOption<Boolean>("adaptive-restart") {
        override fun decode(params: EngineParams): Boolean? = params.bool(id)
    }

    /** The `ema-restart` execution setting. */
    data object EmaRestart : ExecutionOption<Boolean>("ema-restart") {
        override fun decode(params: EngineParams): Boolean? = params.bool(id)
    }

    /** The `mode-switching-restart` execution setting. */
    data object ModeSwitchingRestart : ExecutionOption<Boolean>("mode-switching-restart") {
        override fun decode(params: EngineParams): Boolean? = params.bool(id)
    }

    /** The `phase-saving` execution setting. */
    data object PhaseSaving : ExecutionOption<Boolean>("phase-saving") {
        override fun decode(params: EngineParams): Boolean? = params.bool(id)
    }

    /** The `target-phasing` execution setting. */
    data object TargetPhasing : ExecutionOption<Boolean>("target-phasing") {
        override fun decode(params: EngineParams): Boolean? = params.bool(id)
    }

    /** The `solution-phasing` execution setting. */
    data object SolutionPhasing : ExecutionOption<Boolean>("solution-phasing") {
        override fun decode(params: EngineParams): Boolean? = params.bool(id)
    }

    /** The `rephase-interval` execution setting. */
    data object RephaseInterval : ExecutionOption<Long>("rephase-interval") {
        override fun decode(params: EngineParams): Long? = params.long(id)
    }

    /** The `max-learned` execution setting. */
    data object MaxLearned : ExecutionOption<Int>("max-learned") {
        override fun decode(params: EngineParams): Int? = params.int(id)
    }

    /** The `lbd-glue` execution setting. */
    data object LbdGlue : ExecutionOption<Int>("lbd-glue") {
        override fun decode(params: EngineParams): Int? = params.int(id)
    }

    /** The `tiered-db` execution setting. */
    data object TieredDb : ExecutionOption<Boolean>("tiered-db") {
        override fun decode(params: EngineParams): Boolean? = params.bool(id)
    }

    /** The `mid-lbd` execution setting. */
    data object MidLbd : ExecutionOption<Int>("mid-lbd") {
        override fun decode(params: EngineParams): Int? = params.int(id)
    }

    /** The `vivification` execution setting. */
    data object Vivification : ExecutionOption<Boolean>("vivification") {
        override fun decode(params: EngineParams): Boolean? = params.bool(id)
    }

    /** The `vivify-batch` execution setting. */
    data object VivifyBatch : ExecutionOption<Int>("vivify-batch") {
        override fun decode(params: EngineParams): Int? = params.int(id)
    }

    /** The `subsumption` execution setting. */
    data object Subsumption : ExecutionOption<Boolean>("subsumption") {
        override fun decode(params: EngineParams): Boolean? = params.bool(id)
    }

    /** The `subsume-batch` execution setting. */
    data object SubsumeBatch : ExecutionOption<Int>("subsume-batch") {
        override fun decode(params: EngineParams): Int? = params.int(id)
    }

    /** The `inprocessing-cadence` execution setting. */
    data object InprocessingCadence : ExecutionOption<Int>("inprocessing-cadence") {
        override fun decode(params: EngineParams): Int? = params.int(id)
    }

    /** The `lp-objective-cone` execution setting. */
    data object LpObjectiveCone : ExecutionOption<Boolean>("lp-objective-cone") {
        override fun decode(params: EngineParams): Boolean? = params.bool(id)
    }

    /** The `lp-auto-off-reprobe` execution setting. */
    data object LpAutoOffReprobe : ExecutionOption<Boolean>("lp-auto-off-reprobe") {
        override fun decode(params: EngineParams): Boolean? = params.bool(id)
    }

    /** The `lp-knapsack-lagrangian` execution setting. */
    data object LpKnapsackLagrangian : ExecutionOption<Boolean>("lp-knapsack-lagrangian") {
        override fun decode(params: EngineParams): Boolean? = params.bool(id)
    }

    /** The `lp-component-split` execution setting. */
    data object LpComponentSplit : ExecutionOption<Boolean>("lp-component-split") {
        override fun decode(params: EngineParams): Boolean? = params.bool(id)
    }

    /** The `lp-bound-max-pivots` execution setting. */
    data object LpBoundMaxPivots : ExecutionOption<Int>("lp-bound-max-pivots") {
        override fun decode(params: EngineParams): Int? = params.int(id)
    }

    /** The `lp-adaptive-work` execution setting. */
    data object LpAdaptiveWork : ExecutionOption<Boolean>("lp-adaptive-work") {
        override fun decode(params: EngineParams): Boolean? = params.bool(id)
    }

    /** The `lp-branching` execution setting. */
    data object LpBranching : ExecutionOption<Boolean>("lp-branching") {
        override fun decode(params: EngineParams): Boolean? = params.bool(id)
    }

    /** The `lp-variable-shaving` execution setting. */
    data object LpVariableShaving : ExecutionOption<Boolean>("lp-variable-shaving") {
        override fun decode(params: EngineParams): Boolean? = params.bool(id)
    }

    /** The `lp-prune-hulls` execution setting. */
    data object LpPruneHulls : ExecutionOption<Boolean>("lp-prune-hulls") {
        override fun decode(params: EngineParams): Boolean? = params.bool(id)
    }

    /** The `lp-root-cuts` execution setting. */
    data object LpRootCuts : ExecutionOption<Boolean>("lp-root-cuts") {
        override fun decode(params: EngineParams): Boolean? = params.bool(id)
    }

    /** The `pb-learning` execution setting. */
    data object PbLearning : ExecutionOption<Boolean>("pb-learning") {
        override fun decode(params: EngineParams): Boolean? = params.bool(id)
    }

    /** The `pb-objective-cutoff` execution setting. */
    data object PbObjectiveCutoff : ExecutionOption<Boolean>("pb-objective-cutoff") {
        override fun decode(params: EngineParams): Boolean? = params.bool(id)
    }

    /** The `objective-guided-values` execution setting. */
    data object ObjectiveGuidedValues : ExecutionOption<Boolean>("objective-guided-values") {
        override fun decode(params: EngineParams): Boolean? = params.bool(id)
    }

    /** The `var-selector` execution setting. */
    data object VariableSelector : ExecutionOption<VarSelectorKind>("var-selector") {
        override fun decode(params: EngineParams): VarSelectorKind? = params.varSelectorKind(id)
    }

    /** The `val-selector` execution setting. */
    data object ValueSelector : ExecutionOption<ValSelectorKind>("val-selector") {
        override fun decode(params: EngineParams): ValSelectorKind? = params.valSelectorKind(id)
    }

    /** The `seed` execution setting. */
    data object Seed : ExecutionOption<Long>("seed") {
        override fun decode(params: EngineParams): Long? = params.long(id)
    }

    /** The `dry-run-solver` execution setting. */
    data object DryRunSolver : ExecutionOption<Boolean>("dry-run-solver") {
        override fun decode(params: EngineParams): Boolean? = params.bool(id)
    }

    /** The `noise` execution setting. */
    data object Noise : ExecutionOption<Double>("noise") {
        override fun decode(params: EngineParams): Double? = params.double(id)
    }

    /** The `cb` execution setting. */
    data object Cb : ExecutionOption<Double>("cb") {
        override fun decode(params: EngineParams): Double? = params.double(id)
    }

    /** The `skew-alpha` execution setting. */
    data object SkewAlpha : ExecutionOption<Double>("skew-alpha") {
        override fun decode(params: EngineParams): Double? = params.double(id)
    }

    /** The `initial-temp` execution setting. */
    data object InitialTemp : ExecutionOption<Double>("initial-temp") {
        override fun decode(params: EngineParams): Double? = params.double(id)
    }

    /** The `cooling-rate` execution setting. */
    data object CoolingRate : ExecutionOption<Double>("cooling-rate") {
        override fun decode(params: EngineParams): Double? = params.double(id)
    }

    /** The `min-temp` execution setting. */
    data object MinTemp : ExecutionOption<Double>("min-temp") {
        override fun decode(params: EngineParams): Double? = params.double(id)
    }

    /** The `smooth-prob` execution setting. */
    data object SmoothProb : ExecutionOption<Double>("smooth-prob") {
        override fun decode(params: EngineParams): Double? = params.double(id)
    }

    /** The `smooth-factor` execution setting. */
    data object SmoothFactor : ExecutionOption<Double>("smooth-factor") {
        override fun decode(params: EngineParams): Double? = params.double(id)
    }

    /** The `tabu-tenure` execution setting. */
    data object TabuTenure : ExecutionOption<Int>("tabu-tenure") {
        override fun decode(params: EngineParams): Int? = params.int(id)
    }

    /** The `sources` execution setting. */
    data object Sources : ExecutionOption<List<AxisToken>>("sources") {
        override fun capture(value: List<AxisToken>): List<AxisToken> = value.toList()
        override fun decode(params: EngineParams): List<AxisToken>? = params.string(id)?.let { AxisEdits.tokens(it) }
    }

    /** The `scoring` execution setting. */
    data object Scoring : ExecutionOption<List<AxisToken>>("scoring") {
        override fun capture(value: List<AxisToken>): List<AxisToken> = scalarTokens(value.toList(), "scoring")
        override fun decode(params: EngineParams): List<AxisToken>? =
            params.string(id)?.let { scalarTokens(it, "scoring") }
    }

    /** The `acceptance` execution setting. */
    data object Acceptance : ExecutionOption<List<AxisToken>>("acceptance") {
        override fun capture(value: List<AxisToken>): List<AxisToken> = scalarTokens(value.toList(), "acceptance")
        override fun decode(params: EngineParams): List<AxisToken>? =
            params.string(id)?.let { scalarTokens(it, "acceptance") }
    }

    /** The `restart` execution setting. */
    data object Restart : ExecutionOption<List<AxisToken>>("restart") {
        override fun capture(value: List<AxisToken>): List<AxisToken> = scalarTokens(value.toList(), "restart")
        override fun decode(params: EngineParams): List<AxisToken>? =
            params.string(id)?.let { scalarTokens(it, "restart") }
    }

    /** The `strategy` execution setting. */
    data object Strategy : ExecutionOption<String>("strategy") {
        override fun decode(params: EngineParams): String? = params.string(id)
    }

    /** The `arm` execution setting. */
    data object LocalSearchArm : ExecutionOption<String>("arm") {
        override fun decode(params: EngineParams): String? = params.string(id)
    }

    /** The `lambda` execution setting. */
    data object Lambda : ExecutionOption<Double>("lambda") {
        override fun decode(params: EngineParams): Double? = params.double(id)
    }

    /** The `arms` execution setting. */
    data object Arms : ExecutionOption<Int>("arms") {
        override fun decode(params: EngineParams): Int? = params.int(id)
    }

    /** The `ls` execution setting. */
    data object LocalSearchArms : ExecutionOption<Int>("ls") {
        override fun decode(params: EngineParams): Int? = params.int(id)
    }

    /** The `bt` execution setting. */
    data object BacktrackArms : ExecutionOption<Int>("bt") {
        override fun decode(params: EngineParams): Int? = params.int(id)
    }

    /** The `clause-share-lbd` execution setting. */
    data object ClauseShareLbd : ExecutionOption<Int>("clause-share-lbd") {
        override fun decode(params: EngineParams): Int? = params.int(id)
    }

    /** The `clause-share-len` execution setting. */
    data object ClauseShareLen : ExecutionOption<Int>("clause-share-len") {
        override fun decode(params: EngineParams): Int? = params.int(id)
    }

    /** The `slice-work` execution setting. */
    data object SliceWork : ExecutionOption<Long>("slice-work") {
        override fun decode(params: EngineParams): Long? = params.long(id)
    }

    /** The `slice-max-work` execution setting. */
    data object SliceMaxWork : ExecutionOption<Long>("slice-max-work") {
        override fun decode(params: EngineParams): Long? = params.long(id)
    }

    /** The `slice-growth` execution setting. */
    data object SliceGrowth : ExecutionOption<Double>("slice-growth") {
        override fun decode(params: EngineParams): Double? = params.double(id)
    }

    /** The `phase-retention` execution setting. */
    data object PhaseRetention : ExecutionOption<Double>("phase-retention") {
        override fun decode(params: EngineParams): Double? = params.double(id)
    }

    /** The `reseed-stale-threshold` execution setting. */
    data object ReseedStaleThreshold : ExecutionOption<Int>("reseed-stale-threshold") {
        override fun decode(params: EngineParams): Int? = params.int(id)
    }

    /** The `ls-residuals` execution setting. */
    data object LsResiduals : ExecutionOption<Boolean>("ls-residuals") {
        override fun decode(params: EngineParams): Boolean? = params.bool(id)
    }

    /** The `ls-phase-times` execution setting. */
    data object LsPhaseTimes : ExecutionOption<Boolean>("ls-phase-times") {
        override fun decode(params: EngineParams): Boolean? = params.bool(id)
    }

    /** The `bt-arm` execution setting. */
    data object BacktrackArmsByLabel : ExecutionOption<List<String>>("bt-arm") {
        override fun capture(value: List<String>): List<String> = value.toList()
        override fun decode(params: EngineParams): List<String>? = params.string(id)?.let { raw ->
            raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        }
    }

    /** The `dry-run-presolve` execution setting. */
    data object DryRunPresolve : ExecutionOption<Boolean>("dry-run-presolve") {
        override fun decode(params: EngineParams): Boolean? = params.bool(id)
    }

    internal companion object {
        val all: List<ExecutionOption<*>> = listOf(
            MaxDecisions, Luby, AdaptiveRestart, EmaRestart, ModeSwitchingRestart,
            PhaseSaving, TargetPhasing, SolutionPhasing, RephaseInterval, MaxLearned,
            LbdGlue, TieredDb, MidLbd, Vivification, VivifyBatch,
            Subsumption, SubsumeBatch, InprocessingCadence, LpObjectiveCone, LpAutoOffReprobe,
            LpKnapsackLagrangian, LpComponentSplit, LpBoundMaxPivots, LpAdaptiveWork, LpBranching,
            LpVariableShaving, LpPruneHulls, LpRootCuts, PbLearning, PbObjectiveCutoff,
            ObjectiveGuidedValues, VariableSelector, ValueSelector, Seed, DryRunSolver,
            Noise, Cb, SkewAlpha, InitialTemp, CoolingRate,
            MinTemp, SmoothProb, SmoothFactor, TabuTenure, Sources,
            Scoring, Acceptance, Restart, Strategy, LocalSearchArm,
            Lambda, Arms, LocalSearchArms, BacktrackArms, ClauseShareLbd,
            ClauseShareLen, SliceWork, SliceMaxWork, SliceGrowth, PhaseRetention,
            ReseedStaleThreshold, LsResiduals, LsPhaseTimes, BacktrackArmsByLabel, DryRunPresolve,
        )
        val byId = all.associateBy { it.id }
    }
}

/** One immutable typed execution setting, created by invoking an [ExecutionOption]. */
class ExecutionSetting<T> internal constructor(internal val option: ExecutionOption<T>, internal val value: T)

/**
 * Immutable finite execution configuration. Repeated settings use the last value. The same options
 * can resolve independent plans; each plan and worker owns its mutable consumption and selectors.
 * Invocation-wide resource allowances remain separate request arguments.
 */
class ExecutionOptions private constructor(
    private val values: Map<String, OptionValue>,
    private val malformed: String?,
    internal val originalPairs: List<String>,
) {
    /** Capture typed settings. Library callers need no frontend strings or consumption ordering. */
    constructor(vararg settings: ExecutionSetting<*>) : this(
        settings.associate { it.option.id to OptionValue.Valid(it.value) }, null, emptyList(),
    )

    /** Whether an arm count or engine-mix count was supplied explicitly. */
    val explicitArms: Boolean get() = listOf("arms", "ls", "bt").any { it in values }

    internal fun reader(): ExecutionOptionReader {
        malformed?.let { pipelineConfigError(it) }
        return CapturedExecutionOptionReader(values.toMutableMap())
    }

    companion object {
        /**
         * Capture repeatable frontend `key=value` parameters once. Invalid values retain their
         * diagnostic until the selected route consumes them, preserving dry-run and annotation rules.
         */
        fun parse(pairs: List<String>): ExecutionOptions {
            val captured = pairs.toList()
            val raw = linkedMapOf<String, String>()
            for (pair in captured) {
                val eq = pair.indexOf('=')
                if (eq <= 0) return ExecutionOptions(
                    emptyMap(), "malformed engine param `$pair`; expected key=value", captured,
                )
                raw[pair.take(eq)] = pair.substring(eq + 1)
            }
            val values = raw.mapValues { (key, value) ->
                val option = ExecutionOption.byId[key]
                if (option == null) OptionValue.Valid(value) else try {
                    OptionValue.Valid(option.decode(EngineParams(listOf("$key=$value"))))
                } catch (failure: PipelineConfigException) {
                    OptionValue.Invalid(requireNotNull(failure.message), configuration = true)
                } catch (failure: IllegalArgumentException) {
                    OptionValue.Invalid(requireNotNull(failure.message), configuration = false)
                }
            }
            return ExecutionOptions(values, null, captured)
        }
    }
}

internal sealed class OptionValue {
    class Valid(val value: Any?) : OptionValue()
    class Invalid(val message: String, val configuration: Boolean) : OptionValue()
}

internal abstract class ExecutionOptionReader {
    abstract operator fun <T> get(option: ExecutionOption<T>): T?
    abstract fun finish(engine: String, valid: String)
}

private class CapturedExecutionOptionReader(
    private val values: MutableMap<String, OptionValue>,
) : ExecutionOptionReader() {
    @Suppress("UNCHECKED_CAST")
    override operator fun <T> get(option: ExecutionOption<T>): T? = when (val entry = values.remove(option.id)) {
        null -> null
        is OptionValue.Valid -> entry.value as T?
        is OptionValue.Invalid -> if (entry.configuration) pipelineConfigError(entry.message)
        else throw IllegalArgumentException(entry.message)
    }

    override fun finish(engine: String, valid: String) {
        if (values.isNotEmpty()) {
            pipelineConfigError("unknown $engine engine param(s) ${values.keys.joinToString()}; valid keys: $valid")
        }
    }
}
