package com.eignex.klause.solver.pipeline

import com.eignex.klause.localsearch.strategy.AxisEdits
import com.eignex.klause.localsearch.strategy.AxisToken

/** A typed setting accepted by finite execution. Values describe policy; workers own live heuristic state. */
class ExecutionOption<T> private constructor(internal val id: String, private val kind: ValueKind) {
    /** Bind this option to a value without command-line syntax. */
    @Suppress("UNCHECKED_CAST")
    operator fun invoke(value: T): ExecutionSetting<T> {
        val captured = if (value is List<*>) value.toList() else value
        val entry = try {
            if (kind == ValueKind.SCALAR_AXIS) scalarTokens(captured as List<AxisToken>, id)
            OptionValue.Valid(captured)
        } catch (failure: PipelineConfigException) {
            OptionValue.Invalid(requireNotNull(failure.message), configuration = true)
        }
        return ExecutionSetting(this, entry)
    }

    @Suppress("UNCHECKED_CAST")
    internal fun decode(params: EngineParams): T? = when (kind) {
        ValueKind.LONG -> params.long(id)
        ValueKind.INT -> params.int(id)
        ValueKind.DOUBLE -> params.double(id)
        ValueKind.BOOLEAN -> params.bool(id)
        ValueKind.STRING -> params.string(id)
        ValueKind.VARIABLE_SELECTOR -> params.varSelectorKind(id)
        ValueKind.VALUE_SELECTOR -> params.valSelectorKind(id)
        ValueKind.SOURCES -> params.string(id)?.let { AxisEdits.tokens(it) }
        ValueKind.SCALAR_AXIS -> params.string(id)?.let { scalarTokens(it, id) }
        ValueKind.LABELS -> params.string(id)?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
    } as T?

    private enum class ValueKind {
        LONG, INT, DOUBLE, BOOLEAN, STRING, VARIABLE_SELECTOR, VALUE_SELECTOR, SOURCES, SCALAR_AXIS, LABELS,
    }

    /** Finite tuning settings, separate from source models and invocation allowances. */
    companion object {
        /** The `max-decisions` execution setting. */
        val maxDecisions: ExecutionOption<Long> = ExecutionOption("max-decisions", ValueKind.LONG)

        /** The `luby` execution setting. */
        val luby: ExecutionOption<Long> = ExecutionOption("luby", ValueKind.LONG)

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

        /** The `lp-objective-cone` execution setting. */
        val lpObjectiveCone: ExecutionOption<Boolean> = ExecutionOption("lp-objective-cone", ValueKind.BOOLEAN)

        /** The `lp-auto-off-reprobe` execution setting. */
        val lpAutoOffReprobe: ExecutionOption<Boolean> = ExecutionOption("lp-auto-off-reprobe", ValueKind.BOOLEAN)

        /** The `lp-knapsack-lagrangian` execution setting. */
        val lpKnapsackLagrangian: ExecutionOption<Boolean> =
            ExecutionOption("lp-knapsack-lagrangian", ValueKind.BOOLEAN)

        /** The `lp-component-split` execution setting. */
        val lpComponentSplit: ExecutionOption<Boolean> = ExecutionOption("lp-component-split", ValueKind.BOOLEAN)

        /** The `lp-bound-max-pivots` execution setting. */
        val lpBoundMaxPivots: ExecutionOption<Int> = ExecutionOption("lp-bound-max-pivots", ValueKind.INT)

        /** The `lp-adaptive-work` execution setting. */
        val lpAdaptiveWork: ExecutionOption<Boolean> = ExecutionOption("lp-adaptive-work", ValueKind.BOOLEAN)

        /** The `lp-branching` execution setting. */
        val lpBranching: ExecutionOption<Boolean> = ExecutionOption("lp-branching", ValueKind.BOOLEAN)

        /** The `lp-variable-shaving` execution setting. */
        val lpVariableShaving: ExecutionOption<Boolean> = ExecutionOption("lp-variable-shaving", ValueKind.BOOLEAN)

        /** The `lp-prune-hulls` execution setting. */
        val lpPruneHulls: ExecutionOption<Boolean> = ExecutionOption("lp-prune-hulls", ValueKind.BOOLEAN)

        /** The `lp-root-cuts` execution setting. */
        val lpRootCuts: ExecutionOption<Boolean> = ExecutionOption("lp-root-cuts", ValueKind.BOOLEAN)

        /** The `pb-learning` execution setting. */
        val pbLearning: ExecutionOption<Boolean> = ExecutionOption("pb-learning", ValueKind.BOOLEAN)

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
                maxDecisions, luby, adaptiveRestart, emaRestart, modeSwitchingRestart,
                phaseSaving, targetPhasing, solutionPhasing, rephaseInterval, maxLearned,
                lbdGlue, tieredDb, midLbd, vivification, vivifyBatch,
                subsumption, subsumeBatch, inprocessingCadence, lpObjectiveCone, lpAutoOffReprobe,
                lpKnapsackLagrangian, lpComponentSplit, lpBoundMaxPivots, lpAdaptiveWork, lpBranching,
                lpVariableShaving, lpPruneHulls, lpRootCuts, pbLearning, pbObjectiveCutoff,
                objectiveGuidedValues, variableSelector, valueSelector, seed, dryRunSolver,
                noise, cb, skewAlpha, initialTemp, coolingRate,
                minTemp, smoothProb, smoothFactor, tabuTenure, sources,
                scoring, acceptance, restart, strategy, localSearchArm,
                lambda, arms, localSearchArms, backtrackArms, clauseShareLbd,
                clauseShareLen, sliceWork, sliceMaxWork, sliceGrowth, phaseRetention,
                reseedStaleThreshold, lsResiduals, lsPhaseTimes, backtrackArmsByLabel,
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
 * Immutable finite execution configuration. Repeated settings use the last value. The same options
 * can resolve independent plans; each plan owns its consumption view and each worker owns its selectors.
 * Invocation-wide resource allowances remain separate request arguments.
 */
class ExecutionOptions private constructor(
    private val values: Map<String, OptionValue>,
    private val malformed: String?,
    internal val originalPairs: List<String>,
) {
    /** Capture typed settings. Library callers need no frontend strings or consumption ordering. */
    constructor(vararg settings: ExecutionSetting<*>) : this(
        settings.associate { it.option.id to it.value }, null, emptyList(),
    )

    /** Whether an arm count or engine-mix count was supplied explicitly. */
    val explicitArms: Boolean get() = listOf("arms", "ls", "bt").any { it in values }

    internal fun reader(): ExecutionOptionReader {
        malformed?.let { pipelineConfigError(it) }
        return CapturedExecutionOptionReader(values.toMutableMap())
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
            for (pair in captured) {
                val eq = pair.indexOf('=')
                if (eq <= 0) return ExecutionOptions(
                    emptyMap(), "malformed engine param `$pair`; expected key=value", captured,
                )
                raw[pair.take(eq)] = pair.substring(eq + 1)
            }
            val values = raw.mapValues { (key, value) ->
                val option = ExecutionOption.byId[key]
                if (option == null) OptionValue.Valid(Unit) else try {
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
