package com.eignex.klause.bench.metric

/**
 * Fair-tester scoring, aimed at a **complementary, diverse** arm set rather than the single best arm.
 * Each arm is run in **isolation** (one subprocess, full budget, no shared incumbent), so its result
 * on a problem is purely its own. Then, looking at each problem individually:
 *
 *  - Arms compete under two lenses, one per axis we care about: **objective quality** (best final
 *    objective) and **feasibility speed** (fastest to a first feasible solution). The best arm under a
 *    lens *wins* that problem-lens (ties shared).
 *  - An arm's headline score is its summed **win share** — `1/(number of co-winners)` for every
 *    problem-lens it wins, summed across all of them. A clean sole win scores 1, a loser scores 0, so
 *    wins score highly and *unique* wins (the complementary ones) score highest.
 *  - The **diverse palette** is a greedy set-cover over the (problem × lens) units: each slot goes to
 *    the arm covering the most still-uncovered units. This is the credit-system algorithm, fed by
 *    per-problem winners — it keeps arms that win where others don't (objective specialists *and*
 *    fast-feasible specialists) and drops arms whose wins are all covered by a kept arm.
 */
internal object ArmCalibration {

    /** One arm's isolated run on one optimize instance (model-oriented [finalObjective]). */
    data class ArmRun(val arm: String, val feasible: Boolean, val finalObjective: Double?, val timeToFeasibleMs: Long?)

    /** Every arm's run on one optimize instance, with the per-instance direction. */
    data class Instance(val problem: String, val maximize: Boolean, val runs: List<ArmRun>)

    /** The two competition lenses — the axes a diverse set should span. */
    enum class Lens { QUALITY, SPEED }

    /** Per-arm scores: the summed win share plus the per-lens win counts. */
    data class ArmScore(val arm: String, val winShare: Double, val qualityWins: Int, val speedWins: Int)

    /** One slot of the recalibrated palette: [newlyCovered] is the arm's marginal contribution (new
     *  problem-lens units it wins that no earlier slot did), [cumulativeCovered] the running total. */
    data class DiverseSlot(val rank: Int, val arm: String, val newlyCovered: Int, val cumulativeCovered: Int)

    /** The calibration outcome: per-arm scores (win-share desc) and the marginal-contribution palette
     *  over [totalUnits] problem-lens units — take the first k slots for a diverse k-arm portfolio. */
    data class Report(
        val instances: Int,
        val totalUnits: Int,
        val scores: List<ArmScore>,
        val diverse: List<DiverseSlot>,
    )

    private const val EPS = 1e-9

    /** One problem under one lens: the arms that tie for the win (empty when no arm was feasible). */
    private data class WinUnit(val winners: Set<String>)

    /** Lower-is-better value of [run] under [lens]; an infeasible arm is worst (never a winner). */
    private fun value(inst: Instance, run: ArmRun, lens: Lens): Double {
        if (!run.feasible) return Double.MAX_VALUE
        return when (lens) {
            Lens.QUALITY -> (if (inst.maximize) -1.0 else 1.0) * (run.finalObjective ?: return Double.MAX_VALUE)
            Lens.SPEED -> (run.timeToFeasibleMs ?: return Double.MAX_VALUE).toDouble()
        }
    }

    private fun winnersOf(inst: Instance, lens: Lens): Set<String> {
        val values = inst.runs.associate { it.arm to value(inst, it, lens) }
        val best = values.values.min()
        if (best == Double.MAX_VALUE) return emptySet() // no feasible arm — non-discriminating
        return values.filterValues { it <= best + EPS }.keys
    }

    /** Score and recalibrate [instances] (optimize instances only). */
    fun score(instances: List<Instance>): Report {
        val arms = instances.flatMap { inst -> inst.runs.map { it.arm } }.distinct()
        val units = instances.flatMap { inst -> Lens.entries.map { WinUnit(winnersOf(inst, it)) } }
            .filter { it.winners.isNotEmpty() }

        val winShare = HashMap<String, Double>()
        for (unit in units) {
            val share = 1.0 / unit.winners.size
            for (arm in unit.winners) winShare[arm] = (winShare[arm] ?: 0.0) + share
        }
        val qualityWins = winCounts(instances, Lens.QUALITY)
        val speedWins = winCounts(instances, Lens.SPEED)

        val scores = arms.map { arm ->
            ArmScore(arm, winShare[arm] ?: 0.0, qualityWins[arm] ?: 0, speedWins[arm] ?: 0)
        }.sortedByDescending { it.winShare }

        return Report(instances.size, units.size, scores, greedyDiverse(arms, units, winShare))
    }

    private fun winCounts(instances: List<Instance>, lens: Lens): Map<String, Int> {
        val counts = HashMap<String, Int>()
        for (inst in instances) for (arm in winnersOf(inst, lens)) counts[arm] = (counts[arm] ?: 0) + 1
        return counts
    }

    /** Rank *every* arm by greedy marginal contribution over the (problem × lens) [units]: each slot
     *  goes to the arm winning the most still-uncovered units, ties broken by total win share. Ranking
     *  continues past full coverage (those tail slots add 0), so any prefix of length k is a diverse
     *  k-arm portfolio and the cumulative-coverage curve shows where the returns flatten. */
    private fun greedyDiverse(
        arms: List<String>,
        units: List<WinUnit>,
        winShare: Map<String, Double>,
    ): List<DiverseSlot> {
        val covered = BooleanArray(units.size)
        val taken = HashSet<String>()
        val palette = ArrayList<DiverseSlot>()
        var cumulative = 0
        while (taken.size < arms.size) {
            var bestArm: String? = null
            var bestCover = -1
            var bestShare = -1.0
            for (arm in arms) {
                if (arm in taken) continue
                val cover = units.indices.count { !covered[it] && arm in units[it].winners }
                val share = winShare[arm] ?: 0.0
                if (cover > bestCover || (cover == bestCover && share > bestShare)) {
                    bestArm = arm
                    bestCover = cover
                    bestShare = share
                }
            }
            val arm = bestArm ?: break
            units.indices.forEach { if (arm in units[it].winners && !covered[it]) covered[it] = true }
            cumulative += bestCover
            taken += arm
            palette += DiverseSlot(palette.size + 1, arm, bestCover, cumulative)
        }
        return palette
    }

    /** Render a [Report] as a plain-text table for the bench console. */
    fun render(report: Report): String = buildString {
        appendLine(
            "=== arm calibration  (${report.instances} optimize instances; win share over quality+speed lenses) ===",
        )
        appendLine("--- win share | objective-quality wins | feasibility-speed wins ---")
        for (s in report.scores) {
            appendLine("  ${s.arm.padEnd(28)} ${fmt(s.winShare)}  ${s.qualityWins}  ${s.speedWins}")
        }
        appendLine("")
        appendLine("--- marginal-contribution ranking (take the first k for a diverse k-arm set) ---")
        for (slot in report.diverse) {
            appendLine(
                "  ${slot.rank.toString().padStart(2)}  ${slot.arm.padEnd(28)} " +
                    "+covered=${slot.newlyCovered}  (${slot.cumulativeCovered}/${report.totalUnits})",
            )
        }
    }

    private fun fmt(value: Double): String = ((value * 100).toLong() / 100.0).toString().padStart(7)
}
