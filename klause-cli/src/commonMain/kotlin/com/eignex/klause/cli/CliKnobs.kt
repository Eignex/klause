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

    /** Default presolve wall-clock budget, sized so the presolve phase stays under ~2s on every corpus
     *  instance: the long-running passes bail via cooperative cancellation with the reductions made so far
     *  (each pass is sound, so a partial run only forgoes further reduction). Set below the ~1.5s where
     *  every ordinary instance's productive presolve is already front-loaded (a large model keeps
     *  >99.9% of its reduction at this cap) and above it plus the reduced-problem delta-application
     *  (~0.4s on a multi-million-factor model) so the total presolve phase lands under 2s. */
    const val DEFAULT_PRESOLVE_BUDGET_MS = 1400L
}
