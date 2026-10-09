package com.eignex.klause.bench.metric

import com.eignex.klause.formats.mps.MpsConstraint
import com.eignex.klause.formats.mps.MpsModel
import com.eignex.klause.ir.ObjectiveSense
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.round

/**
 * Checks an MPS reference solver's claim against the model it solved, so a reference row says only what the model
 * itself confirms. A solver's own feasibility report is not enough: it judges its presolved, scaled model under its
 * own tolerances, and a binary at 8e-7 times a 1e6 coefficient passes as zero while carrying 0.8 units through a row.
 *
 * A witness is checked as a MIP checker checks it: every integer variable rounded and fixed, then every bound and
 * row of the original model recomputed under [FEAS_TOL]. One that fails is repaired once, by solving the LP over the
 * continuous variables with the integers fixed ([Repair]); the repaired assignment is checked again from scratch.
 * The objective a row records is the one recomputed from the checked assignment, never the solver's.
 */
internal object MpsWitness {
    /** The adapter and validation rules, part of every MPS reference's cache identity and lab row: a change to how
     *  claims are judged makes earlier rows stale. */
    const val VERSION = "mps-validate-3"

    /** Row and bound violations allowed, relative to the bound's magnitude (absolute below one): the usual MIP checker
     *  tolerance, looser than the solvers' own so a correct solution never fails on print precision. */
    const val FEAS_TOL = 1e-6

    /** How close the primal and dual bounds must be for an optimum to count as proven rather than gap-limited. */
    private const val GAP_REL = 1e-9
    private const val GAP_ABS = 1e-6

    /** Floating-point error allowed in a row's activity, relative to the sum of its terms' magnitudes: a row of
     *  large terms cancels to a value carrying their rounding, which is no violation of the model. */
    private const val ROUNDING = 1e-12

    /** What a solver reports when it stops. */
    enum class Status { OPTIMAL, INFEASIBLE, LIMIT, UNKNOWN }

    /** A solver's report: its status, bounds and gap as printed, and the assignment it returned (by column name). */
    data class Claim(
        val status: Status,
        val primal: Double?,
        val dual: Double?,
        val gap: Double?,
        val assignment: Map<String, Double>?,
    )

    /** Solves the LP over a model's continuous variables, its integers fixed: values by column name, or why not. */
    fun interface Repair {
        fun solve(lp: String): RepairResult
    }

    sealed interface RepairResult {
        data class Solved(val values: Map<String, Double>) : RepairResult
        data object Infeasible : RepairResult
        data class Failed(val why: String) : RepairResult
    }

    /** Violations of one assignment, each the largest over its kind. */
    data class Violations(val bound: Double, val row: Double, val integrality: Double) {
        val ok: Boolean get() = bound <= 0.0 && row <= 0.0
    }

    sealed interface Outcome {
        /** The assignment (rounded, possibly repaired) satisfies the model; [objective] is recomputed from it. */
        data class Valid(
            val objective: Double,
            val values: DoubleArray,
            val repaired: Boolean,
            val claimed: Violations,
        ) : Outcome

        /** The model rejects the assignment, and no continuous completion of its integers exists. */
        data class Invalid(val why: String, val claimed: Violations) : Outcome

        /** Neither confirmed nor refuted: no assignment, or the repair could not decide. */
        data class Unresolved(val why: String) : Outcome
    }

    /** What a claim amounts to once checked: the fields a reference row records. */
    data class Verdict(
        val feasible: Boolean?,
        val objective: Double?,
        val proven: Boolean,
        val outcome: Outcome?,
        val stats: Map<String, String>,
    )

    /**
     * Judge [claim] on [model]. Infeasibility cannot be checked from a witness and stands as claimed; the lab sets a
     * disputed one aside when another solver's checked witness contradicts it. A solution counts only once checked,
     * and an optimum is proven only when the solver claimed one, its witness checks, the recomputed objective matches
     * its primal bound, and its dual bound meets the primal bound: an optimum within a relative gap tolerance (HiGHS
     * stops at 0.01% by default) is a solution, not a proof.
     */
    fun judge(model: MpsModel, claim: Claim, repair: Repair?): Verdict {
        val stats = linkedMapOf("claimedStatus" to claim.status.name.lowercase(), "validation" to "none")
        claim.primal?.let { stats["claimedPrimal"] = it.toString() }
        claim.dual?.let { stats["dualBound"] = it.toString() }
        claim.gap?.let { stats["gap"] = it.toString() }
        if (claim.status == Status.INFEASIBLE) return Verdict(false, null, true, null, stats)
        val values = claim.assignment ?: run {
            if (claim.primal !=
                null
            ) {
                stats["validation"] = "unresolved: the solver reported a primal bound without a solution"
            }
            return Verdict(null, null, false, null, stats)
        }
        val outcome = validate(model, values, repair)
        stats += describe(outcome)
        val valid = outcome as? Outcome.Valid ?: return Verdict(null, null, false, outcome, stats)
        val maximize = model.sense == ObjectiveSense.MAXIMIZE
        val matchesPrimal = claim.primal != null && close(valid.objective, claim.primal, GAP_REL * 100, GAP_ABS)
        val dual = claim.dual
        // A dual bound that excludes the solution it was reported with contradicts itself.
        val bounded = dual == null || if (maximize) {
            dual >= valid.objective - tolerance(
                valid.objective,
            )
        } else {
            dual <= valid.objective + tolerance(valid.objective)
        }
        val closed = claim.primal != null && dual != null && close(claim.primal, dual, GAP_REL, GAP_ABS)
        val proven = claim.status == Status.OPTIMAL && matchesPrimal && bounded && closed
        when {
            claim.status != Status.OPTIMAL -> {}

            !bounded -> stats["proof"] =
                "rejected: the dual bound $dual excludes the checked solution ${valid.objective}"

            !matchesPrimal -> stats["proof"] =
                "rejected: the checked objective ${valid.objective} differs from the reported ${claim.primal}"

            !closed -> stats["proof"] =
                "gap-limited: stopped within its gap tolerance, primal ${claim.primal}, dual $dual"

            else -> stats["proof"] = "optimal"
        }
        return Verdict(true, valid.objective, proven, outcome, stats)
    }

    /** Check [values] on [model]: rounded integers first, then a continuous repair when they fail. */
    fun validate(model: MpsModel, values: Map<String, Double>, repair: Repair?): Outcome {
        val x = DoubleArray(model.variables.size) { values[model.variables[it].name] ?: 0.0 }
        val integers = model.variables.indices.filter { model.variables[it].integer }
        val integrality = integers.maxOfOrNull { abs(x[it] - round(x[it])) } ?: 0.0
        for (i in integers) x[i] = round(x[i])
        val claimed = violations(model, x).copy(integrality = integrality)
        if (claimed.ok) return Outcome.Valid(objective(model, x), x, repaired = false, claimed = claimed)
        if (integers.any { !within(x[it], model.variables[it].lower, model.variables[it].upper) }) {
            return Outcome.Invalid("a rounded integer lies outside its bounds", claimed)
        }
        if (model.variables.none { !it.integer }) {
            return Outcome.Invalid(
                "the rounded integers violate a row and there is no continuous variable to repair",
                claimed,
            )
        }
        repair ?: return Outcome.Unresolved(
            "the rounded assignment violates the model and no LP solver is available to repair it",
        )
        // The best completion first; when the solver finds none, the completion closest to feasible, which the
        // check then judges: whether one exists is the check's question, not the LP solver's feasibility tolerance.
        val best = (repair.solve(fixedLp(model, x)) as? RepairResult.Solved)?.let { completed(model, x, it.values) }
        if (best != null && violations(model, best).ok) {
            return Outcome.Valid(objective(model, best), best, repaired = true, claimed = claimed)
        }
        return when (val closest = repair.solve(fixedLp(model, x, elastic = true))) {
            is RepairResult.Solved -> judgeClosest(
                model,
                completed(model, x, closest.values),
                closest.values[ELASTIC],
                claimed,
            )

            is RepairResult.Infeasible -> Outcome.Unresolved("the repair LP found the variable bounds alone infeasible")

            is RepairResult.Failed -> Outcome.Unresolved("the repair LP did not decide: ${closest.why}")
        }
    }

    /** The closest completion [y], [gap] its largest scaled row violation: valid when the check accepts it. */
    private fun judgeClosest(model: MpsModel, y: DoubleArray, gap: Double?, claimed: Violations): Outcome = when {
        violations(model, y).ok -> Outcome.Valid(objective(model, y), y, repaired = true, claimed = claimed)

        gap != null && gap > FEAS_TOL -> Outcome.Invalid(
            "no completion of the rounded integers is within tolerance: the closest misses by $gap",
            claimed,
        )

        else -> Outcome.Unresolved("the closest completion still violates the model (${violations(model, y)})")
    }

    /** [x] with its continuous variables taken from a repair's [values]. */
    private fun completed(model: MpsModel, x: DoubleArray, values: Map<String, Double>): DoubleArray {
        val y = x.copyOf()
        for (i in model.variables.indices) if (!model.variables[i].integer) y[i] = values[col(i)] ?: 0.0
        return y
    }

    /** The checked assignment as `name value` lines, the way [ReferenceSolve] keeps a reference's witness. */
    fun assignmentText(model: MpsModel, values: DoubleArray): String =
        model.variables.indices.joinToString("\n", postfix = "\n") { "${model.variables[it].name} ${values[it]}" }

    /** Each violation above tolerance, in excess of it; zero when within. */
    internal fun violations(model: MpsModel, x: DoubleArray): Violations {
        var bound = 0.0
        for ((i, v) in model.variables.withIndex()) bound = max(bound, excess(x[i], v.lower, v.upper))
        var row = 0.0
        for (c in model.constraints) {
            val indicator = c.indicator
            if (indicator != null && round(x[indicator.column]) != if (indicator.whenOne) 1.0 else 0.0) continue
            var activity = 0.0
            var magnitude = 0.0
            for (k in c.indices.indices) {
                val term = c.coeffs[k] * x[c.indices[k]]
                activity += term
                magnitude += abs(term)
            }
            row = max(row, excess(activity, c.lower, c.upper, rounding = ROUNDING * magnitude))
        }
        return Violations(bound, row, 0.0)
    }

    /** How far [value] lies outside [lower, upper] beyond the tolerance and the [rounding] its computation carries. */
    private fun excess(value: Double, lower: Double?, upper: Double?, rounding: Double = 0.0): Double {
        val below = lower?.let { (it - value) - tolerance(it) - rounding } ?: 0.0
        val above = upper?.let { (value - it) - tolerance(it) - rounding } ?: 0.0
        return max(0.0, max(below, above))
    }

    private fun within(value: Double, lower: Double?, upper: Double?) = excess(value, lower, upper) <= 0.0

    private fun tolerance(bound: Double) = FEAS_TOL * max(1.0, abs(bound))

    private fun close(a: Double, b: Double, rel: Double, absolute: Double) = abs(
        a - b,
    ) <= max(absolute, rel * max(abs(a), abs(b)))

    private fun objective(model: MpsModel, x: DoubleArray): Double {
        var sum = model.objective.constant
        for (k in model.objective.indices.indices) sum += model.objective.coeffs[k] * x[model.objective.indices[k]]
        return sum
    }

    private fun describe(outcome: Outcome): Map<String, String> = when (outcome) {
        is Outcome.Valid -> mapOf(
            "validation" to if (outcome.repaired) "repaired" else "valid",
            "checkedObjective" to outcome.objective.toString(),
        ) + claimedViolations(outcome.claimed)

        is Outcome.Invalid -> mapOf("validation" to "invalid: ${outcome.why}") + claimedViolations(outcome.claimed)

        is Outcome.Unresolved -> mapOf("validation" to "unresolved: ${outcome.why}")
    }

    private fun claimedViolations(v: Violations) = mapOf(
        "boundViolation" to v.bound.toString(),
        "rowViolation" to v.row.toString(),
        "integralityViolation" to v.integrality.toString(),
    )

    /** Column [i]'s name in [fixedLp]: generated, since MPS names may hold what free MPS cannot. */
    private fun col(i: Int) = "c$i"

    /** The elastic LP's column: the largest violation of any row, relative to its bound as the check measures it. */
    internal const val ELASTIC = "t"

    /**
     * [model] as a free-format MPS LP with every integer variable fixed at its value in [x]: the rows an indicator
     * switches off at that value dropped, the rest kept, so its optimum is the best continuous completion. [elastic]
     * instead lets every row bound slip by [ELASTIC] times `max(1, |bound|)` and minimizes it: always feasible, its
     * optimum is the completion closest to the model by the check's own measure, and within [FEAS_TOL] exactly when a
     * completion the check accepts exists.
     */
    internal fun fixedLp(model: MpsModel, x: DoubleArray, elastic: Boolean = false): String = buildString {
        val sides = model.constraints.withIndex().filter { (_, c) ->
            val indicator = c.indicator
            indicator == null || round(x[indicator.column]) == if (indicator.whenOne) 1.0 else 0.0
        }.flatMap { (r, c) ->
            // One row per finite side, so each side slips on its own: a G row for a lower bound, an L row for an
            // upper one, an equality being both.
            listOfNotNull(c.lower?.let { Side("r${r}l", "G", it, c) }, c.upper?.let { Side("r${r}u", "L", it, c) })
        }
        appendLine("NAME fixed")
        appendLine("OBJSENSE")
        appendLine(if (!elastic && model.sense == ObjectiveSense.MAXIMIZE) "    MAX" else "    MIN")
        appendLine("ROWS")
        appendLine(" N obj")
        for (side in sides) appendLine(" ${side.type} ${side.name}")
        val entries = Array(model.variables.size) { ArrayList<String>() }
        if (!elastic) {
            for (k in model.objective.indices.indices) {
                entries[model.objective.indices[k]] +=
                    "obj ${model.objective.coeffs[k]}"
            }
        }
        for (side in sides) {
            for (k in side.row.indices.indices) {
                entries[side.row.indices[k]] +=
                    "${side.name} ${side.row.coeffs[k]}"
            }
        }
        appendLine("COLUMNS")
        for (i in model.variables.indices) {
            if (entries[i].isEmpty()) {
                appendLine(
                    "    ${col(i)} obj 0",
                )
            } else {
                for (e in entries[i]) appendLine("    ${col(i)} $e")
            }
        }
        if (elastic) {
            appendLine("    $ELASTIC obj 1")
            for (side in sides) {
                val slip = max(1.0, abs(side.bound))
                appendLine("    $ELASTIC ${side.name} ${if (side.type == "G") slip else -slip}")
            }
        }
        appendLine("RHS")
        for (side in sides) if (side.bound != 0.0) appendLine("    rhs ${side.name} ${side.bound}")
        appendLine("BOUNDS")
        for ((i, v) in model.variables.withIndex()) {
            when {
                v.integer -> appendLine(" FX bnd ${col(i)} ${x[i]}")

                v.lower == null && v.upper == null -> appendLine(" FR bnd ${col(i)}")

                else -> {
                    if (v.lower == null) appendLine(" MI bnd ${col(i)}") else appendLine(" LO bnd ${col(i)} ${v.lower}")
                    v.upper?.let { appendLine(" UP bnd ${col(i)} $it") }
                }
            }
        }
        appendLine("ENDATA")
    }

    /** One finite side of a model row in [fixedLp]: its row name, MPS type and bound. */
    private class Side(val name: String, val type: String, val bound: Double, val row: MpsConstraint)
}
