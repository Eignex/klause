package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import kotlin.math.ceil
import kotlin.random.Random

internal data class ExactNeighborhoodCondition(
    val bools: Map<Int, Boolean>,
    val lower: Map<Int, BigFraction>,
    val upper: Map<Int, BigFraction>,
    val cutoff: BigFraction? = null,
)

internal sealed interface ExactNeighborhoodResult {
    data class Witness(val sample: Sample) : ExactNeighborhoodResult
    data object Refuted : ExactNeighborhoodResult
    data object Undecided : ExactNeighborhoodResult
}

internal fun interface ExactNeighborhoodCompletion {
    fun check(condition: ExactNeighborhoodCondition, cancellation: Cancellation): ExactNeighborhoodResult
}

internal class ExactNeighborhood(
    private val boolCount: Int,
    centers: List<BigFraction>,
    private val completion: ExactNeighborhoodCompletion,
    private val objective: LinearObjective? = null,
    private val coordinateValues: (Sample) -> List<BigFraction>,
    private val adaptive: Boolean = false,
    seed: Long = 0,
    private val nodeBudget: NodeBudget? = null,
    private val maximumMoves: Long = Long.MAX_VALUE,
    private val maximumInstructions: Long = Long.MAX_VALUE,
    private val maximumImprovementMoves: Long? = null,
    private val nativeWork: () -> Long = { 0 },
    private val completionStats: () -> SolveStats = { SolveStats.EMPTY },
    private val onClose: () -> Unit = {},
    private val prepareBooleanCenter: ((Cancellation) -> Boolean)? = null,
    private val rootBooleanValue: (Int) -> Boolean? = { null },
) : AutoCloseable {
    private val random = Random(seed)
    private var centers = centers.toList()
    private var bools = BooleanArray(boolCount)
    private var radius = BigFraction.ONE
    private var round = 0L
    private var incumbent: Sample? = null
    private var value: BigFraction? = null
    private val weights = DoubleArray(3) { 1.0 }
    private val sink = SolveStatsSink(if (adaptive) "exact-alns" else "exact-ls").also { it.start() }
    private var moves = 0L
    private var improvementMoves = 0L
    private var stalls = 0L
    private var charged = 0L
    private var closed = false
    private var booleanCenterPrepared = prepareBooleanCenter == null
    private var nextCenterBoolean = 0
    val stats: SolveStats get() = sink.snapshot().mergedWith(completionStats())
    val work: Long get() = charged

    private fun operator(): Int {
        if (!adaptive) return if (round % (centers.size + boolCount + 1) == 0L) 2 else 0
        if (round < weights.size) return round.toInt()
        var draw = random.nextDouble() * weights.sum()
        for (i in weights.indices) {
            draw -= weights[i]
            if (draw <= 0) return i
        }
        return weights.lastIndex
    }

    fun advance(
        cancellation: Cancellation,
        allowance: Long,
        onIncumbent: (Sample, BigFraction?) -> Unit = { _, _ -> },
    ): Boolean {
        check(!closed) { "neighborhood owner is closed" }
        val end = charged + minOf(allowance.coerceAtLeast(0), Long.MAX_VALUE - charged)
        while (!cancellation() && charged < end && moves < maximumMoves && charged < maximumInstructions &&
            !improvementAllowanceSpent() && nodeBudget?.exhausted() != true) {
            if (!booleanCenterPrepared) {
                val before = nativeWork()
                val prepared = requireNotNull(prepareBooleanCenter)(cancellation)
                charge(ceil((nativeWork() - before).coerceAtLeast(0) * 0.8).toLong())
                if (cancellation()) return searchAllowanceSpent()
                if (!prepared) {
                    moves++
                    charge(1)
                    sink.ls.recordCompletion(refuted = false, undecided = true)
                    sink.ls.recordWork(moves, 0, stalls)
                    continue
                }
                while (nextCenterBoolean < boolCount) {
                    if (cancellation() || charged >= end || charged >= maximumInstructions ||
                        nodeBudget?.exhausted() == true) return searchAllowanceSpent()
                    rootBooleanValue(nextCenterBoolean)?.let { bools[nextCenterBoolean] = it }
                    nextCenterBoolean++
                    charge(1)
                }
                booleanCenterPrepared = true
                continue
            }
            val total = centers.size + boolCount
            val operator = operator()
            val count = when (operator) { 0 -> 1; 1 -> maxOf(1, total / 2); else -> total }
            val selected = if (moves == 0L) emptySet() else (0 until total).shuffled(random).take(count).toSet()
            val lower = centers.indices.associateWith { if (it in selected) centers[it] - radius else centers[it] }
            val upper = centers.indices.associateWith { if (it in selected) centers[it] + radius else centers[it] }
            val pins = (0 until boolCount).filter { centers.size + it !in selected }.associateWith { bools[it] }
            if (incumbent != null) improvementMoves++
            val before = nativeWork()
            val result = completion.check(ExactNeighborhoodCondition(pins, lower, upper, value), cancellation)
            moves++
            val cost = 1 + ceil((nativeWork() - before).coerceAtLeast(0) * 0.8).toLong()
            charge(cost)
            sink.ls.recordCompletion(
                result is ExactNeighborhoodResult.Refuted, result is ExactNeighborhoodResult.Undecided,
            )
            val improved = result is ExactNeighborhoodResult.Witness && accept(result.sample, onIncumbent)
            if (adaptive) weights[operator] = weights[operator] * 0.8 + if (improved) 1.0 else 0.2
            if (improved) {
                radius = BigFraction.ONE
                round = 0
                if (objective == null) {
                    sink.ls.recordWork(moves, 0, stalls)
                    return true
                }
            } else {
                round++
                if (operator == 2 || total == 0) {
                    radius += radius
                    stalls++
                }
            }
            sink.ls.recordWork(moves, 0, stalls)
            if (total == 0) return true
        }
        return searchAllowanceSpent()
    }

    private fun searchAllowanceSpent(): Boolean = moves >= maximumMoves || charged >= maximumInstructions ||
        improvementAllowanceSpent() || nodeBudget?.exhausted() == true

    private fun charge(cost: Long) {
        charged += minOf(cost, Long.MAX_VALUE - charged)
        nodeBudget?.spendMoves(cost)
    }

    private fun improvementAllowanceSpent(): Boolean = incumbent != null &&
        maximumImprovementMoves?.let { improvementMoves >= it } == true

    private fun accept(sample: Sample, onIncumbent: (Sample, BigFraction?) -> Unit): Boolean {
        val candidateValue = objective?.evaluateExact(sample)
        if (value != null && candidateValue != null && candidateValue >= requireNotNull(value)) return false
        incumbent = sample
        value = candidateValue
        centers = coordinateValues(sample).toList()
        bools = sample.bools
        sink.ls.recordIncumbent(candidateValue?.toDouble() ?: Double.NaN, 0.0, sink.elapsedMs())
        onIncumbent(sample, candidateValue)
        return true
    }

    fun solveResult(reason: TerminationReason): SolveResult = incumbent?.let {
        SolveResult.Sat(it, resultStats(reason))
    }
        ?: SolveResult.Unknown(reason, resultStats(reason))

    fun minimizeResult(reason: TerminationReason): MinimizeResult = incumbent?.let {
        MinimizeResult.BestFound(it, requireNotNull(value).toDouble(), reason, resultStats(reason))
    } ?: MinimizeResult.Unknown(reason, resultStats(reason))

    override fun close() {
        if (closed) return
        closed = true
        onClose()
    }
}

private fun ExactNeighborhood.resultStats(reason: TerminationReason): SolveStats = stats.let {
    it.copy(run = it.run.copy(timedOut = reason == TerminationReason.Timeout))
}
