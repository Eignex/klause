package com.eignex.klause.config

import com.eignex.klause.solver.presolve.PresolveConfig
import com.eignex.klause.solver.presolve.PresolveEmphasis
import com.eignex.klause.solver.presolve.PresolvePass

/** Default lower bound assigned to unbounded `var int` declarations (FlatZinc auxiliaries
 *  with no explicit range). Wide enough to absorb typical CP arithmetic without overflow in
 *  factor coefficient × value products; matches the convention used by Gecode / Chuffed. */
const val DEFAULT_UNBOUNDED_INT_LO: Int = -10_000_000

/** Default upper bound for unbounded `var int`; counterpart to [DEFAULT_UNBOUNDED_INT_LO]. */
const val DEFAULT_UNBOUNDED_INT_HI: Int = 10_000_000

/** Default number of uniformly-spaced buckets a `floatVar` is discretised into. 10-bit
 *  precision is enough for typical config-style fractions; raise it for finer granularity. */
const val DEFAULT_FLOAT_BUCKETS: Int = 1024

/** Default fixed-point scale for the FlatZinc float-linear lowering: real coefficients and
 *  bounds are multiplied by this and rounded to integers. */
const val DEFAULT_FLOAT_SCALE: Long = 1_000_000L

/** Default **base** relaxation-size cap (`rows × (cols + rows + 1)` cells). The sparse revised simplex
 *  is the only LP engine (#705); this is a pure per-node cost guard (not a memory bound), and the bound
 *  stays sound either way. A model whose *base* relaxation fits this cap budgets its gated hulls against
 *  it; a larger base (still under [DEFAULT_LP_CEILING_TABLEAU_CELLS]) budgets hulls against that
 *  ceiling. Raise it (env below) to spend more per node for hull reach on small models. */
const val DEFAULT_LP_MAX_TABLEAU_CELLS: Long = 1L shl 20

/** Default **ceiling** relaxation-size cap: the absolute size past which `LpAutoConfig` declines LP
 *  entirely. Larger than [DEFAULT_LP_MAX_TABLEAU_CELLS] (the sparse engine carries bigger relaxations
 *  cheaply); also the hull budget for models whose base relaxation is over the base cap but under this
 *  ceiling. A pure cost guard (the bound is sound). */
const val DEFAULT_LP_CEILING_TABLEAU_CELLS: Long = 1L shl 26

/** Default span threshold (inclusive) below which a non-contiguous int domain is stored as a bitset
 *  rather than a wide run / survivor rep. 4096 ⇒ ≤ 64 longs (512 bytes), which keeps membership O(1)
 *  across the moderate-span middle ground; only genuinely wide domains fall through to the wide reps.
 *  A pure storage/speed tradeoff — the domain semantics are identical either way. */
const val DEFAULT_BITSET_THRESHOLD: Int = 4096

/**
 * Central, process-wide configuration for klause's core (compiler + frontends).
 *
 * Historically these knobs were scattered as ad-hoc `System.getenv` / `System.getProperty`
 * reads and loose constants resolved at each call site. [KlauseConfig] consolidates the ones
 * that affect *compilation and solving semantics* into a single immutable value object.
 *
 * Two usage modes:
 *  - **Ambient:** set [current] once at startup (e.g. a CLI entry point translating env vars
 *    via [fromProps]) and let APIs that don't take an explicit config read it.
 *  - **Explicit:** pass a [KlauseConfig] straight to [com.eignex.klause.compile.Compiler] or
 *    `VariableSchema.compile(config)` when you need per-call control (tests, embedding).
 *
 * The core stays pure Kotlin with no platform dependencies — reading env vars / system
 * properties is the responsibility of platform entry points, which feed a property [fromProps]
 * lookup and assign the result to [current].
 *
 * Two things are deliberately *not* env-configurable here:
 *  - **Presolve** — the presolve* fields are set programmatically (embedding) or per-invocation
 *    via the CLI `--presolve` flag; there is no env key for them.
 *  - **Bench-harness tuning** (the `klause.bench.*` system properties) — those configure the
 *    benchmarking harness, not solving semantics, and live in their own namespace.
 */
data class KlauseConfig(
    /**
     * When true, an optional variable whose presence Boolean is false is pinned to a
     * canonical in-domain default: `0` coerced into `[min, max]` for ints, `false` for bools,
     * and the first declared label for nominals.
     *
     * An absent opt var is otherwise free to take any domain value, and every such value
     * yields an equivalent model. Pinning collapses that dead-value symmetry — it shrinks the
     * solution space for enumeration / model-counting and gives clean, deterministic decoded
     * values for absent vars.
     *
     * Disable via `KLAUSE_PIN_ABSENT_OPT_VARS=0` (e.g. for the MiniZinc challenge, where absent
     * opt-var output values are unconstrained by spec, so the extra clauses are pure overhead).
     */
    val pinAbsentOptVars: Boolean = true,

    /** Lower bound assigned to unbounded `var int` declarations (FlatZinc auxiliaries with no
     *  explicit range). */
    val unboundedIntLo: Int = DEFAULT_UNBOUNDED_INT_LO,

    /** Upper bound counterpart to [unboundedIntLo]. */
    val unboundedIntHi: Int = DEFAULT_UNBOUNDED_INT_HI,

    /** Number of uniformly-spaced buckets a `floatVar` is discretised into when no explicit
     *  count is given. Higher = finer precision, more bits per float var. */
    val floatBuckets: Int = DEFAULT_FLOAT_BUCKETS,

    /** Fixed-point scale used by the FlatZinc float-linear lowering (real coefficients and
     *  bounds are multiplied by this and rounded to integers). */
    val floatScale: Long = DEFAULT_FLOAT_SCALE,

    /** Base relaxation-size cap (see [DEFAULT_LP_MAX_TABLEAU_CELLS]): a model whose base relaxation
     *  fits it budgets its gated hulls against it. A pure cost guard — the bound is always sound. */
    val lpMaxTableauCells: Long = DEFAULT_LP_MAX_TABLEAU_CELLS,

    /** Ceiling relaxation-size cap (see [DEFAULT_LP_CEILING_TABLEAU_CELLS]): the absolute size past
     *  which LP is declined, and the hull budget for an over-base-cap but in-ceiling model. */
    val lpCeilingTableauCells: Long = DEFAULT_LP_CEILING_TABLEAU_CELLS,

    /** Span threshold (inclusive) below which a non-contiguous int domain is stored as a bitset
     *  rather than a wide rep (see [DEFAULT_BITSET_THRESHOLD]). A pure storage/speed tradeoff. */
    val bitsetThreshold: Int = DEFAULT_BITSET_THRESHOLD,

    // Presolve: an emphasis level plus one tri-state override knob per pass (`true` forces the pass
    // on, `false` off, `null` defers to the emphasis). Assembled into a [PresolveConfig] via
    // [presolveConfig]; the CLI `--presolve` flag / `klause.presolve` property override these.

    /** Presolve effort level. */
    val presolveEmphasis: PresolveEmphasis = PresolveEmphasis.DEFAULT,

    /** GCD coefficient strengthening. `null` = follow the emphasis. */
    val presolveStrengthenCoefficients: Boolean? = null,

    /** Affine singleton elimination. `null` = auto (on). */
    val presolveAffineSingletons: Boolean? = null,

    /** Interchangeable-variable symmetry breaking. `null` = auto (on for decision/optimization,
     *  off for solution-set-sensitive queries — enumeration / counting / sampling). */
    val presolveBreakSymmetries: Boolean? = null,

    /** Construction-time failed-literal SAC. `null` = auto (off — opt-in). */
    val presolveProbeFailedLiterals: Boolean? = null,

    /** Construction-time bound SAC. `null` = auto (off — opt-in). */
    val presolveProbeIntBounds: Boolean? = null,

    /** Construction-time interior-hole SAC (implies bound SAC). `null` = auto (off — opt-in). */
    val presolveProbeIntHoles: Boolean? = null,
) {
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
         * (notably `VariableSchema.compile()` and `Compiler()`). Assign once at application
         * startup, before compiling. Defaults to [DEFAULT].
         */
        var current: KlauseConfig = DEFAULT

        /**
         * Layer property/env/file overrides onto [base], reading each knob through [lookup] — a
         * function from a knob's [ConfigKey.propertyKey] (e.g. `klause.float.buckets`) to its raw
         * string value, or `null` when unset. The recognised knobs, their keys and their parsing all
         * come from [KlauseConfigSchema] (one declaration per knob), so this never re-spells a key.
         * Unset / unparseable values leave the [base] value untouched.
         *
         * Platform entry points supply the [lookup]: a CLI maps the dotted key to a system property
         * and/or the `KLAUSE_FLOAT_BUCKETS`-style env var; a config file maps it to a parsed value.
         */
        fun fromProps(base: KlauseConfig = current, lookup: (String) -> String?): KlauseConfig =
            KlauseConfigSchema.keys.fold(base) { config, key ->
                lookup(key.propertyKey)?.let { key.applyRaw(config, it) } ?: config
            }
    }
}
