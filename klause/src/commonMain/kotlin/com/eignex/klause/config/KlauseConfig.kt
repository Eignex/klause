package com.eignex.klause.config

import com.eignex.klause.ir.DomainStorageSettings
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.presolve.PresolveEmphasis
import com.eignex.klause.presolve.PresolvePass

/** Default lower bound for an unbounded `var int`: the full `Long` floor, so no artificial clamp clips a
 *  legitimate value. Span and coefficient arithmetic on domain bounds must stay overflow-safe. */
const val DEFAULT_UNBOUNDED_INT_LO: Long = Long.MIN_VALUE

/** Default upper bound for unbounded `var int`; counterpart to [DEFAULT_UNBOUNDED_INT_LO]. */
const val DEFAULT_UNBOUNDED_INT_HI: Long = Long.MAX_VALUE

/** MiniZinc's default range for an unbounded `var int` (`±10^6`). This is a semantic default, so an
 *  `unsat` within it is a sound `unsat`. */
const val MINIZINC_UNBOUNDED_DEFAULT: Long = 1_000_000

/** Default integer-domain span above which LP feasibility and bound tightening run ahead of the root
 *  bake, whose bound propagation would otherwise grind O(span). A pure cost gate. */
const val DEFAULT_LARGE_SPAN_THRESHOLD: Long = 1_000_000

/** Default **base** relaxation-size cap (`rows × (cols + rows + 1)` cells), a pure per-node cost guard.
 *  A model whose base relaxation fits this cap budgets its gated hulls against it; a larger base, still
 *  under [DEFAULT_LP_CEILING_TABLEAU_CELLS], budgets hulls against that ceiling. */
const val DEFAULT_LP_MAX_TABLEAU_CELLS: Long = 1L shl 20

/** Default **ceiling** relaxation-size cap: the size past which automatic LP planning declines LP
 *  entirely, and the hull budget for models whose base relaxation exceeds the base cap. A pure cost guard. */
const val DEFAULT_LP_CEILING_TABLEAU_CELLS: Long = 1L shl 26

/** Default span threshold (inclusive) below which a non-contiguous int domain is stored as a bitset
 *  (at most 64 longs) rather than a run or survivor list. A pure storage/speed tradeoff. */
const val DEFAULT_BITSET_THRESHOLD: Int = 4096

/**
 * Maximum present-value count for a propagator's value-by-value walk. Larger domains use
 * span-independent bounds, cover or position reasoning. Matches [DEFAULT_BITSET_THRESHOLD]
 * so bitset-sized domains remain cheap to iterate; above the cap pruning may be weaker
 * but remains sound.
 */
const val DEFAULT_DOMAIN_WALK_CAP: Long = DEFAULT_BITSET_THRESHOLD.toLong()

/**
 * Immutable invocation configuration: storage policy and preparation effort for one invocation.
 * Source interpretation belongs to [com.eignex.klause.ir.SemanticOverrides].
 *
 * Two usage modes:
 *  - **Ambient:** set [current] once at startup (e.g. a CLI entry point translating env vars
 *    via [fromProps]) and let APIs that don't take an explicit config capture it at entry.
 *  - **Explicit:** pass a [KlauseConfig] straight to [com.eignex.klause.compile.Compiler] or
 *    `VariableSchema.compile(config)` when you need per-call control (tests, embedding).
 *    Source models retain [problemSettings] through preparation and solving.
 *
 * Reading env vars and system properties is the responsibility of platform entry points, which feed a
 * [fromProps] lookup. Presolve has no env key: it is set programmatically or via the CLI `--presolve` flag.
 */
data class KlauseConfig(
    /** Integer-domain span above which LP preparation runs ahead of the root bake (see
     *  [DEFAULT_LARGE_SPAN_THRESHOLD]). Wide columns coupled to continuous columns also use it. */
    val largeSpanThreshold: Long = DEFAULT_LARGE_SPAN_THRESHOLD,

    /** Base relaxation-size cap (see [DEFAULT_LP_MAX_TABLEAU_CELLS]). */
    val lpMaxTableauCells: Long = DEFAULT_LP_MAX_TABLEAU_CELLS,

    /** Ceiling relaxation-size cap (see [DEFAULT_LP_CEILING_TABLEAU_CELLS]). */
    val lpCeilingTableauCells: Long = DEFAULT_LP_CEILING_TABLEAU_CELLS,

    /** Bitset storage span threshold (see [DEFAULT_BITSET_THRESHOLD]). */
    val bitsetThreshold: Int = DEFAULT_BITSET_THRESHOLD,

    // Presolve: an emphasis level plus one tri-state override per pass (`true` forces the pass on,
    // `false` off, `null` defers to the emphasis).

    /** Presolve effort level. */
    val presolveEmphasis: PresolveEmphasis = PresolveEmphasis.DEFAULT,

    /** GCD coefficient strengthening. `null` follows the emphasis. */
    val presolveStrengthenCoefficients: Boolean? = null,

    /** Affine singleton elimination. `null` follows the emphasis. */
    val presolveAffineSingletons: Boolean? = null,

    /** Interchangeable-variable symmetry breaking. `null` follows the emphasis, and stays off for
     *  solution-set-sensitive queries. */
    val presolveBreakSymmetries: Boolean? = null,

    /** Construction-time failed-literal probing. `null` follows the emphasis. */
    val presolveProbeFailedLiterals: Boolean? = null,

    /** Construction-time bound SAC. `null` follows the emphasis. */
    val presolveProbeIntBounds: Boolean? = null,

    /** Construction-time interior-hole SAC, implying bound SAC. `null` follows the emphasis. */
    val presolveProbeIntHoles: Boolean? = null,
) {
    /** Narrow immutable resource settings for source models and their projections. */
    fun problemSettings(): ProblemSettings = ProblemSettings(
        storage = DomainStorageSettings(bitsetThreshold),
        largeSpanThreshold = largeSpanThreshold,
        lpMaxTableauCells = lpMaxTableauCells,
        lpCeilingTableauCells = lpCeilingTableauCells,
    )

    /** Bundle the emphasis level and the per-pass override knobs into a [PresolveConfig]; `null`
     *  knobs are left out (deferred to the emphasis), explicit values become forced overrides. */
    fun presolveConfig(): PresolveConfig = PresolveConfig(
        emphasis = presolveEmphasis,
        overrides = buildMap {
            presolveStrengthenCoefficients?.let { put(PresolvePass.STRENGTHEN_COEFFICIENTS, it) }
            presolveAffineSingletons?.let { put(PresolvePass.ELIMINATE_AFFINE_SINGLETONS, it) }
            presolveBreakSymmetries?.let { put(PresolvePass.BREAK_SYMMETRIES, it) }
            presolveProbeFailedLiterals?.let { put(PresolvePass.PROBE_FAILED_LITERALS, it) }
            presolveProbeIntBounds?.let { put(PresolvePass.PROBE_INT_BOUNDS, it) }
            presolveProbeIntHoles?.let { put(PresolvePass.PROBE_INT_HOLES, it) }
        },
    )

    /** Default configuration values. */
    companion object {
        /** Built-in defaults. */
        val DEFAULT: KlauseConfig = KlauseConfig()

        /**
         * Ambient configuration consulted by APIs that don't take an explicit [KlauseConfig]
         * (notably `VariableSchema.compile()` and format entry points). Assign once at application
         * startup, before compiling. Defaults to [DEFAULT].
         */
        var current: KlauseConfig = DEFAULT

        /**
         * Layer property/env/file overrides onto [base], reading each knob of [KlauseConfigSchema] through
         * [lookup], which maps a [ConfigKey.propertyKey] such as `klause.bitset.threshold` to its raw value or
         * `null`. Unset or unparseable values leave [base] untouched. Source-semantic keys are rejected; pass
         * [com.eignex.klause.ir.SemanticOverrides] to the source entry.
         */
        fun fromProps(base: KlauseConfig = current, lookup: (String) -> String?): KlauseConfig {
            for (name in retiredSourceKnobs) {
                val key = klausePropertyKey(name)
                require(lookup(key) == null) { "$key is source semantics; pass SemanticOverrides at the source entry" }
            }
            return KlauseConfigSchema.keys.fold(base) { config, key ->
                lookup(key.propertyKey)?.let { key.applyRaw(config, it) } ?: config
            }
        }

        private val retiredSourceKnobs = listOf(
            "pinAbsentOptVars", "unboundedIntLo", "unboundedIntHi", "floatBuckets", "floatScale",
            "unboundedFloatLo", "unboundedFloatHi",
        )
    }
}
