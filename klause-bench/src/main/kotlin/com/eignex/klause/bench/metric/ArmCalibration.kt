package com.eignex.klause.bench.metric

/**
 * Fair-tester scoring. Each arm is run in **isolation** (one subprocess, full budget, no shared
 * incumbent), so its anytime incumbent stream is purely its own. Arms are scored two ways and then
 * recalibrated into a *diverse* palette rather than a best-aggregate ranking:
 *
 *  - **Borda anytime** over a normalized *primal integral* of each arm's incumbent curve — one number
 *    that rewards reaching feasibility sooner *and* improving the objective further (lower is better).
 *    The two sub-signals (time-to-feasible, final quality) are also Borda-scored so the
 *    feasible-fast vs objective-improving split stays visible.
 *  - **Diversity** via the credit system's greedy set-cover: an arm "covers" an instance when it is a
 *    per-instance primal-integral winner; each slot goes to the arm covering the most still-uncovered
 *    instances (ties broken by total anytime Borda). Omitted arms are redundant — some other kept arm
 *    wins everywhere they do.
 */
internal object ArmCalibration {

    /** One arm's isolated run on one instance. [incumbents] is the improving `(ms, objective)` stream
     *  (model-oriented objective); empty when the arm never reached a feasible solution. */
    data class ArmRun(
        val arm: String,
        val feasible: Boolean,
        val finalObjective: Double?,
        val timeToFeasibleMs: Long?,
        val incumbents: List<Incumbent>,
    )

    /** A single incumbent in an arm's anytime stream. */
    data class Incumbent(val ms: Long, val objective: Double)

    /** Every arm's run on one optimize instance, with the per-instance direction and budget. */
    data class Instance(val problem: String, val maximize: Boolean, val budgetMs: Long, val runs: List<ArmRun>)

    /** Per-arm scores: the combined anytime Borda plus the two sub-signal Bordas and win count. */
    data class ArmScore(
        val arm: String,
        val bordaAnytime: Double,
        val feasSpeedBorda: Double,
        val qualityBorda: Double,
        val instancesWon: Int,
    )

    /** One slot of the recalibrated diverse palette. */
    data class DiverseSlot(val rank: Int, val arm: String, val newlyCovered: Int)

    /** The calibration outcome: per-arm scores (anytime-Borda desc) and the diverse set-cover palette. */
    data class Report(val instances: Int, val scores: List<ArmScore>, val diverse: List<DiverseSlot>)

    private const val EPS = 1e-9

    /** Normalized primal integral of [run] on [inst] — the fraction of the budget spent away from the
     *  instance's best, with a full-gap penalty before first feasibility. 0 = instantly at the best,
     *  1 = never feasible. Direction-aware via [Instance.maximize]. */
    fun primalIntegral(inst: Instance, run: ArmRun): Double {
        if (!run.feasible || run.incumbents.isEmpty()) return 1.0
        val sign = if (inst.maximize) -1.0 else 1.0
        val feasible = inst.runs.filter { it.feasible && it.incumbents.isNotEmpty() }
        val best = feasible.mapNotNull { it.finalObjective }.minOf { sign * it }
        val worst = feasible.flatMap { it.incumbents }.maxOf { sign * it.objective }
        val denom = (worst - best).takeIf { it > EPS } ?: 1.0
        fun gap(objective: Double) = (((sign * objective) - best) / denom).coerceIn(0.0, 1.0)

        val budget = inst.budgetMs.toDouble().coerceAtLeast(1.0)
        var area = 0.0
        var prevMs = 0.0
        var prevGap = 1.0 // before the first incumbent, the full gap
        for (incumbent in run.incumbents.sortedBy { it.ms }) {
            val ms = incumbent.ms.toDouble().coerceIn(0.0, budget)
            area += prevGap * (ms - prevMs)
            prevMs = ms
            prevGap = gap(incumbent.objective)
        }
        area += prevGap * (budget - prevMs)
        return (area / budget).coerceIn(0.0, 1.0)
    }

    /** Pairwise Borda over one per-instance metric (lower is better): on each instance an arm scores 1
     *  vs each opponent it beats, 0.5 on a tie, summed across opponents and instances. */
    private fun borda(instances: List<Instance>, valueOf: (Instance, ArmRun) -> Double): Map<String, Double> {
        val points = HashMap<String, Double>()
        for (inst in instances) {
            val values = inst.runs.associate { it.arm to valueOf(inst, it) }
            for (a in inst.runs) {
                var p = 0.0
                for (b in inst.runs) {
                    if (a.arm == b.arm) continue
                    val va = values.getValue(a.arm)
                    val vb = values.getValue(b.arm)
                    p += if (va < vb - EPS) {
                        1.0
                    } else if (va > vb + EPS) {
                        0.0
                    } else {
                        0.5
                    }
                }
                points[a.arm] = (points[a.arm] ?: 0.0) + p
            }
        }
        return points
    }

    /** Score and recalibrate [instances] (only optimize instances with ≥1 arm should be passed). */
    fun score(instances: List<Instance>): Report {
        val arms = instances.flatMap { inst -> inst.runs.map { it.arm } }.distinct()
        val anytime = borda(instances) { inst, run -> primalIntegral(inst, run) }
        val feasSpeed = borda(instances) { _, run -> run.timeToFeasibleMs?.toDouble() ?: Double.MAX_VALUE }
        val quality = borda(instances) { inst, run ->
            val sign = if (inst.maximize) -1.0 else 1.0
            run.finalObjective?.takeIf { run.feasible }?.let { sign * it } ?: Double.MAX_VALUE
        }

        // Per-instance primal-integral winners (ties shared); instances no arm solved don't discriminate.
        val winners: List<Set<String>> = instances.map { inst ->
            if (inst.runs.none { it.feasible }) {
                emptySet()
            } else {
                val pi = inst.runs.associate { it.arm to primalIntegral(inst, it) }
                val min = pi.values.min()
                pi.filterValues { it <= min + EPS }.keys
            }
        }
        val wins = arms.associateWith { arm -> winners.count { arm in it } }

        val scores = arms.map { arm ->
            ArmScore(
                arm = arm,
                bordaAnytime = anytime[arm] ?: 0.0,
                feasSpeedBorda = feasSpeed[arm] ?: 0.0,
                qualityBorda = quality[arm] ?: 0.0,
                instancesWon = wins.getValue(arm),
            )
        }.sortedByDescending { it.bordaAnytime }

        return Report(instances.size, scores, greedyDiverse(arms, winners, anytime))
    }

    /** Greedy set-cover over per-instance [winners]: each slot goes to the arm covering the most
     *  still-uncovered instances, ties broken by total anytime Borda. The credit-system algorithm,
     *  fed by Borda-anytime winners instead of shared-race attribution. */
    private fun greedyDiverse(
        arms: List<String>,
        winners: List<Set<String>>,
        anytime: Map<String, Double>,
    ): List<DiverseSlot> {
        val covered = BooleanArray(winners.size)
        val taken = HashSet<String>()
        val palette = ArrayList<DiverseSlot>()
        while (true) {
            var bestArm: String? = null
            var bestCover = 0
            var bestBorda = -1.0
            for (arm in arms) {
                if (arm in taken) continue
                val cover = winners.indices.count { !covered[it] && arm in winners[it] }
                val tieBreak = anytime[arm] ?: 0.0
                if (cover > bestCover || (cover == bestCover && cover > 0 && tieBreak > bestBorda)) {
                    bestArm = arm
                    bestCover = cover
                    bestBorda = tieBreak
                }
            }
            if (bestArm == null || bestCover == 0) break
            winners.indices.forEach { if (bestArm in winners[it]) covered[it] = true }
            taken += bestArm
            palette += DiverseSlot(palette.size + 1, bestArm, bestCover)
        }
        return palette
    }

    /** Render a [Report] as a plain-text table for the bench console. */
    fun render(report: Report): String = buildString {
        appendLine("=== arm calibration  (${report.instances} optimize instances) ===")
        appendLine("--- Borda anytime (primal integral) | feasible-fast | objective-quality | wins ---")
        for (s in report.scores) {
            appendLine(
                "  ${s.arm.padEnd(28)} ${fmt(s.bordaAnytime)}  ${fmt(s.feasSpeedBorda)}  " +
                    "${fmt(s.qualityBorda)}  ${s.instancesWon}",
            )
        }
        appendLine("")
        appendLine("--- recalibrated diverse palette (greedy set cover; omitted arms are redundant) ---")
        for (slot in report.diverse) {
            appendLine("  ${slot.rank.toString().padStart(2)}  ${slot.arm.padEnd(28)} +covered=${slot.newlyCovered}")
        }
    }

    private fun fmt(value: Double): String {
        val rounded = (value * 100).toLong() / 100.0
        return rounded.toString().padStart(7)
    }
}
