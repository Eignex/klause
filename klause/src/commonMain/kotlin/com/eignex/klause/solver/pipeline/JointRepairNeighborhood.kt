package com.eignex.klause.solver.pipeline

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.util.Cancellation
import kotlin.math.abs
import kotlin.math.round

internal class JointRepairNeighborhood private constructor(
    private val integers: Int,
    private val rows: List<IntArray>,
    private val occurrences: Array<IntArray>,
) : ExactNeighborhoodProposal {
    private var seeds = (0 until integers).toList()
    var selectedIntegers: Long = 0
        private set
    var visitedEntries: Long = 0
        private set

    fun guide(point: Map<SourceCoordinate, Double>) {
        seeds = seeds.sortedByDescending { id ->
            val value = point[SourceCoordinate(SourceCoordinate.Kind.Integer, id)]
            if (value == null || !value.isFinite()) 0.0 else abs(value - round(value))
        }
    }

    override fun propose(
        centers: List<BigFraction>, bools: BooleanArray, count: Int, round: Long,
        radius: BigFraction, cutoff: BigFraction?,
    ): ExactNeighborhoodCondition {
        val freed = if (round == 0L || seeds.isEmpty()) emptySet() else {
            val seed = seeds[((round - 1) % seeds.size).toInt()]
            connected(seed, minOf(integers, maxOf(2, count), MAX_FREE_INTEGERS))
        }
        selectedIntegers += freed.size
        val pins = (0 until integers).filter { it !in freed }.associateWith { centers[it] }
        // The full continuous residual stays free: an omitted boundary row could invalidate a local repair.
        return ExactNeighborhoodCondition(bools.indices.associateWith { bools[it] }, pins, pins, cutoff)
    }

    private fun connected(seed: Int, count: Int): Set<Int> {
        val selected = linkedSetOf(seed)
        val seenColumns = hashSetOf(seed)
        val seenRows = HashSet<Int>()
        val queue = ArrayDeque<Int>().also { it.addLast(seed) }
        var visits = 0
        while (queue.isNotEmpty() && selected.size < count && visits < MAX_SELECTION_VISITS) {
            val column = queue.removeFirst()
            for (row in occurrences[column]) {
                if (++visits > MAX_SELECTION_VISITS) break
                if (!seenRows.add(row)) continue
                for (peer in rows[row]) {
                    if (++visits > MAX_SELECTION_VISITS) break
                    if (!seenColumns.add(peer)) continue
                    queue.addLast(peer)
                    if (peer < integers) selected += peer
                    if (selected.size == count) break
                }
                if (selected.size == count) break
            }
        }
        visitedEntries += visits
        return selected
    }

    companion object {
        fun prepare(
            source: SourceProofContext, integers: Int, reals: Int, cancellation: Cancellation,
        ): JointRepairNeighborhood? {
            if (cancellation() || integers.toLong() + reals > MAX_INDEX_ENTRIES) return null
            cancellation.charge(integers.toLong() + reals)
            if (cancellation()) return null
            val rows = ArrayList<IntArray>()
            val occurrences = Array(integers + reals) { ArrayList<Int>() }
            var entries = integers + reals
            for (premise in source.premises.values) {
                cancellation.charge(1)
                if (cancellation() || ++entries > MAX_INDEX_ENTRIES) return null
                val columns = ArrayList<Int>()
                for ((coordinate, coefficient) in premise.inequality.coefficients) {
                    cancellation.charge(1)
                    if (cancellation() || ++entries > MAX_INDEX_ENTRIES) return null
                    if (coefficient.isZero || coordinate.kind == SourceCoordinate.Kind.Boolean) continue
                    val column = if (coordinate.kind == SourceCoordinate.Kind.Integer) coordinate.id
                        else integers + coordinate.id
                    columns += column
                }
                for (coordinate in premise.integerPins.keys) {
                    cancellation.charge(1)
                    if (cancellation() || ++entries > MAX_INDEX_ENTRIES) return null
                    if (coordinate.id !in columns) columns += coordinate.id
                }
                if (columns.size < 2) continue
                for (column in columns) occurrences[column] += rows.size
                rows += columns.toIntArray()
            }
            return if (cancellation()) null else
                JointRepairNeighborhood(integers, rows, Array(occurrences.size) { occurrences[it].toIntArray() })
        }
    }
}

private const val MAX_FREE_INTEGERS = 32
private const val MAX_SELECTION_VISITS = 8192
private const val MAX_INDEX_ENTRIES = 1_048_576
