package com.eignex.klause.config

import com.eignex.klause.formats.flatzinc.DEFAULT_UNBOUNDED_INT_HI
import com.eignex.klause.formats.flatzinc.DEFAULT_UNBOUNDED_INT_LO

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
) {
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
