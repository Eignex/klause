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

    /** Share of the solve budget presolve may spend: `klause.presolve.budget.fraction` /
     *  `KLAUSE_PRESOLVE_BUDGET_FRACTION`. Overridden by an explicit [presolveBudgetMs]. */
    val presolveBudgetFraction by propertyKnob()

    /** Fraction of the `-t` budget presolve may spend. A flat allowance is wrong in both directions —
     *  it is most of a short budget and a rounding error on a long one — so the phase scales with the
     *  run it precedes. */
    const val DEFAULT_PRESOLVE_BUDGET_FRACTION = 0.1

    /** Presolve budget when the run carries no `-t` at all, so there is no total to take a share of.
     *  A pure backstop against a pathological model; the long-running passes bail promptly once it
     *  trips via cooperative cancellation. */
    const val DEFAULT_PRESOLVE_BUDGET_MS = 5000L

    /** Floor on the derived budget: a 10% slice of a short budget leaves presolve unable to finish a
     *  single pass on a large model, so the floor buys it enough to be worth entering at all. Bounded
     *  above by [MAX_PRESOLVE_BUDGET_SHARE] — the floor may raise a small budget, never take the run. */
    const val MIN_PRESOLVE_BUDGET_MS = DEFAULT_PRESOLVE_BUDGET_MS

    /** Ceiling on presolve's share of `-t`, applied after [MIN_PRESOLVE_BUDGET_MS]. Without it the flat
     *  floor wins outright on any run shorter than `MIN_PRESOLVE_BUDGET_MS / fraction` (50s at the
     *  default 0.1) — at `-t 5000` presolve is handed the entire time limit and the search gets none.
     *  Only ever binds where the floor would otherwise displace the search it precedes. */
    const val MAX_PRESOLVE_BUDGET_SHARE = 0.25
}
