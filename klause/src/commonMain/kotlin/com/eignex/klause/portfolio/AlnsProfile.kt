package com.eignex.klause.portfolio

/**
 * A curated ALNS arm regime — the axes that distinguish one hybrid-ALNS arm from another in a diverse
 * portfolio. The two bandits already vary the destroy and repair *operators* within a single arm, so
 * cross-arm diversity is about the search *regime*: how much of the incumbent each iteration tears down
 * (small intensifying neighbourhoods vs. large diversifying ones) and how freely worsening repairs are
 * accepted (a hot simulated-annealing walk vs. strict hill-climbing).
 *
 * [Curated] is the credit-order-agnostic default pool the [EngineMix.ALNS] engine cycles through, the
 * ALNS analog of the LS/backtrack recipe catalogs.
 */
internal data class AlnsProfile(
    val label: String,
    /** Lower bound of the per-iteration destroy fraction (see [com.eignex.klause.meta.alns.Alns]). */
    val minDestroyFraction: Double,
    /** Upper bound of the per-iteration destroy fraction. */
    val maxDestroyFraction: Double,
    /** Worsening — as a fraction of the initial objective — accepted with ~50% probability at the start
     *  temperature. `0.0` selects strict hill-climbing ([com.eignex.klause.localsearch.AcceptanceCriterion.Improving]);
     *  above it the arm
     *  anneals from a temperature scaled to that worsening and cools by [saCooling] each iteration. */
    val saInitialWorsening: Double,
    /** Per-iteration geometric cooling rate when annealing; ignored when [saInitialWorsening] is `0.0`. */
    val saCooling: Double,
) {
    companion object {
        /** The curated ALNS regimes, spanning intensify → diversify and exploit → explore. */
        val Curated: List<AlnsProfile> = listOf(
            profile("balanced", destroy = 0.10 to 0.40, worsening = 0.05, cooling = 0.98),
            profile("intensify", destroy = 0.10 to 0.25, worsening = 0.02, cooling = 0.99),
            profile("diversify", destroy = 0.30 to 0.60, worsening = 0.10, cooling = 0.97),
            profile("exploit", destroy = 0.10 to 0.25, worsening = 0.0, cooling = 1.0),
        )

        private fun profile(label: String, destroy: Pair<Double, Double>, worsening: Double, cooling: Double) =
            AlnsProfile(label, destroy.first, destroy.second, worsening, cooling)

        /** The regime the standalone [AlnsWorkerConfig] arm (e.g. the last slot of a mixed portfolio) uses. */
        val Default: AlnsProfile = Curated.first()
    }
}
