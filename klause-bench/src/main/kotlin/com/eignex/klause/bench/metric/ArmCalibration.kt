package com.eignex.klause.bench.metric

/**
 * Per-arm credit from **one live portfolio run**, aimed at a **complementary, diverse** arm set rather
 * than the single best arm. Each problem's winner is its **best-holder** — the arm that produced the
 * final (best) incumbent, read from the `%%%klause-arm:` attribution of a co-running `-e mixed|ls|cp`
 * `-p<N>` optimize. An arm's score is its summed **win share** (`1/co-winners` per problem won), and the
 * **diverse palette** is a greedy set-cover over the per-problem best-holders — keep arms that win where
 * others don't; an arm always shadowed by a stronger sibling earns no slot.
 *
 * This measures each arm's *real marginal contribution* in the pool as it actually runs (with the
 * portfolio's incumbent/bound sharing), from a single run — so the ranking reflects production, and
 * evaluating a new candidate is just adding it to the pool. A problem no arm holds a strict incumbent
 * on contributes nothing and is dropped.
 */
internal object ArmCalibration {

    /** Per-arm scores: the summed win share and the count of problems won. */
    data class ArmScore(val arm: String, val winShare: Double, val wins: Int)

    /** One slot of the recalibrated palette: [newlyCovered] is the arm's marginal contribution (new
     *  problems it wins that no earlier slot did), [cumulativeCovered] the running total. */
    data class DiverseSlot(val rank: Int, val arm: String, val newlyCovered: Int, val cumulativeCovered: Int)

    /** The calibration outcome over [totalWon] discriminating problems — take the first k palette
     *  slots for a diverse k-arm portfolio. */
    data class Report(
        val instances: Int,
        val totalWon: Int,
        val scores: List<ArmScore>,
        val diverse: List<DiverseSlot>,
    )

    /** Score and rank a palette over the per-problem winner sets: [won] is the best-holder(s) of each
     *  discriminating problem, [arms] every arm that contributed (so a never-winning arm still ranks,
     *  at zero). Emits the win-share tally and the greedy marginal-contribution palette. [instances] is
     *  the total scored, defaulting to the discriminating count. */
    fun scoreWinnerSets(arms: List<String>, won: List<Set<String>>, instances: Int = won.size): Report {
        val winShare = HashMap<String, Double>()
        val wins = HashMap<String, Int>()
        for (winners in won) {
            val share = 1.0 / winners.size
            for (arm in winners) {
                winShare[arm] = (winShare[arm] ?: 0.0) + share
                wins[arm] = (wins[arm] ?: 0) + 1
            }
        }
        val scores = arms.map { ArmScore(it, winShare[it] ?: 0.0, wins[it] ?: 0) }
            .sortedByDescending { it.winShare }
        return Report(instances, won.size, scores, greedyDiverse(arms, won, winShare))
    }

    /** Rank *every* arm by greedy marginal contribution over the per-problem [won] winner sets: each
     *  slot goes to the arm winning the most still-uncovered problems, ties broken by total win share.
     *  Ranking continues past full coverage (those tail slots add 0), so any prefix of length k is a
     *  diverse k-arm portfolio and the cumulative-coverage curve shows where the returns flatten. */
    private fun greedyDiverse(
        arms: List<String>,
        won: List<Set<String>>,
        winShare: Map<String, Double>,
    ): List<DiverseSlot> {
        val covered = BooleanArray(won.size)
        val taken = HashSet<String>()
        val palette = ArrayList<DiverseSlot>()
        var cumulative = 0
        while (taken.size < arms.size) {
            var bestArm: String? = null
            var bestCover = -1
            var bestShare = -1.0
            for (arm in arms) {
                if (arm in taken) continue
                val cover = won.indices.count { !covered[it] && arm in won[it] }
                val share = winShare[arm] ?: 0.0
                if (cover > bestCover || (cover == bestCover && share > bestShare)) {
                    bestArm = arm
                    bestCover = cover
                    bestShare = share
                }
            }
            val arm = bestArm ?: break
            won.indices.forEach { if (arm in won[it] && !covered[it]) covered[it] = true }
            cumulative += bestCover
            taken += arm
            palette += DiverseSlot(palette.size + 1, arm, bestCover, cumulative)
        }
        return palette
    }

    /** Render a [Report] as a plain-text table for the bench console. */
    fun render(report: Report): String = buildString {
        appendLine(
            "=== arm calibration: ${report.instances} instances, ${report.totalWon} discriminating ===",
        )
        appendLine("--- win share | problems won ---")
        for (s in report.scores) appendLine("  ${s.arm.padEnd(28)} ${fmt(s.winShare)}  ${s.wins}")
        appendLine("")
        appendLine("--- marginal-contribution ranking (take the first k for a diverse k-arm set) ---")
        for (slot in report.diverse) {
            appendLine(
                "  ${slot.rank.toString().padStart(2)}  ${slot.arm.padEnd(28)} " +
                    "+covered=${slot.newlyCovered}  (${slot.cumulativeCovered}/${report.totalWon})",
            )
        }
    }

    private fun fmt(value: Double): String = ((value * 100).toLong() / 100.0).toString().padStart(7)
}
