package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.LS_INSTRUCTIONS_PER_WORK
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.CandidateCompletion
import com.eignex.klause.localsearch.Completion
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.parseBigInt
import kotlin.math.ceil

/**
 * Completes a local-search candidate over an open model's continuous columns through the open theory route: every
 * Boolean and integer column pinned to the candidate's value, and the rest decided exactly by the theory the model's
 * plan selects.
 *
 * The leaf LP a finite model's completion solves cannot certify a strict row, which open models carry routinely; the
 * theory decides strictness exactly, so on an open model it is the completion that can say yes. Its work is charged to
 * the search at the rate a portfolio weighs one `openWork` unit against local-search moves.
 */
internal class TheoryCompletion(private val model: Problem, private val params: TheoryParams) : CandidateCompletion {
    override fun complete(candidate: Sample, cancellation: Cancellation): Completion {
        val pinned = pinnedModel(candidate)
        val plan = pinned.componentPlan()
        if (plan.theoryPipeline == ProblemPipeline.UNSUPPORTED_OPEN ||
            plan.theoryPipeline == ProblemPipeline.FINITE_CP
        ) {
            return Completion.Undecided()
        }
        val request = OpenTheoryRequest(pinned, componentPlan = plan)
        val decided = OpenTheoryPipeline.execute(
            request,
            params.copy(cancellation = params.cancellation or cancellation),
        )
        val result = (decided as OpenTheoryExecution.Satisfy).result
        val work = ceil(result.stats.openTheory.openWork * LS_INSTRUCTIONS_PER_WORK).toLong().coerceAtLeast(1L)
        return when (result) {
            is OpenTheoryResult.Sat -> {
                val exact = List(model.numRealVars) { parseRational(result.assignment.realValue(it)) }
                val reals = DoubleArray(exact.size) { exact[it].toDouble() }
                Completion.Witness(candidate.copy(reals = reals, exactReals = exact), work)
            }

            is OpenTheoryResult.Unsat -> Completion.Refuted(work = work)

            is OpenTheoryResult.Unknown -> Completion.Undecided(work)
        }
    }

    // The model with every Boolean and integer column fixed to the candidate's value.
    private fun pinnedModel(candidate: Sample): Problem {
        val pins = ArrayList<Factor>(model.numBoolVars + model.numIntVars)
        for (b in 0 until model.numBoolVars) pins += Clause(intArrayOf(Lit.make(b, candidate.bools[b])))
        for (v in 0 until model.numIntVars) {
            pins += Linear(longArrayOf(1L), intArrayOf(v), LinearOp.EQ, candidate.ints[v])
        }
        val mask = model.impliedFactorMask?.let { it + BooleanArray(pins.size) }
        return model.withFactors(model.factors + pins, mask)
    }

    companion object {
        // An exact witness value as the theory prints it: an integer or `numerator/denominator`.
        fun parseRational(text: String): BigFraction {
            val parts = text.split('/')
            val numerator = parseBigInt(parts[0])
            val denominator = if (parts.size > 1) parseBigInt(parts[1]) else BIG_ONE
            return BigFraction.of(numerator, denominator)
        }
    }
}
