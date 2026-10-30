package com.eignex.klause.portfolio

internal class SourceRateReward(
    val priceFamilies: Boolean = false,
    families: List<ArmFamily> = emptyList(),
) : PortfolioRewardPolicy {
    private class Account {
        var cost = 0.0
        var pendingCost = 0.0
        var gain = 0.0
    }

    private val accounts = linkedMapOf<Int, Account>()
    private val families = if (priceFamilies) families.toList() else emptyList()
    private val familyAccounts = linkedMapOf<ArmFamily, Account>()
    private var gainScale = 0.0
    var familyReward: Double? = null
        private set

    fun fresh(families: List<ArmFamily>): SourceRateReward =
        SourceRateReward(priceFamilies, if (priceFamilies) families else emptyList())

    override fun reward(progress: PortfolioProgress): Double {
        val account = accounts.getOrPut(progress.arm) { Account() }
        val family = families.getOrNull(progress.arm)
        val familyAccount = family?.let { familyAccounts.getOrPut(it) { Account() } }
        familyReward = if (familyAccount != null && progress.phase != PortfolioPhase.FEASIBILITY) 0.0 else null
        if (!progress.idleCredit) {
            val cost = progress.nanos.coerceAtLeast(0L).toDouble() +
                progress.constructionNanos.coerceAtLeast(0L)
            account.cost += cost
            account.pendingCost += cost
            if (familyAccount != null) {
                familyAccount.cost += cost
                familyAccount.pendingCost += cost
            }
        }
        if (progress.failed || progress.refuted) return 0.0
        if (progress.phase == PortfolioPhase.FEASIBILITY) return progress.ordinaryReward
        if (progress.phase == PortfolioPhase.PROOF_CLOSURE) {
            if (familyAccount != null) familyReward = 1.0
            return 1.0
        }
        if (progress.idleCredit || !progress.acceptedIncumbent || account.pendingCost <= 0.0) return 0.0
        val gain = progress.credit[Signal.Improvement.name]?.takeIf { it.isFinite() && it > 0.0 } ?: return 0.0
        val normalized = normalize(gain)
        val reward = price(account, normalized, accounts.filterKeys { it != progress.arm }.values)
        if (familyAccount != null) {
            familyReward = price(familyAccount, normalized, familyAccounts.filterKeys { it != family }.values)
        }
        return reward
    }

    private fun price(account: Account, normalized: Double, others: Collection<Account>): Double {
        if (account.pendingCost <= 0.0) return 0.0
        account.gain += normalized
        val ownRate = normalized / account.pendingCost
        account.pendingCost = 0.0
        val cost = others.sumOf { it.cost }
        val othersRate = if (cost > 0.0) others.sumOf { it.gain } / cost else 0.0
        return if (ownRate > 0.0) ownRate / (ownRate + othersRate) else 0.0
    }

    private fun normalize(gain: Double): Double {
        if (gain > gainScale) {
            val scale = gainScale / gain
            for (other in accounts.values) other.gain *= scale
            for (other in familyAccounts.values) other.gain *= scale
            gainScale = gain
        }
        return gain / gainScale
    }
}
