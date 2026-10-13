package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.util.Cancellation
import kotlin.math.ceil
import kotlin.random.Random

internal class QuadraticNeighborhoodAllowance(
    val maximumMoves: Long,
    val maximumInstructions: Long,
    private val nodeBudget: NodeBudget?,
) {
    init { require(maximumMoves >= 0 && maximumInstructions >= 0) }
    var moves: Long = 0
        private set
    var instructions: Long = 0
        private set
    val exhausted: Boolean get() = moves >= maximumMoves || instructions >= maximumInstructions ||
        nodeBudget?.exhausted() == true

    fun charge(nativeWork: Long) {
        val cost = 1L + minOf(quadraticNativeInstructions(nativeWork), Long.MAX_VALUE - 1)
        moves += minOf(1, Long.MAX_VALUE - moves)
        instructions += minOf(cost, Long.MAX_VALUE - instructions)
        nodeBudget?.spendMoves(cost)
    }
}

internal class QuadraticNeighborhoodCursor(
    private val coordinates: Int,
    private val adaptive: Boolean,
    seed: Long,
    private val allowance: QuadraticNeighborhoodAllowance,
    private val lifetime: Cancellation,
    private val nativeWork: () -> Long,
    private val propose: (Set<Int>, BigFraction, Random) -> ExactNeighborhoodCondition,
    private val completion: ExactNeighborhoodCompletion,
    private val accept: (Sample) -> Boolean,
    private val releaseFirst: Boolean = false,
    private val rotateSingle: Boolean = false,
) : AutoCloseable {
    private val random = Random(seed)
    private var radius = BigFraction.ONE
    private val weights = DoubleArray(3) { 1.0 }
    private val initialMoves = allowance.moves
    private val initialInstructions = allowance.instructions
    private var stalls = 0L
    private val sink = SolveStatsSink(if (adaptive) "quadratic-alns" else "quadratic-ls").also { it.start() }
    private var closed = false
    private var finished = false
    val isDone: Boolean get() = finished || allowance.exhausted
    val work: Long get() = allowance.instructions - initialInstructions
    val stats: SolveStats get() = sink.snapshot()

    init { require(coordinates >= 0) }

    fun advance(
        segment: Cancellation, instructions: Long, invocation: Cancellation = Cancellation.Never,
    ): Boolean {
        require(instructions >= 0)
        check(!closed) { "quadratic neighborhood cursor is closed" }
        if (isDone) return true
        val start = allowance.instructions
        val stop = lifetime or invocation
        while (!segment() && !stop() && !isDone && allowance.instructions - start < instructions) {
            val operator = if (!adaptive) 0 else random.nextInt(weights.size).let { draw ->
                if (random.nextDouble() < weights[draw] / requireNotNull(weights.maxOrNull())) draw else 2
            }
            val amount = when (operator) { 0 -> 1; 1 -> maxOf(1, coordinates / 2); else -> coordinates }
            val released = when {
                coordinates == 0 -> emptySet()
                rotateSingle && !adaptive -> setOf((allowance.moves % coordinates).toInt())
                allowance.moves == initialMoves && !releaseFirst -> emptySet()
                else -> (0 until coordinates).shuffled(random).take(amount).toSet()
            }
            val before = nativeWork()
            val condition = propose(released, radius, random)
            // A repair and its accepted witness form one atomic segment operation.
            val result = try {
                completion.check(condition, stop)
            } finally {
                allowance.charge((nativeWork() - before).coerceAtLeast(0))
            }
            val improved = result is ExactNeighborhoodResult.Witness && accept(result.sample)
            sink.ls.recordCompletion(
                result is ExactNeighborhoodResult.Refuted, result is ExactNeighborhoodResult.Undecided,
            )
            if (improved) radius = BigFraction.ONE else { radius += radius; stalls++ }
            if (adaptive) weights[operator] = weights[operator] * 0.8 + if (improved) 1.0 else 0.2
            sink.ls.recordWork(allowance.moves - initialMoves, 0, stalls)
            if (coordinates == 0) finished = true
        }
        return isDone
    }

    override fun close() {
        if (closed) return
        closed = true
        sink.stop()
    }
}

internal fun quadraticNativeInstructions(work: Long): Long = ceil(work.coerceAtLeast(0) * 0.8).toLong()
