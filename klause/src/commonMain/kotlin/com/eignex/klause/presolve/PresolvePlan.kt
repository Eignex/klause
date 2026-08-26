package com.eignex.klause.presolve

/**
 * Preset effort levels. Each level is a bundle of enabled [PresolveTiming] tiers and a
 * round-to-fixpoint cap. Per-pass overrides on [PresolveConfig] sit on top of the level.
 */
enum class PresolveEmphasis(
    /** Canonical token shown in `--presolve` / `--help` and error messages. */
    val id: String,
    /** Cost tiers this level lets run. */
    val timings: Set<PresolveTiming>,
    /** Round-to-fixpoint cap (`0` = no presolve, `1` = a single non-iterating pass). */
    val maxRounds: Int,
    /** Per-var cap on bake-time SAC `propagate` calls. */
    val probeBudgetPerVar: Int,
    /** Total cap on bake-time SAC `propagate` calls across all variables. */
    val probeTotalBudget: Int,
    /** Accepted spellings besides [id]. */
    private val aliases: List<String> = emptyList(),
) {
    /** No presolve. */
    OFF("off", emptySet(), 0, CAPPED_PROBE_BUDGET_PER_VAR, CAPPED_PROBE_TOTAL_BUDGET, aliases = listOf("none")),

    /** Cheap FAST reductions only, applied once. */
    CONSERVATIVE(
        "conservative",
        setOf(PresolveTiming.FAST),
        1,
        CAPPED_PROBE_BUDGET_PER_VAR,
        CAPPED_PROBE_TOTAL_BUDGET,
        aliases = listOf("fast"),
    ),

    /** FAST + MEDIUM reductions, iterated to a fixpoint. */
    DEFAULT(
        "default",
        setOf(PresolveTiming.FAST, PresolveTiming.MEDIUM),
        MAX_PRESOLVE_ROUNDS,
        CAPPED_PROBE_BUDGET_PER_VAR,
        CAPPED_PROBE_TOTAL_BUDGET,
        aliases = listOf("auto"),
    ),

    /** FAST + MEDIUM + EXHAUSTIVE reductions, iterated to a fixpoint. */
    AGGRESSIVE(
        "aggressive",
        setOf(PresolveTiming.FAST, PresolveTiming.MEDIUM, PresolveTiming.EXHAUSTIVE),
        MAX_PRESOLVE_ROUNDS,
        AGGRESSIVE_PROBE_BUDGET_PER_VAR,
        AGGRESSIVE_PROBE_TOTAL_BUDGET,
    ),
    ;

    /** Preset-token lookup. */
    companion object {
        private val byToken: Map<String, PresolveEmphasis> =
            entries.flatMap { e -> (listOf(e.id) + e.aliases).map { it to e } }.toMap()

        /** Parse a preset token, returning [DEFAULT] for no token. */
        fun fromId(id: String?): PresolveEmphasis? {
            val token = id?.trim()?.lowercase()
            return if (token.isNullOrEmpty()) DEFAULT else byToken[token]
        }

        /** Canonical preset ids, joined for command-line help and errors. */
        fun ids(): String = entries.joinToString(" | ") { it.id }
    }
}

/**
 * Selects presolve work independently from the round engine. It combines an effort [emphasis]
 * with explicit per-pass [overrides].
 */
class PresolveConfig(
    val emphasis: PresolveEmphasis = PresolveEmphasis.DEFAULT,
    val overrides: Map<PresolvePass, Boolean> = emptyMap(),
    private val probeBudgetPerVarOverride: Int? = null,
    private val probeTotalBudgetOverride: Int? = null,
    /** The pivot order used by affine elimination. */
    val affinePivotOrder: AffinePivotOrder = AffinePivotOrder.MARKOWITZ,
) {
    /** Per-variable cap on bake-time SAC `propagate` calls. */
    fun probeBudgetPerVar(): Int = probeBudgetPerVarOverride ?: emphasis.probeBudgetPerVar

    /** Total cap on bake-time SAC `propagate` calls. */
    fun probeTotalBudget(): Int = probeTotalBudgetOverride ?: emphasis.probeTotalBudget

    /** Return this plan with a different affine-elimination pivot order. */
    fun withAffinePivotOrder(pivotOrder: AffinePivotOrder): PresolveConfig =
        PresolveConfig(emphasis, overrides, probeBudgetPerVarOverride, probeTotalBudgetOverride, pivotOrder)

    /** Whether [pass] runs under [context]. */
    fun resolved(pass: PresolvePass, context: PresolveContext): Boolean = overrides[pass] ?: auto(pass, context)

    private fun auto(pass: PresolvePass, context: PresolveContext): Boolean = pass.autoEligible &&
        pass.timing in emphasis.timings &&
        (pass.preservesSolutionSet || !context.solutionSetSensitive) &&
        !(pass == PresolvePass.BREAK_SYMMETRIES && context.modelBreaksSymmetry)

    /** Problem-transform passes enabled for [context], in priority order. */
    fun problemPasses(context: PresolveContext): List<PresolvePass> =
        PresolvePass.entries.filter { it.stage == PresolvePass.Stage.PROBLEM && resolved(it, context) }

    /** Disable solution-set-collapsing passes for a pure local-search route. */
    fun forLocalSearch(): PresolveConfig = PresolveConfig(
        emphasis,
        overrides + PresolvePass.entries
            .filter { !it.preservesSolutionSet && it != PresolvePass.ELIMINATE_AFFINE_SINGLETONS }
            .associateWith { false },
        probeBudgetPerVarOverride,
        probeTotalBudgetOverride,
        affinePivotOrder,
    )

    /** Predefined plans and parser. */
    companion object {
        /** The shipped default plan. */
        val AUTO = PresolveConfig(PresolveEmphasis.DEFAULT)

        /** Alias for [AUTO]. */
        val DEFAULT = AUTO

        /** A plan with all presolve disabled. */
        val NONE = PresolveConfig(PresolveEmphasis.OFF)

        /** Parse an effort level plus optional `+pass` / `-pass` overrides. */
        fun parse(spec: String?): PresolveConfig {
            val source = spec?.trim()?.lowercase().orEmpty()
            if (source.isEmpty()) return AUTO
            if (source == "all") return PresolveConfig(overrides = PresolvePass.entries.associateWith { true })
            val tokens = source.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            PresolveEmphasis.fromId(tokens.first())?.let { base ->
                val overrides = HashMap<PresolvePass, Boolean>()
                for (token in tokens.drop(1)) {
                    val enabled = when (token.firstOrNull()) {
                        '+' -> true
                        '-' -> false
                        else -> error("after an emphasis, `$token` must be +<pass> or -<pass>")
                    }
                    overrides[passOf(token.substring(1))] = enabled
                }
                return PresolveConfig(base, overrides)
            }
            val enabled = tokens.map { passOf(it) }.toSet()
            return PresolveConfig(overrides = PresolvePass.entries.associateWith { it in enabled })
        }

        private fun passOf(id: String): PresolvePass =
            PresolvePass.fromId(id) ?: error("unknown presolve pass `$id`; expected ${PresolvePass.ids()}")
    }
}
