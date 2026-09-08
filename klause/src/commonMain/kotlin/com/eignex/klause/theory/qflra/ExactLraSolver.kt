package com.eignex.klause.theory.qflra

import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.linearRows
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.RationalFeasibility
import com.eignex.klause.simplex.exact.bigRationalOutcome
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
    init {
        require(model.supportsExactLra()) {
            "exact LRA search requires a pure-real linear source model"
        }
    }

    override fun check(bools: BooleanArray, context: TheoryContext): TheoryCheck<ExactLraAssignment> {
        if (model.factors.any { factor ->
                factor.linearForm is LinearForm.Disjunction || factor.linearRows.any { row ->
                    row.relation == LinearOp.NE ||
                        (row.relation == LinearOp.EQ && row.activator != LinearRow.ALWAYS && !bools[row.activator])
                }
            }
        ) {
            return when (val result = checkExactLinear(model, bools, context)) {
                is TheoryCheck.Sat -> TheoryCheck.Sat(
                    ExactLraAssignment(result.assignment.bools, result.assignment.reals),
                )

                is TheoryCheck.Infeasible -> TheoryCheck.Infeasible(result.explanation)

                TheoryCheck.Cancelled -> TheoryCheck.Cancelled
            }
        }
        if (!context.consumeCheck()) return TheoryCheck.Cancelled
        val relaxation = QfLraSystem(model).build(bools)
        val outcome = bigRationalOutcome(relaxation, Cancellation(context::cancelled), maxPivots = Int.MAX_VALUE)
        return when (outcome.feasibility) {
            RationalFeasibility.FEASIBLE -> {
                val witness = requireNotNull(outcome.witness)
                TheoryCheck.Sat(
                    ExactLraAssignment(
                        bools.copyOf(),
                        List(model.numRealVars) { real ->
                            witness[real] - witness[model.numRealVars + real]
                        },
                    ),
                )
            }

            RationalFeasibility.UNKNOWN -> TheoryCheck.Cancelled

            RationalFeasibility.INFEASIBLE -> TheoryCheck.Infeasible()
        }
    }
}
