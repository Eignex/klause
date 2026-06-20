package com.eignex.klause.bench.metric

import kotlin.math.abs

/**
 * Fair-tester scoring, aimed at a **complementary, diverse** arm set rather than the single best arm.
 * Each arm is run in **isolation** (one subprocess, full budget, no shared incumbent), so its result
 * on a problem is purely its own. Looking at each problem individually, the best arm(s) *win* it (ties
 * shared); an arm's score is its summed **win share** (`1/co-winners` per problem won) so wins score
 * highly and unique wins score highest, and the **diverse palette** is a greedy set-cover over the
 * per-problem winners — keep arms that win where others don't, drop the redundant ones.
 *
 * Winners follow the MiniZinc-Challenge comparison (mirrors `compare.sh`), per [complete]:
 *  - **incomplete** (local search): only objective quality counts — best objective among the feasible
 *    arms wins; optimality and time are ignored, so equal-objective arms share the win.
 *  - **complete** (backtrack / CP): the chain solved > proved-optimal > objective > faster — a proved
 *    optimum beats an unproved equal, and among otherwise-equal arms the faster one wins.
 * A problem every arm ties on (all reach the same outcome) discriminates nothing and is dropped.
 */
internal object ArmCalibration {

    /** One arm's isolated run on one optimize instance (model-oriented [finalObjective]). */
    data class ArmRun(
        val arm: String,
        val feasible: Boolean,
        val finalObjective: Double?,
        val proven: Boolean,
        val timeToBestMs: Long?,
    )

    /** Every arm's run on one optimize instance, with the per-instance direction. */
    data class Instance(val problem: String, val maximize: Boolean, val runs: List<ArmRun>)

    /** Per-arm scores: the summed win share and the count of problems won. */
    data class ArmScore(val arm: String, val winShare: Double, val wins: Int)

    /** One slot of the recalibrated palette: [newlyCovered] is the arm's marginal contribution (new
     *  problems it wins that no earlier slot did), [cumulativeCovered] the running total. */
    data class DiverseSlot(val rank: Int, val arm: String, val newlyCovered: Int, val cumulativeCovered: Int)

    /** The calibration outcome over [totalWon] discriminating problems — take the first k palette
     *  slots for a diverse k-arm portfolio. */
    data class Report(
        val instances: Int,
        val complete: Boolean,
        val totalWon: Int,
        val scores: List<ArmScore>,
        val diverse: List<DiverseSlot>,
    )

    private const val EPS = 1e-9

    /** The win-ranking key (higher is better, compared lexicographically). Infeasible sorts worst; in
     *  [complete] mode a proved optimum and then a faster time break ties the way the Challenge does. */
    private fun rankKey(inst: Instance, run: ArmRun, complete: Boolean): DoubleArray {
        val solved = if (run.feasible) 1.0 else 0.0
        val quality = if (run.feasible && run.finalObjective != null) {
            if (inst.maximize) run.finalObjective else -run.finalObjective
        } else {
            Double.NEGATIVE_INFINITY
        }
        return if (complete) {
            val proven = if (run.feasible && run.proven) 1.0 else 0.0
            val faster = -(run.timeToBestMs?.toDouble() ?: Double.MAX_VALUE)
            doubleArrayOf(solved, proven, quality, faster)
        } else {
            doubleArrayOf(solved, quality)
        }
    }

    private fun lexCompare(a: DoubleArray, b: DoubleArray): Int {
        for (i in a.indices) {
            if (a[i] == b[i]) continue
            val d = a[i] - b[i]
            if (d.isFinite() && abs(d) <= EPS) continue
            return if (a[i] < b[i]) -1 else 1
        }
        return 0
    }

    /** The arms that win [inst]: those maximal on the [complete] ranking key (ties shared). Empty when
     *  no arm is feasible, or when every arm ties (non-discriminating). */
    private fun winnersOf(inst: Instance, complete: Boolean): Set<String> {
        val keys = inst.runs.associate { it.arm to rankKey(inst, it, complete) }
        val best = keys.values.reduce { a, b -> if (lexCompare(a, b) >= 0) a else b }
        if (best[0] == 0.0) return emptySet() // no feasible arm
        val winners = keys.filterValues { lexCompare(it, best) == 0 }.keys
        return if (winners.size == inst.runs.size) emptySet() else winners
    }

    /** Score and recalibrate [instances] under the [complete] (CP) or incomplete (LS) Challenge rules. */
    fun score(instances: List<Instance>, complete: Boolean): Report {
        val arms = instances.flatMap { inst -> inst.runs.map { it.arm } }.distinct()
        val won = instances.map { winnersOf(it, complete) }.filter { it.isNotEmpty() }

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
        return Report(instances.size, complete, won.size, scores, greedyDiverse(arms, won, winShare))
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
        val mode = if (report.complete) "complete" else "incomplete"
        appendLine(
            "=== arm calibration ($mode): ${report.instances} instances, ${report.totalWon} discriminating ===",
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
