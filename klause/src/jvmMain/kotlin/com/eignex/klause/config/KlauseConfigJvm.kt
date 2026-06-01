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
 *
 * This is the single place env/property names for core config are spelled out; entry points
 * should call it instead of reading `System.getenv` directly.
 */
fun klauseConfigFromEnv(base: KlauseConfig = KlauseConfig.current): KlauseConfig {
    fun raw(prop: String, env: String): String? =
        System.getProperty(prop) ?: System.getenv(env)
    fun bool(prop: String, env: String, default: Boolean): Boolean =
        raw(prop, env)?.let { it.trim().lowercase() !in FALSEY } ?: default
    fun int(prop: String, env: String, default: Int): Int =
        raw(prop, env)?.trim()?.toIntOrNull() ?: default
    return base.copy(
        pinAbsentOptVars = bool("klause.pinAbsentOpt", "KLAUSE_PIN_ABSENT_OPT", base.pinAbsentOptVars),
        unboundedIntLo = int("klause.fzn.unboundedIntLo", "KLAUSE_FZN_UNBOUNDED_INT_LO", base.unboundedIntLo),
        unboundedIntHi = int("klause.fzn.unboundedIntHi", "KLAUSE_FZN_UNBOUNDED_INT_HI", base.unboundedIntHi),
    )
}

/** Load core config from env/system properties and install it into [KlauseConfig.current].
 *  Call once at application startup before compiling. Returns the installed config. */
fun installKlauseConfigFromEnv(): KlauseConfig =
    klauseConfigFromEnv().also { KlauseConfig.current = it }

private val FALSEY = setOf("0", "false", "off", "no")
