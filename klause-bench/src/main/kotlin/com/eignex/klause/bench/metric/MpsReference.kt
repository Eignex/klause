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
 * stays proven only when no other attempt's checked witness beats it, and an infeasibility claim only when no other
 * attempt claimed a solution at all: a retry that contradicts the first run refutes it rather than being outvoted.
 */
internal object MpsReference {
    /** Judge [claim] on [model]; a model the bench cannot parse leaves every solution unchecked, so unknown. */
    fun judge(model: MpsModel?, claim: MpsWitness.Claim, repair: MpsWitness.Repair?): MpsWitness.Verdict =
        if (model != null) {
            MpsWitness.judge(model, claim, repair)
        } else {
            val infeasible = claim.status == MpsWitness.Status.INFEASIBLE
            MpsWitness.Verdict(
                if (infeasible) false else null,
                null,
                infeasible,
                null,
                mapOf(
                    "claimedStatus" to claim.status.name.lowercase(),
                    "validation" to "unresolved: the model did not parse",
                ),
            )
        }

    fun result(
        attempts: List<MpsAttempt>,
        model: MpsModel?,
        maximize: Boolean,
        identity: String,
        options: String,
    ): SolverInvocation.Result {
        val witnesses = attempts.mapNotNull { a ->
            a.verdict.objective?.takeIf { a.verdict.feasible == true }?.let { a to it }
        }
        val best = witnesses.reduceOrNull { a, b -> if (better(b.second, a.second, maximize)) b else a }
        val infeasible = attempts.filter { it.claim.status == MpsWitness.Status.INFEASIBLE }
        // A solver that claims a solution in one run and infeasibility in another contradicts itself: even when the
        // solution fails the check, its infeasibility is not a proof to rely on.
        val claimingSolution = attempts.filter { it !in infeasible && claimsSolution(it.claim) }
        val contradicted = best == null && infeasible.isNotEmpty() && claimingSolution.isNotEmpty()
        val chosen = best?.first
            ?: infeasible.firstOrNull()?.takeUnless { contradicted }
            ?: attempts.last { it !in infeasible }
        val beaten = best != null && best.first.verdict.proven &&
            witnesses.any { better(it.second, best.second, maximize) }
        val stats = LinkedHashMap<String, String>()
        stats["solveTime"] = (attempts.sumOf { it.elapsedMs } / MS_PER_SEC).toString()
        stats["maximize"] = maximize.toString()
        stats["referenceVersion"] = identity
        stats["options"] = options
        stats += chosen.reported
        stats += chosen.verdict.stats
        stats["attempt"] = chosen.label
        stats["attempts"] = attempts.joinToString("; ") { a ->
            "${a.label}: ${a.verdict.stats["claimedStatus"]}, ${a.verdict.stats["validation"]}"
        }
        if (best != null && infeasible.isNotEmpty()) {
            stats["refuted"] = "${labels(infeasible)} claimed infeasible; ${best.first.label} found a checked solution"
        }
        if (contradicted) {
            val claimants = labels(claimingSolution)
            stats["contradicted"] = "${labels(infeasible)} claimed infeasible; $claimants claimed a solution"
        }
        if (beaten) stats["proof"] = "rejected: another attempt found a better checked solution"
        val firstFeasibleMs = best?.let { (found, _) ->
            attempts.takeWhile { it !== found }.sumOf { it.elapsedMs } + found.elapsedMs
        }
        return SolverInvocation.Result(
            feasible = chosen.verdict.feasible,
            objective = chosen.verdict.objective,
            elapsedMs = attempts.sumOf { it.elapsedMs },
            timeToBestMs = firstFeasibleMs,
            timeToFirstFeasibleMs = firstFeasibleMs,
            proven = chosen.verdict.proven && !beaten,
            stats = stats,
            rawOutput = attempts.joinToString("\n") { "### ${it.label}: ${it.command}\n${it.stdout}" },
            command = attempts.joinToString(" ; ") { it.command },
            assignment = assignment(chosen, model),
        )
    }

    private fun claimsSolution(claim: MpsWitness.Claim) =
        claim.assignment != null || claim.status == MpsWitness.Status.OPTIMAL

    private fun labels(attempts: List<MpsAttempt>) = attempts.joinToString { it.label }

    /** The checked solution as `name value` lines, or the one the solver claimed when it did not check. */
    private fun assignment(chosen: MpsAttempt, model: MpsModel?): String? {
        val outcome = chosen.verdict.outcome
        return if (outcome is MpsWitness.Outcome.Valid && model != null) {
            MpsWitness.assignmentText(model, outcome.values)
        } else {
            chosen.claim.assignment?.entries?.joinToString("\n", postfix = "\n") { "${it.key} ${it.value}" }
        }
    }

    /** Whether [x] is a better objective than [than] beyond the checker's tolerance. */
    private fun better(x: Double, than: Double, maximize: Boolean): Boolean {
        val margin = MpsWitness.FEAS_TOL * max(1.0, max(abs(x), abs(than)))
        return if (maximize) x > than + margin else x < than - margin
    }

    private const val MS_PER_SEC = 1000.0
}
