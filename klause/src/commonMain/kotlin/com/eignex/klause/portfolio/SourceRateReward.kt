package com.eignex.klause.portfolio

internal class SourceRateReward : PortfolioRewardPolicy {
    private class Account {
        var cost = 0.0
        var pendingCost = 0.0
        var gain = 0.0
    }

    private val accounts = linkedMapOf<Int, Account>()
    private var gainScale = 0.0

    override fun reward(progress: PortfolioProgress): Double {
        val account = accounts.getOrPut(progress.arm) { Account() }
        if (!progress.idleCredit) {
            val cost = progress.nanos.coerceAtLeast(0L).toDouble() +
                progress.constructionNanos.coerceAtLeast(0L)
            account.cost += cost
            account.pendingCost += cost
        }
        if (progress.failed || progress.refuted) return 0.0
        if (progress.phase == PortfolioPhase.FEASIBILITY) return progress.ordinaryReward
        if (progress.phase == PortfolioPhase.PROOF_CLOSURE) return 1.0
        if (progress.idleCredit || !progress.acceptedIncumbent || account.pendingCost <= 0.0) return 0.0
        val gain = progress.credit[Signal.Improvement.name]?.takeIf { it.isFinite() && it > 0.0 } ?: return 0.0
        val normalized = normalize(gain)
        account.gain += normalized
        val ownRate = normalized / account.pendingCost
        account.pendingCost = 0.0
        val others = accounts.filterKeys { it != progress.arm }.values
        val cost = others.sumOf { it.cost }
        val othersRate = if (cost > 0.0) others.sumOf { it.gain } / cost else 0.0
        return if (ownRate > 0.0) ownRate / (ownRate + othersRate) else 0.0
    }

    private fun normalize(gain: Double): Double {
        if (gain > gainScale) {
            val scale = gainScale / gain
            for (other in accounts.values) other.gain *= scale
            gainScale = gain
        }
        return gain / gainScale
    }
}
