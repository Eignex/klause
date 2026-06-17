package com.eignex.klause.config

/**
 * Build a [KlauseConfig] from JVM system properties and environment variables, layered over
 * [base] (defaults to [KlauseConfig.current]). For each key a system property wins over an
 * environment variable, which wins over the [base] value; unset / unparseable keys leave the
 * base value untouched.
 *
 * Recognised keys (system property | environment variable):
 *  - `klause.pinAbsentOpt` | `KLAUSE_PIN_ABSENT_OPT` — Boolean (`0`/`false`/`off`/`no` ⇒ false)
 *  - `klause.fzn.unboundedIntLo` | `KLAUSE_FZN_UNBOUNDED_INT_LO` — Int
 *  - `klause.fzn.unboundedIntHi` | `KLAUSE_FZN_UNBOUNDED_INT_HI` — Int
 *  - `klause.floatBuckets` | `KLAUSE_FLOAT_BUCKETS` — Int
 *  - `klause.floatScale` | `KLAUSE_FLOAT_SCALE` — Long
 *
 * This is the single place env/property names for core config are spelled out; entry points
 * should call it instead of reading `System.getenv` directly.
 */
fun klauseConfigFromEnv(base: KlauseConfig = KlauseConfig.current): KlauseConfig {
    fun raw(prop: String, env: String): String? = System.getProperty(prop) ?: System.getenv(env)
    fun bool(prop: String, env: String, default: Boolean): Boolean =
        raw(prop, env)?.let { it.trim().lowercase() !in FALSEY } ?: default
    fun int(prop: String, env: String, default: Int): Int = raw(prop, env)?.trim()?.toIntOrNull() ?: default
    fun long(prop: String, env: String, default: Long): Long = raw(prop, env)?.trim()?.toLongOrNull() ?: default
    return base.copy(
        pinAbsentOptVars = bool("klause.pinAbsentOpt", "KLAUSE_PIN_ABSENT_OPT", base.pinAbsentOptVars),
        unboundedIntLo = int("klause.fzn.unboundedIntLo", "KLAUSE_FZN_UNBOUNDED_INT_LO", base.unboundedIntLo),
        unboundedIntHi = int("klause.fzn.unboundedIntHi", "KLAUSE_FZN_UNBOUNDED_INT_HI", base.unboundedIntHi),
        floatBuckets = int("klause.floatBuckets", "KLAUSE_FLOAT_BUCKETS", base.floatBuckets),
        floatScale = long("klause.floatScale", "KLAUSE_FLOAT_SCALE", base.floatScale),
        lpMaxTableauCells = long(
            "klause.fzn.lpMaxTableauCells",
            "KLAUSE_FZN_LP_MAX_TABLEAU_CELLS",
            base.lpMaxTableauCells,
        ),
        lpCeilingTableauCells = long(
            "klause.fzn.lpCeilingTableauCells",
            "KLAUSE_FZN_LP_CEILING_TABLEAU_CELLS",
            base.lpCeilingTableauCells,
        ),
    )
}

/** Load core config from env/system properties and install it into [KlauseConfig.current].
 *  Call once at application startup before compiling. Returns the installed config. */
fun installKlauseConfigFromEnv(): KlauseConfig = klauseConfigFromEnv().also { KlauseConfig.current = it }

private val FALSEY = setOf("0", "false", "off", "no")
