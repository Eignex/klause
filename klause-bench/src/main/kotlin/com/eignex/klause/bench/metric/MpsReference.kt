package com.eignex.klause.bench.metric

import com.eignex.klause.formats.mps.MpsModel
import kotlin.math.abs
import kotlin.math.max

/** One run of an MPS reference solver: what it printed and claimed, how its claim checked, and how long it took. */
internal data class MpsAttempt(
    /** Which run this was: `default`, or the setting a retry changed, such as `presolve off`. */
    val label: String,
    val stdout: String,
    val elapsedMs: Long,
    val command: String,
    val claim: MpsWitness.Claim,
    val verdict: MpsWitness.Verdict,
    /** What the solver said about its own run beyond the claim: its status and solution status as printed. */
    val reported: Map<String, String>,
)

/**
 * The reference result of one or more [MpsAttempt]s on a model, shared by [ScipReference] and [HighsReference]. Only a
 * checked witness makes a problem feasible, and the best one across the attempts is the row's objective. An optimum
 * stays proven only when no other attempt's checked witness beats it, and an infeasibility claim only when no attempt
 * found a checked witness: a retry that contradicts the first run refutes it rather than being outvoted.
 */
internal object MpsReference {
    /** Judge [claim] on [model]; a model the bench cannot parse leaves every solution unchecked, so unknown. */
    fun judge(model: MpsModel?, claim: MpsWitness.Claim, repair: MpsWitness.Repair?): MpsWitness.Verdict =
        if (model != null) {
            MpsWitness.judge(model, claim, repair)
        } else {
            val infeasible = claim.status == MpsWitness.Status.INFEASIBLE
            MpsWitness.Verdict(
                if (infeasible) false else null, null, infeasible, null,
                mapOf("claimedStatus" to claim.status.name.lowercase(), "validation" to "unresolved: the model did not parse"),
            )
        }

    fun result(
        attempts: List<MpsAttempt>,
        model: MpsModel?,
        maximize: Boolean,
        identity: String,
        options: String,
    ): SolverInvocation.Result {
        val witnesses = attempts.filter { it.verdict.feasible == true && it.verdict.objective != null }
        val best = witnesses.reduceOrNull { a, b -> if (better(b.verdict.objective!!, a.verdict.objective!!, maximize)) b else a }
        val refuting = attempts.filter { it.claim.status == MpsWitness.Status.INFEASIBLE }.takeIf { best != null }.orEmpty()
        val chosen = best ?: attempts.firstOrNull { it.verdict.feasible == false } ?: attempts.last()
        val beaten = best != null && best.verdict.proven &&
            witnesses.any { better(it.verdict.objective!!, best.verdict.objective!!, maximize) }
        val proven = chosen.verdict.proven && !beaten
        val totalMs = attempts.sumOf { it.elapsedMs }
        val firstFeasibleMs = best?.let { attempts.takeWhile { a -> a !== best }.sumOf { a -> a.elapsedMs } + best.elapsedMs }
        val stats = LinkedHashMap<String, String>()
        stats["solveTime"] = (totalMs / MS_PER_SEC).toString()
        stats["maximize"] = maximize.toString()
        stats["referenceVersion"] = identity
        stats["options"] = options
        stats += chosen.reported
        stats += chosen.verdict.stats
        stats["attempt"] = chosen.label
        stats["attempts"] = attempts.joinToString("; ") { a -> "${a.label}: ${a.verdict.stats["claimedStatus"]}, ${a.verdict.stats["validation"]}" }
        if (refuting.isNotEmpty()) {
            stats["refuted"] = "${refuting.joinToString { it.label }} claimed infeasible; ${best!!.label} found a checked solution"
        }
        if (beaten) stats["proof"] = "rejected: another attempt found a better checked solution"
        val outcome = chosen.verdict.outcome
        return SolverInvocation.Result(
            feasible = chosen.verdict.feasible,
            objective = chosen.verdict.objective,
            timeToBestMs = firstFeasibleMs,
            timeToFirstFeasibleMs = firstFeasibleMs,
            proven = proven,
            stats = stats,
            rawOutput = attempts.joinToString("\n") { "### ${it.label}: ${it.command}\n${it.stdout}" },
            command = attempts.joinToString(" ; ") { it.command },
            assignment = when {
                outcome is MpsWitness.Outcome.Valid && model != null -> MpsWitness.assignmentText(model, outcome.values)
                else -> chosen.claim.assignment?.entries?.joinToString("\n", postfix = "\n") { "${it.key} ${it.value}" }
            },
        )
    }

    /** Whether [x] is a better objective than [than] beyond the checker's tolerance. */
    private fun better(x: Double, than: Double, maximize: Boolean): Boolean {
        val margin = MpsWitness.FEAS_TOL * max(1.0, max(abs(x), abs(than)))
        return if (maximize) x > than + margin else x < than - margin
    }

    private const val MS_PER_SEC = 1000.0
}
