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

/**
 * Central, process-wide configuration for klause's core (compiler + frontends).
 *
 * Historically these knobs were scattered as ad-hoc `System.getenv` / `System.getProperty`
 * reads and loose constants resolved at each call site. [KlauseConfig] consolidates the ones
 * that affect *compilation and solving semantics* into a single immutable value object.
 *
 * Two usage modes:
 *  - **Ambient:** set [current] once at startup (e.g. a JVM entry point translating env vars
 *    via `klauseConfigFromEnv`) and let APIs that don't take an explicit config read it.
 *  - **Explicit:** pass a [KlauseConfig] straight to [com.eignex.klause.compile.Compiler] or
 *    `VariableSchema.compile(config)` when you need per-call control (tests, embedding).
 *
 * The core stays pure Kotlin with no platform dependencies — reading env vars / system
 * properties is the responsibility of platform entry points, which build a [KlauseConfig] and
 * assign it to [current].
 *
 * Note: bench-harness tuning (the `klause.bench.*` system properties) is deliberately *not*
 * here — those configure the benchmarking harness, not solving semantics, and live in their
 * own `klause.bench.*` namespace.
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
     * Disable via `KLAUSE_PIN_ABSENT_OPT=0` (e.g. for the MiniZinc challenge, where absent
     * opt-var output values are unconstrained by spec, so the extra clauses are pure overhead).
     */
    val pinAbsentOptVars: Boolean = true,

    /** Lower bound assigned to unbounded `var int` declarations (FlatZinc auxiliaries with no
     *  explicit range). Env: `KLAUSE_FZN_UNBOUNDED_INT_LO`. */
    val unboundedIntLo: Int = DEFAULT_UNBOUNDED_INT_LO,

    /** Upper bound counterpart to [unboundedIntLo]. Env: `KLAUSE_FZN_UNBOUNDED_INT_HI`. */
    val unboundedIntHi: Int = DEFAULT_UNBOUNDED_INT_HI,

    /** Number of uniformly-spaced buckets a `floatVar` is discretised into when no explicit
     *  count is given. Higher = finer precision, more bits per float var. Env:
     *  `KLAUSE_FLOAT_BUCKETS`. */
    val floatBuckets: Int = DEFAULT_FLOAT_BUCKETS,

    /** Fixed-point scale used by the FlatZinc float-linear lowering (real coefficients and
     *  bounds are multiplied by this and rounded to integers). Env: `KLAUSE_FLOAT_SCALE`. */
    val floatScale: Long = DEFAULT_FLOAT_SCALE,

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
    }
}
