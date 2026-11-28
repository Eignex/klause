package com.eignex.klause.cli

import com.eignex.klause.config.propertyKnob
import com.eignex.util.process.processProperty

/**
 * CLI-only `klause.*` env/property knobs that set defaults a flag then overrides. Each key derives from its
 * property name via [propertyKnob]; read it with [processProperty].
 */
internal object CliKnobs {
    val engine by propertyKnob()

    val portfolioArms by propertyKnob()

    val lp by propertyKnob()

    // Applied as an elapsed ceiling and converted to work at PRESOLVE_WORK_PER_MS; zero or negative disables
    // both caps.
    val presolveBudgetMs by propertyKnob()

    // Overridden by an explicit presolveBudgetMs.
    val presolveBudgetFraction by propertyKnob()

    // A flat allowance is most of a short budget and a rounding error on a long one, so the phase scales
    // with the run it precedes.
    const val DEFAULT_PRESOLVE_BUDGET_FRACTION = 0.1

    // Backstop for a run without `-t`, which has no total to take a share of.
    const val DEFAULT_PRESOLVE_BUDGET_MS = 5000L

    // A fractional slice of a short budget cannot finish a single pass on a large model.
    const val MIN_PRESOLVE_BUDGET_MS = DEFAULT_PRESOLVE_BUDGET_MS

    // Applied after the floor, so on a short `-t` the floor never takes the whole time limit from search.
    const val MAX_PRESOLVE_BUDGET_SHARE = 0.25

    // Calibrated as the median weighted work per millisecond of uncapped presolves lasting at least a second;
    // shorter runs are dominated by warm-up no unit can charge.
    const val PRESOLVE_WORK_PER_MS = 275_000L
}
