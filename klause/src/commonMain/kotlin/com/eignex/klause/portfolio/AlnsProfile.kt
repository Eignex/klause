package com.eignex.klause.portfolio

// The destroy and repair bandits already vary operators within one arm, so cross-arm ALNS diversity comes
// from the regime: destroy-size band and how freely worsening repairs are accepted.
internal data class AlnsProfile(
    val label: String,
    val minDestroyFraction: Double,
    val maxDestroyFraction: Double,
    // Worsening, as a fraction of the initial objective, accepted with ~50% probability at the start
    // temperature. `0.0` selects strict hill-climbing.
    val saInitialWorsening: Double,
    // Ignored when saInitialWorsening is `0.0`.
    val saCooling: Double,
) {
    companion object {
        val Curated: List<AlnsProfile> = listOf(
            profile("balanced", destroy = 0.10 to 0.40, worsening = 0.05, cooling = 0.98),
            profile("intensify", destroy = 0.10 to 0.25, worsening = 0.02, cooling = 0.99),
            profile("diversify", destroy = 0.30 to 0.60, worsening = 0.10, cooling = 0.97),
            profile("exploit", destroy = 0.10 to 0.25, worsening = 0.0, cooling = 1.0),
        )

        private fun profile(label: String, destroy: Pair<Double, Double>, worsening: Double, cooling: Double) =
            AlnsProfile(label, destroy.first, destroy.second, worsening, cooling)

        val Default: AlnsProfile = Curated.first()
    }
}
