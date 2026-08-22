package com.eignex.klause.backtrack

import com.eignex.klause.lp.BigFraction
import com.eignex.klause.lp.RationalFeasibility
import com.eignex.klause.lp.bigRationalOutcome
import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.lp.relaxation.RelaxationDomains
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.ProblemSpec
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.solver.supportsExactLra

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
    fun solve(params: BacktrackParams = BacktrackParams()): ExactLraResult {
        val problem = model.materializeFiniteBounds()
        val bools = BooleanArray(model.numBoolVars)
        var instructionsLeft = minOf(params.maxDecisions, params.maxInstructions ?: Long.MAX_VALUE)
        while (true) {
            if (instructionsLeft == 0L || params.nodeBudget?.exhausted() == true) {
                return ExactLraResult.Unknown(TerminationReason.BudgetExhausted)
            }
            if (params.cancellation()) return ExactLraResult.Unknown(TerminationReason.Cancelled)
            instructionsLeft--
            params.nodeBudget?.spend()
            val relaxation = CpToLpRelaxation(problem, null).build(PinnedBools(bools))
            val outcome = bigRationalOutcome(
                relaxation.model,
                Cancellation { params.cancellation() },
                maxPivots = Int.MAX_VALUE,
            )
            when (outcome.feasibility) {
                RationalFeasibility.FEASIBLE -> return ExactLraResult.Sat(
                    ExactLraAssignment(bools.copyOf(), reals(problem.numRealVars, relaxation, outcome.witness!!)),
                )

                RationalFeasibility.UNKNOWN -> return ExactLraResult.Unknown(TerminationReason.Cancelled)

                RationalFeasibility.INFEASIBLE -> {
                    var bit = bools.lastIndex
                    while (bit >= 0 && bools[bit]) {
                        bools[bit] = false
                        bit--
                    }
                    if (bit < 0) return ExactLraResult.Unsat()
                    bools[bit] = true
                }
            }
        }
    }

    private fun reals(
        count: Int,
        relaxation: com.eignex.klause.lp.relaxation.LpRelaxation,
        witness: List<BigFraction>,
    ): List<BigFraction> {
        val result = MutableList(count) { BigFraction.ZERO }
        for (column in relaxation.colRealId.indices) {
            val real = relaxation.colRealId[column]
            if (real >= 0) {
                val value = witness[column]
                result[real] = if (relaxation.colRealSign[column] > 0) result[real] + value else result[real] - value
            }
        }
        return result
    }

    private class PinnedBools(private val bools: BooleanArray) : RelaxationDomains {
        override fun intDomain(varId: Int): IntDomain = error("pure-real exact LRA has no integer domains")

        override fun boolValue(varId: Int): Boolean = bools[varId]
    }
}
