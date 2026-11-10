package com.eignex.klause.solver.pipeline

import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceDerivation
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.util.Cancellation
import com.eignex.util.arrays.emptyIntArray
import com.eignex.util.collections.IntArrayDeque
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.IntHashSet
import com.eignex.util.numbers.magnitudeBitLength
import kotlin.math.abs
import kotlin.math.round

internal class JointRepairNeighborhood private constructor(
    private val integers: Int,
    private val rows: List<IntArray>,
    private val occurrences: Array<IntArray>,
    private val inputs: IntArray?,
) : ExactNeighborhoodProposal {
    private val eligible = inputs?.let { indices ->
        BooleanArray(integers).also { mask -> indices.forEach { mask[it] = true } }
    }
    private var seeds = inputs?.toList() ?: (0 until integers).toList()
    private var rankedSeeds: List<Int>? = null
    private val refutedPins = ArrayDeque<Pair<Int, BigFraction>>()
    private var refutationBools: Map<Int, Boolean>? = null
    private var refutationBudget: SourceLpBudget? = null
    private var refutationToken: Cancellation = Cancellation.Never
    var refutationSeeds: Long = 0
        private set
    var additionalRefutationSeeds: Long = 0
        private set
    var selectedIntegers: Long = 0
        private set
    var visitedEntries: Long = 0
        private set

    fun guide(point: Map<SourceCoordinate, Double>) {
        rankedSeeds = null
        clearRefutation()
        seeds = seeds.sortedByDescending { id ->
            val value = point[SourceCoordinate(SourceCoordinate.Kind.Integer, id)]
            if (value == null || !value.isFinite()) 0.0 else abs(value - round(value))
        }
    }

    fun clearRefutation() {
        refutedPins.clear()
        refutationBools = null
        refutationBudget = null
        refutationToken = Cancellation.Never
    }

    fun guideRefutation(assumptions: Set<SourceDerivation.Assumption>, condition: ExactNeighborhoodCondition,
        budget: SourceLpBudget, token: Cancellation): Boolean {
        val funding = budget.funding
        if (funding == null || condition.cutoff != null || condition.bools.size > MAX_REFUTATION_BOOLEANS) {
            clearRefutation()
            return false
        }
        for (assumption in assumptions) {
            if (token() || funding.availableWork <= 1L || funding.remainingAllocation <= 64L) break
            budget.observe(preparation = 1L)
            funding.charge(0L, 64L)
            val coordinate = assumption.coordinate
            if (coordinate.kind != SourceCoordinate.Kind.Integer || coordinate.id !in 0 until integers ||
                eligible?.get(coordinate.id) == false) continue
            val value = condition.lower[coordinate.id] ?: continue
            if (condition.upper[coordinate.id] != value) continue
            val bits = value.num.magnitudeBitLength().toLong() + value.den.magnitudeBitLength()
            if (bits > MAX_GUIDANCE_SCALAR_BITS || !payRefutation(budget, token,
                    1L + condition.bools.size + MAX_REFUTED_PINS * (1L + bits),
                    128L + 64L * condition.bools.size)) break
            if (refutationBudget !== budget || refutationBools != condition.bools ||
                refutedPins.any { it.first == coordinate.id && it.second != value }) clearRefutation()
            refutedPins.removeAll { it.first == coordinate.id }
            if (refutedPins.size == MAX_REFUTED_PINS) refutedPins.removeFirst()
            refutedPins.addLast(coordinate.id to value)
            refutationBools = refutationBools ?: condition.bools.toMap()
            refutationBudget = budget
            refutationToken = token
            return true
        }
        clearRefutation()
        return false
    }

    private fun payRefutation(budget: SourceLpBudget, token: Cancellation, work: Long, bytes: Long): Boolean {
        val funding = budget.funding ?: return false
        if (token() || funding.remainingWork <= work || funding.remainingAllocation <= bytes) return false
        // Advisory metadata spends the containing ledger without renewing a numerical query grant.
        budget.observe(preparation = work)
        funding.charge(0L, bytes)
        return !token() && !funding.exhausted
    }

    private fun refutationRoots(centers: List<BigFraction>, bools: BooleanArray, cutoff: BigFraction?): List<Int> {
        val context = refutationBools ?: return emptyList()
        val budget = refutationBudget ?: return emptyList()
        val token = refutationToken
        if (cutoff != null || !payRefutation(budget, token, 1L + context.size + refutedPins.size, 64L) ||
            context.any { (id, value) -> id !in bools.indices || bools[id] != value }) {
            clearRefutation()
            return emptyList()
        }
        var work = 1L
        for ((id, value) in refutedPins) {
            if (token() || id !in centers.indices) {
                clearRefutation()
                return emptyList()
            }
            val center = centers[id]
            val bits = center.num.magnitudeBitLength().toLong() + center.den.magnitudeBitLength()
            if (bits > MAX_GUIDANCE_SCALAR_BITS) {
                clearRefutation()
                return emptyList()
            }
            work += 1L + bits + value.num.magnitudeBitLength() + value.den.magnitudeBitLength()
        }
        if (!payRefutation(budget, token, work, 256L) || refutedPins.any { (id, value) -> centers[id] != value }) {
            clearRefutation()
            return emptyList()
        }
        return refutedPins.reversed().map { it.first }
    }

    fun guide(hint: LinearRepairGuidance.Hint?, centers: List<BigFraction>,
        budget: SourceLpBudget, token: Cancellation): Boolean {
        rankedSeeds = null
        if (hint == null || token() || seeds.isEmpty() || centers.size < integers ||
            centers.size > LinearRepairGuidance.MAX_COLUMNS) return false
        val funding = budget.funding ?: return false
        val work = 1L + 16L * seeds.size
        val bytes = 256L + 128L * seeds.size
        if (funding.availableWork <= work || funding.remainingAllocation <= bytes) return false
        budget.observe(preparation = work)
        funding.charge(0L, bytes)
        val distances = DoubleArray(seeds.size)
        for (index in seeds.indices) {
            if (token()) return false
            val center = centers[seeds[index]]
            val bits = center.num.magnitudeBitLength().toLong() + center.den.magnitudeBitLength()
            if (bits > MAX_GUIDANCE_SCALAR_BITS) return false
            val conversion = 1L + bits * bits / 64L
            if (funding.availableWork <= conversion || funding.remainingAllocation <= 8L * bits) return false
            budget.observe(preparation = conversion)
            funding.charge(0L, 8L * bits)
            val value = center.toDouble()
            if (!value.isFinite()) return false
            val distance = hint.integer(seeds[index])?.let { abs(it - value) } ?: 0.0
            if (!distance.isFinite()) return false
            distances[index] = distance
        }
        if (token() || distances.none { it > 0.0 }) return false
        val ranked = seeds.indices.sortedByDescending { distances[it] }.map { seeds[it] }
        if (token()) return false
        rankedSeeds = ranked
        return true
    }

    override fun propose(
        centers: List<BigFraction>, bools: BooleanArray, count: Int, round: Long,
        radius: BigFraction, cutoff: BigFraction?,
    ): ExactNeighborhoodCondition {
        val order = rankedSeeds ?: seeds
        val freed = if (round == 0L || order.isEmpty()) emptySet() else {
            val width = minOf(inputs?.size ?: integers, maxOf(2, count), MAX_FREE_INTEGERS)
            val preferred = refutationRoots(centers, bools, cutoff).take(width)
            val roots = preferred.ifEmpty { listOf(order[((round - 1) % order.size).toInt()]) }
            if (preferred.isNotEmpty()) {
                refutationSeeds++
                additionalRefutationSeeds += preferred.size - 1
            }
            connected(roots, width)
        }
        selectedIntegers += freed.size
        val pins = (inputs?.asIterable() ?: (0 until integers)).filter { it !in freed }.associateWith { centers[it] }
        // The full continuous residual stays free: an omitted boundary row could invalidate a local repair.
        return ExactNeighborhoodCondition(bools.indices.associateWith { bools[it] }, pins, pins, cutoff)
    }

    private fun connected(roots: List<Int>, count: Int): Set<Int> {
        val selected = roots.toCollection(linkedSetOf())
        val seenColumns = IntHashSet().also { seen -> roots.forEach { seen.add(it) } }
        val seenRows = IntHashSet()
        val queue = IntArrayDeque().also { queue -> roots.forEach { queue.addLast(it) } }
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
        ): JointRepairNeighborhood? {
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
            val occurrences = arrayOfNulls<IntArrayList>(integers + reals)
            var entries = integers + reals
            for (premise in source.premises.values) {
                cancellation.charge(1)
                if (cancellation() || ++entries > MAX_INDEX_ENTRIES) return null
                val columns = IntArrayList()
                for ((coordinate, coefficient) in premise.inequality.coefficients) {
                    cancellation.charge(1)
                    if (cancellation() || ++entries > MAX_INDEX_ENTRIES) return null
                    if (coefficient.isZero || coordinate.kind == SourceCoordinate.Kind.Boolean) continue
                    val column = if (coordinate.kind == SourceCoordinate.Kind.Integer) coordinate.id
                        else integers + coordinate.id
                    columns.add(column)
                }
                for (coordinate in premise.integerPins.keys) {
                    cancellation.charge(1)
                    if (cancellation() || ++entries > MAX_INDEX_ENTRIES) return null
                    if (coordinate.id !in columns) columns.add(coordinate.id)
                }
                if (columns.size < 2) continue
                columns.forEach { column ->
                    val adjacent = occurrences[column] ?: IntArrayList(1).also { occurrences[column] = it }
                    adjacent.add(rows.size)
                }
                rows += columns.toIntArray()
            }
            return if (cancellation()) null else
                JointRepairNeighborhood(integers, rows,
                    Array(occurrences.size) { occurrences[it]?.toIntArray() ?: emptyIntArray() }, inputs)
        }
    }
}

private const val MAX_FREE_INTEGERS = 32
private const val MAX_SELECTION_VISITS = 8192
private const val MAX_INDEX_ENTRIES = 1_048_576
private const val MAX_GUIDANCE_SCALAR_BITS = 4096
private const val MAX_REFUTED_PINS = 4
private const val MAX_REFUTATION_BOOLEANS = 4096
