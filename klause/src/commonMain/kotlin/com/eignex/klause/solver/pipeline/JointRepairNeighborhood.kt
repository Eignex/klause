package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.IntBounds
import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.magnitudeBitLength
import kotlin.math.abs
import kotlin.math.round

internal class JointRepairNeighborhood private constructor(
    private val integers: Int,
    private val rows: List<IntArray>,
    private val occurrences: Array<IntArray>,
    private val inputs: IntArray?,
    private val integerBounds: IntBounds?,
) : ExactNeighborhoodProposal {
    private val eligible = inputs?.let { indices ->
        BooleanArray(integers).also { mask -> indices.forEach { mask[it] = true } }
    }
    private var seeds = inputs?.toList() ?: (0 until integers).toList()
    var selectedIntegers: Long = 0
        private set
    var visitedEntries: Long = 0
        private set
    var rangeScaledGuidance: Boolean = false
        private set

    fun guide(point: Map<SourceCoordinate, Double>) {
        seeds = seeds.sortedByDescending { id ->
            val value = point[SourceCoordinate(SourceCoordinate.Kind.Integer, id)]
            if (value == null || !value.isFinite()) 0.0 else abs(value - round(value))
        }
    }

    fun guide(hint: LinearRepairGuidance.Hint, centers: List<BigFraction>,
        budget: SourceLpBudget, token: Cancellation): Boolean {
        if (token() || centers.size < integers || centers.size > LinearRepairGuidance.MAX_COLUMNS) return false
        val funding = budget.funding ?: return false
        val work = 1L + 16L * seeds.size + 2L * (integerBounds?.size ?: 0)
        val bytes = 256L + 128L * seeds.size
        if (funding.availableWork <= work || funding.remainingAllocation <= bytes) return false
        budget.observe(preparation = work)
        funding.charge(0L, bytes)
        if (integerBounds?.hasOpenSides == true) return false
        val distances = HashMap<Int, Double>()
        for (id in seeds) {
            if (token()) return false
            val center = centers[id]
            val bits = center.num.magnitudeBitLength().toLong() + center.den.magnitudeBitLength()
            if (bits > MAX_GUIDANCE_SCALAR_BITS) return false
            val conversion = 1L + bits * bits / 64L
            if (funding.availableWork <= conversion || funding.remainingAllocation <= 8L * bits) return false
            budget.observe(preparation = conversion)
            funding.charge(0L, 8L * bits)
            val value = center.toDouble()
            if (!value.isFinite()) return false
            distances[id] = disagreement(id, hint.integer(id), value)
        }
        val ranked = seeds.sortedByDescending { distances.getValue(it) }
        if (token()) return false
        seeds = ranked
        rangeScaledGuidance = integerBounds != null
        return true
    }

    private fun disagreement(id: Int, point: Double?, center: Double): Double {
        val distance = point?.let { abs(it - center) } ?: 0.0
        val bounds = integerBounds ?: return distance
        if (!bounds.hasLower(id) || !bounds.hasUpper(id)) return distance
        val lower = bounds.lower(id)
        val upper = bounds.upper(id)
        val width = if (lower >= 0 || upper < 0) (upper - lower).toDouble()
            else upper.toDouble() - lower.toDouble()
        return if (width > 0.0) distance / width else 0.0
    }

    override fun propose(
        centers: List<BigFraction>, bools: BooleanArray, count: Int, round: Long,
        radius: BigFraction, cutoff: BigFraction?,
    ): ExactNeighborhoodCondition {
        val freed = if (round == 0L || seeds.isEmpty()) emptySet() else {
            val seed = seeds[((round - 1) % seeds.size).toInt()]
            connected(seed, minOf(inputs?.size ?: integers, maxOf(2, count), MAX_FREE_INTEGERS))
        }
        selectedIntegers += freed.size
        val pins = (inputs?.asIterable() ?: (0 until integers)).filter { it !in freed }.associateWith { centers[it] }
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
                    if (peer < integers && (eligible == null || eligible[peer])) selected += peer
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
            sourceIntegers: IntArray? = null,
            integerBounds: IntBounds? = null,
        ): JointRepairNeighborhood? {
            if (integerBounds != null && integerBounds.size != integers) return null
            if (cancellation() || integers.toLong() + reals > MAX_INDEX_ENTRIES) return null
            cancellation.charge(integers.toLong() + reals)
            if (cancellation()) return null
            val inputs = sourceIntegers?.copyOf()
            if (inputs != null) {
                cancellation.charge(inputs.size.toLong())
                if (cancellation()) return null
                require(inputs.size <= integers && inputs.all { it in 0 until integers } &&
                    inputs.distinct().size == inputs.size)
            }
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
                JointRepairNeighborhood(integers, rows,
                    Array(occurrences.size) { occurrences[it].toIntArray() }, inputs, integerBounds)
        }
    }
}

private const val MAX_FREE_INTEGERS = 32
private const val MAX_SELECTION_VISITS = 8192
private const val MAX_INDEX_ENTRIES = 1_048_576
private const val MAX_GUIDANCE_SCALAR_BITS = 4096
