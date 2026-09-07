package com.eignex.klause.theory.qflra

import com.eignex.klause.ir.Problem
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.RationalFeasibility
import com.eignex.klause.simplex.exact.bigRationalOutcome
import com.eignex.klause.solver.result.SmtStatsSink
import com.eignex.klause.theory.Theory
import com.eignex.klause.theory.TheoryCheck
import com.eignex.klause.theory.TheoryContext
import com.eignex.klause.util.Cancellation

/** An exact QF_LRA assignment, independent of the finite CP [com.eignex.klause.solver.Sample]. */
data class ExactLraAssignment(
    /** Boolean values indexed by model Boolean variable id. */
    val bools: BooleanArray,
    /** Rational real values indexed by model real variable id. */
    val reals: List<BigFraction>,
)

/**
 * Exact QF_LRA satisfiability over the source model's Boolean skeleton and continuous columns.
 *
 * Each Boolean leaf emits only its active real atoms into the existing LP assembler, then the exact
 * rational simplex decides that conjunction. The finite CP and double-simplex lanes are never entered.
 */
class ExactLraSolver(override val model: Problem) : Theory<ExactLraAssignment> {
    private var smtStats: SmtStatsSink? = null

    init {
        require(model.supportsExactLra()) {
            "exact LRA search requires a pure-real linear source model"
        }
    }

    override fun check(bools: BooleanArray, context: TheoryContext): TheoryCheck<ExactLraAssignment> {
        if (!context.consumeCheck()) return TheoryCheck.Cancelled
        val cancellation = Cancellation(context::cancelled)
        val system = QfLraSystem(model)
        val relaxation = system.build(bools)
        val outcome = bigRationalOutcome(relaxation.model, cancellation, maxPivots = Int.MAX_VALUE, observer = smtStats)
        return when (outcome.feasibility) {
            RationalFeasibility.FEASIBLE -> {
                val telemetry = smtStats?.let { stats ->
                    relaxation.model.rowStrict.any { it }.also { strict ->
                        stats.observeWitnessCandidate(strict, wide = false)
                    }
                }
                TheoryCheck.Sat(
                    ExactLraAssignment(bools.copyOf(), reals(model.numRealVars, relaxation, outcome.witness!!)),
                ).also { telemetry?.let { strict -> smtStats?.observeWitnessAccepted(strict, wide = false) } }
            }

            RationalFeasibility.UNKNOWN -> TheoryCheck.Cancelled

            RationalFeasibility.INFEASIBLE -> TheoryCheck.Infeasible()
        }
    }

    /** Attach solve-scoped telemetry before this solver is checked. */
    internal fun observeWith(stats: SmtStatsSink) {
        smtStats = stats
    }

    private fun reals(count: Int, relaxation: QfLraLeaf, witness: List<BigFraction>): List<BigFraction> {
        val result = MutableList(count) { BigFraction.ZERO }
        for (column in relaxation.realId.indices) {
            val real = relaxation.realId[column]
            val value = witness[column]
            result[real] = if (relaxation.realSign[column] > 0) result[real] + value else result[real] - value
        }
        return result
    }
}
