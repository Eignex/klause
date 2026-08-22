package com.eignex.klause.theory.qflra

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.RationalFeasibility
import com.eignex.klause.simplex.exact.bigRationalOutcome
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.ProblemSpec
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.solver.supportsExactLra
import com.eignex.klause.theory.TheoryParams

/** An exact QF_LRA assignment, independent of the finite CP [com.eignex.klause.solver.Sample]. */
data class ExactLraAssignment(
    /** Boolean values indexed by model Boolean variable id. */
    val bools: BooleanArray,
    /** Rational real values indexed by model real variable id. */
    val reals: List<BigFraction>,
)

/** Result of complete QF_LRA satisfiability search. */
sealed interface ExactLraResult {
    /** Statistics gathered while deciding the model. */
    val stats: SolveStats

    /** A satisfiable result with a rational witness. */
    data class Sat(
        /** The satisfying Boolean and real assignment. */
        val assignment: ExactLraAssignment,
        override val stats: SolveStats = SolveStats.EMPTY,
    ) : ExactLraResult

    /** A proof that every Boolean leaf is infeasible. */
    data class Unsat(override val stats: SolveStats = SolveStats.EMPTY) : ExactLraResult

    /** A search interrupted before it could determine satisfiability. */
    data class Unknown(
        /** The condition which stopped the search. */
        val reason: TerminationReason,
        override val stats: SolveStats = SolveStats.EMPTY,
    ) : ExactLraResult
}

/**
 * Exact QF_LRA satisfiability over the source model's Boolean skeleton and continuous columns.
 *
 * Each Boolean leaf emits only its active real atoms into the existing LP assembler, then the exact
 * rational simplex decides that conjunction. The finite CP and double-simplex lanes are never entered.
 */
class ExactLraSolver(private val model: ProblemSpec) {
    init {
        require(model.supportsExactLra()) {
            "exact LRA search requires a pure-real linear source model"
        }
    }

    /** Decides the model subject to the supplied search budgets and cancellation signal. */
    fun solve(params: TheoryParams = TheoryParams()): ExactLraResult {
        val cancellation = Cancellation { params.cancellation() || model.cancellation() }
        val bools = BooleanArray(model.numBoolVars)
        val system = QfLraSystem(model)
        var leavesLeft = params.maxLeaves
        while (true) {
            if (leavesLeft == 0L) {
                return ExactLraResult.Unknown(TerminationReason.BudgetExhausted)
            }
            if (cancellation()) return ExactLraResult.Unknown(TerminationReason.Cancelled)
            leavesLeft--
            if (!clausesHold(bools)) {
                if (!nextAssignment(bools)) return ExactLraResult.Unsat()
                continue
            }
            val relaxation = system.build(bools)
            val outcome = bigRationalOutcome(
                relaxation.model,
                cancellation,
                maxPivots = Int.MAX_VALUE,
            )
            when (outcome.feasibility) {
                RationalFeasibility.FEASIBLE -> return ExactLraResult.Sat(
                    ExactLraAssignment(bools.copyOf(), reals(model.numRealVars, relaxation, outcome.witness!!)),
                )

                RationalFeasibility.UNKNOWN -> return ExactLraResult.Unknown(TerminationReason.Cancelled)

                RationalFeasibility.INFEASIBLE -> {
                    if (!nextAssignment(bools)) return ExactLraResult.Unsat()
                }
            }
        }
    }

    private fun reals(
        count: Int,
        relaxation: QfLraLeaf,
        witness: List<BigFraction>,
    ): List<BigFraction> {
        val result = MutableList(count) { BigFraction.ZERO }
        for (column in relaxation.realId.indices) {
            val real = relaxation.realId[column]
            val value = witness[column]
            result[real] = if (relaxation.realSign[column] > 0) result[real] + value else result[real] - value
        }
        return result
    }

    private fun clausesHold(bools: BooleanArray): Boolean = model.factors.filterIsInstance<Clause>().all { clause ->
        clause.literals.any { Lit.evaluate(it, bools[Lit.variable(it)]) }
    }

    private fun nextAssignment(bools: BooleanArray): Boolean {
        var bit = bools.lastIndex
        while (bit >= 0 && bools[bit]) {
            bools[bit] = false
            bit--
        }
        if (bit < 0) return false
        bools[bit] = true
        return true
    }
}
