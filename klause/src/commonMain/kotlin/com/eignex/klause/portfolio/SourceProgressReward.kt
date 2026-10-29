package com.eignex.klause.portfolio

internal object SourceProgressReward : PortfolioRewardPolicy {
    override fun reward(progress: PortfolioProgress): Double = when {
        progress.failed || progress.refuted -> 0.0
        progress.phase == PortfolioPhase.FEASIBILITY -> progress.ordinaryReward
        progress.acceptedIncumbent || progress.phase == PortfolioPhase.PROOF_CLOSURE -> 1.0
        else -> 0.0
    }
}
