package com.eignex.klause.cli

import com.eignex.klause.config.propertyKnob

/**
 * The CLI-only `klause.*` env/property knobs that set defaults a flag then overrides — distinct from
 * the core [com.eignex.klause.config.KlauseConfig] knobs (declared in
 * [com.eignex.klause.config.KlauseConfigSchema]). Each key is derived from its property name via
 * [propertyKnob], so it is never spelled as a literal; read it with [cliProp].
 */
internal object CliKnobs {
    /** Default engine for a bare invocation: `klause.engine` / `KLAUSE_ENGINE`. */
    val engine by propertyKnob()

    /** Default portfolio arm-pool size: `klause.portfolio.arms` / `KLAUSE_PORTFOLIO_ARMS`. */
    val portfolioArms by propertyKnob()

    /** Default LP relaxation ceiling spec (parsed by `LpConfig.parse`): `klause.lp` / `KLAUSE_LP`. */
    val lp by propertyKnob()

    /** Soft wall-clock budget (milliseconds) for the presolve phase: `klause.presolve.budget.ms` /
     *  `KLAUSE_PRESOLVE_BUDGET_MS`. The presolve round engine and its long-running passes poll it and
     *  bail with the reductions made so far, so a pathologically large model can't spend unbounded time
     *  in presolve. `0` or negative disables the cap. Defaults to [DEFAULT_PRESOLVE_BUDGET_MS]. */
    val presolveBudgetMs by propertyKnob()

    /** Default presolve wall-clock budget: bounds presolve on pathological models while leaving ordinary
     *  instances untouched. Set well above the corpus's normal presolve times so the cap only ever fires
     *  as a runaway backstop — the long-running passes (affine, symmetry) still bail promptly via
     *  cooperative cancellation once it trips. */
    const val DEFAULT_PRESOLVE_BUDGET_MS = 5000L
}
