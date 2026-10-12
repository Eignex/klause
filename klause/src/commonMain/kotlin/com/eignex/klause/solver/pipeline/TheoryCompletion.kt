package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.LS_INSTRUCTIONS_PER_WORK
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.CandidateCompletion
import com.eignex.klause.localsearch.Completion
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.parseBigInt
import kotlin.math.ceil

internal class TheoryCompletion(
    private val model: Problem,
    params: TheoryParams,
    preparedSource: PreparedSourcePlan? = null,
) : CandidateCompletion, AutoCloseable {
    private val adapter = OpenExactNeighborhood(model, params, preparedSource = preparedSource)
    val stats: SolveStats get() = adapter.stats

    override fun complete(candidate: Sample, cancellation: Cancellation): Completion {
        require(candidate.numBoolVars == model.numBoolVars && candidate.numIntVars == model.numIntVars) {
            "completion requires the source discrete coordinates"
        }
        val pins = (0 until model.numBoolVars).associateWith(candidate::boolValue)
        val integers = (0 until model.numIntVars).associateWith {
            BigFraction.of(candidate.exactIntValue(it), BIG_ONE)
        }
        val before = adapter.work
        val result = adapter.check(ExactNeighborhoodCondition(pins, integers, integers), cancellation)
        val work = ceil((adapter.work - before).coerceAtLeast(0) * LS_INSTRUCTIONS_PER_WORK).toLong().coerceAtLeast(1L)
        return when (result) {
            is ExactNeighborhoodResult.Witness -> Completion.Witness(result.sample, work)
            ExactNeighborhoodResult.Refuted -> Completion.Refuted(work = work)
            ExactNeighborhoodResult.Undecided -> Completion.Undecided(work)
        }
    }

    override fun close() = adapter.close()

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
