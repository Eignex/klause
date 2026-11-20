package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.IntBounds
import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceDerivation
import com.eignex.klause.solver.evidence.SourcePremise
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.arrays.emptyIntArray
import com.eignex.util.collections.IntArrayDeque
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.IntHashSet
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.magnitudeBitLength
import kotlin.math.abs
import kotlin.math.round

internal class JointRepairNeighborhood private constructor(
    private val integers: Int,
    private val rows: List<IntArray>,
    private val occurrences: Array<IntArray>,
    private val inputs: IntArray?,
    private val objectiveDefinition: ObjectiveDefinition?,
) : ExactNeighborhoodProposal {
    sealed interface AdvisorySelection {
        data class Ready(val condition: ExactNeighborhoodCondition, val closed: Boolean,
            val kind: Kind = Kind.Pair) : AdvisorySelection
        data class Declined(val limit: Limit, val row: Int? = null, val column: Int? = null,
            val peer: Int? = null, val freed: Set<Int> = emptySet()) : AdvisorySelection
        data object Unavailable : AdvisorySelection
        enum class Limit { Width, Visits }
        enum class Kind { Pair, IntegerSupport, ContinuousSupport }
    }

    private class ObjectiveDefinition(val firstRow: Int, val first: SourcePremise,
        val secondRow: Int, val second: SourcePremise) {
        fun matches(budget: SourceLpBudget, token: ExecutionControl): Boolean {
            val a = first.inequality
            val b = second.inequality
            if (first.guardCount != 0 || second.guardCount != 0 || first.integerPinCount != 0 ||
                second.integerPinCount != 0 || a.strict || b.strict || a.supportSize != b.supportSize ||
                a.supportSize > MAX_SELECTION_VISITS / 2) return false
            val funding = budget.funding ?: return false
            val work = 16L + a.supportSize
            if (token() || funding.availableWork <= work || funding.remainingAllocation <= 128L) return false
            budget.observe(preparation = work)
            funding.charge(0L, 128L)
            fun opposite(left: BigFraction, right: BigFraction): Boolean {
                val leftBits = left.num.magnitudeBitLength().toLong() + left.den.magnitudeBitLength()
                val rightBits = right.num.magnitudeBitLength().toLong() + right.den.magnitudeBitLength()
                if (leftBits > MAX_GUIDANCE_SCALAR_BITS || rightBits > MAX_GUIDANCE_SCALAR_BITS) return false
                val scalarWork = 16L + leftBits + rightBits
                val bytes = 128L + 8L * (leftBits + rightBits)
                if (token() || funding.availableWork <= scalarWork || funding.remainingAllocation <= bytes)
                    return false
                budget.observe(preparation = scalarWork)
                funding.charge(0L, bytes)
                return left == right.negated() && !token() && !funding.exhausted
            }
            return opposite(a.rhs, b.rhs) && a.forEachCoefficient { coordinate, value ->
                b.coefficient(coordinate)?.let { opposite(value, it) } == true
            }
        }
    }

    private var definitionChecked = objectiveDefinition == null
    private var ignoredFirstRow = -1
    private var ignoredSecondRow = -1

    private fun ignoredPrivateRow(row: Int): Boolean = row == ignoredFirstRow || row == ignoredSecondRow

    private class ConnectedSelection(val integers: Set<Int>, val complete: Boolean)

    private val eligible = inputs?.let { indices ->
        BooleanArray(integers).also { mask -> indices.forEach { mask[it] = true } }
    }
    private var seeds = inputs?.toList() ?: (0 until integers).toList()
    var onRefutationRoots: ((List<Int>) -> Unit)? = null
    private var rankedSeeds: List<Int>? = null
    private val refutedPins = ArrayDeque<Pair<Int, BigFraction>>()
    private var refutationBools: Map<Int, Boolean>? = null
    private var refutationBudget: SourceLpBudget? = null
    private var refutationToken: ExecutionControl = ExecutionControl.Never
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

    fun eligibleInteger(id: Int): Boolean =
        id in 0 until integers && eligible?.get(id) != false

    val advisoryRoots: Int get() = seeds.size

    fun advisoryRoot(root: Int): Int = seeds[root]

    fun advisoryNeighborhood(centers: List<BigFraction>, root: Int, cutoff: BigFraction,
        budget: SourceLpBudget, token: ExecutionControl, expand: Boolean = false,
        previous: ExactNeighborhoodCondition? = null): AdvisorySelection {
        val funding = budget.funding ?: return AdvisorySelection.Unavailable
        val work = 16L * (integers + 1L)
        val bytes = 256L + 128L * integers +
            if (expand) 256L + rows.size + 256L * minOf(integers, MAX_FREE_INTEGERS) else 0L
        if (root !in seeds.indices || centers.size != integers || token() ||
            funding.availableWork <= work + MAX_SELECTION_VISITS || funding.remainingAllocation <= bytes)
            return AdvisorySelection.Unavailable
        budget.observe(preparation = work)
        funding.charge(0L, bytes)
        if (!definitionChecked) {
            definitionChecked = true
            val definition = requireNotNull(objectiveDefinition)
            if (definition.matches(budget, token)) {
                ignoredFirstRow = definition.firstRow
                ignoredSecondRow = definition.secondRow
            }
        }
        if (token() || funding.exhausted || funding.availableWork <= MAX_SELECTION_VISITS)
            return AdvisorySelection.Unavailable
        if (!expand) {
            val before = visitedEntries
            val selected = connected(listOf(seeds[root]), minOf(2, seeds.size), advisory = true)
            budget.observe(preparation = visitedEntries - before)
            val pins = (0 until integers).filter { it !in selected.integers }.associateWith { centers[it] }
            if (token() || funding.exhausted) return AdvisorySelection.Unavailable
            selectedIntegers += selected.integers.size
            return AdvisorySelection.Ready(ExactNeighborhoodCondition(emptyMap(), pins, pins, cutoff),
                selected.complete || selected.integers.size == seeds.size)
        }
        val freed = linkedSetOf(seeds[root])
        val queue = IntArrayDeque().also { it.addLast(seeds[root]) }
        val seenRows = BooleanArray(rows.size)
        var visits = 0
        fun visit(row: Int, column: Int, peer: Int? = null): AdvisorySelection? = when {
            token() -> AdvisorySelection.Unavailable
            visits == MAX_SELECTION_VISITS -> AdvisorySelection.Declined(
                AdvisorySelection.Limit.Visits, row, column, peer, freed)
            else -> {
                visits++
                null
            }
        }
        try {
            while (queue.isNotEmpty()) {
                val column = queue.removeFirst()
                for (row in occurrences[column]) {
                    visit(row, column)?.let { return it }
                    if (ignoredPrivateRow(row) || seenRows[row]) continue
                    seenRows[row] = true
                    for (peer in rows[row]) {
                        visit(row, column, peer)?.let { return it }
                        if (!eligibleInteger(peer) || peer in freed) continue
                        if (freed.size == MAX_FREE_INTEGERS)
                            return AdvisorySelection.Declined(AdvisorySelection.Limit.Width, row, column, peer, freed)
                        freed += peer
                        queue.addLast(peer)
                    }
                }
            }
            var kind = AdvisorySelection.Kind.IntegerSupport
            var closed = true
            if (previous != null && freed.size <= 2 && previous.lower.keys.none { it in freed }) {
                val remaining = MAX_SELECTION_VISITS - visits
                val columns = minOf(occurrences.size, remaining)
                val adjacentRows = minOf(rows.size, remaining)
                val target = minOf(seeds.size, IncumbentProximity.radius(integers))
                val setupWork = 16L + columns + adjacentRows + target
                val setupBytes = 256L + 128L * columns + 64L * adjacentRows + 256L * target
                if (token() || funding.availableWork <= setupWork + MAX_SELECTION_VISITS ||
                    funding.remainingAllocation <= setupBytes) return AdvisorySelection.Unavailable
                budget.observe(preparation = setupWork)
                funding.charge(0L, setupBytes)
                if (token() || funding.availableWork <= MAX_SELECTION_VISITS)
                    return AdvisorySelection.Unavailable
                kind = AdvisorySelection.Kind.ContinuousSupport
                freed.clear()
                freed += seeds[root]
                queue.addLast(seeds[root])
                val seenColumns = IntHashSet().also { it.add(seeds[root]) }
                val physicalRows = IntHashSet()
                while (queue.isNotEmpty() && freed.size < target) {
                    val column = queue.removeFirst()
                    for (row in occurrences[column]) {
                        visit(row, column)?.let { return it }
                        if (ignoredPrivateRow(row) || !physicalRows.add(row)) continue
                        for (peer in rows[row]) {
                            visit(row, column, peer)?.let { return it }
                            if (!seenColumns.add(peer)) continue
                            queue.addLast(peer)
                            if (eligibleInteger(peer)) freed += peer
                            if (freed.size == target) break
                        }
                        if (freed.size == target) break
                    }
                }
                closed = queue.isEmpty() || freed.size == seeds.size
            }
            val pins = linkedMapOf<Int, BigFraction>()
            for (id in 0 until integers) {
                if (token()) return AdvisorySelection.Unavailable
                if (id !in freed) pins[id] = centers[id]
            }
            if (token() || funding.exhausted) return AdvisorySelection.Unavailable
            selectedIntegers += freed.size
            return AdvisorySelection.Ready(ExactNeighborhoodCondition(emptyMap(), pins, pins, cutoff), closed, kind)
        } finally {
            visitedEntries += visits
            budget.observe(preparation = visits.toLong())
        }
    }

    fun clearRefutation() {
        refutedPins.clear()
        refutationBools = null
        refutationBudget = null
        refutationToken = ExecutionControl.Never
    }

    fun guideRefutation(assumptions: Set<SourceDerivation.Assumption>, condition: ExactNeighborhoodCondition,
        budget: SourceLpBudget, token: ExecutionControl): Boolean {
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

    private fun payRefutation(budget: SourceLpBudget, token: ExecutionControl, work: Long, bytes: Long): Boolean {
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
        budget: SourceLpBudget, token: ExecutionControl): Boolean {
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
    ): ExactNeighborhoodCondition = proposeWithinSelectionBudget(centers, bools, count, round, cutoff, 0L)

    fun proposeWithinSelectionBudget(
        centers: List<BigFraction>, bools: BooleanArray, count: Int, round: Long,
        cutoff: BigFraction?, selectionVisitsSpent: Long,
    ): ExactNeighborhoodCondition {
        val order = rankedSeeds ?: seeds
        onRefutationRoots?.invoke(emptyList())
        val freed = if (round == 0L || order.isEmpty()) emptySet() else {
            val width = minOf(inputs?.size ?: integers, maxOf(2, count), MAX_FREE_INTEGERS)
            val preferred = refutationRoots(centers, bools, cutoff).take(width)
            onRefutationRoots?.invoke(preferred)
            val roots = preferred.ifEmpty { listOf(order[((round - 1) % order.size).toInt()]) }
            if (preferred.isNotEmpty()) {
                refutationSeeds++
                additionalRefutationSeeds += preferred.size - 1
            }
            val remaining = MAX_SELECTION_VISITS - selectionVisitsSpent.coerceIn(0L,
                MAX_SELECTION_VISITS.toLong()).toInt()
            connected(roots, width, remaining).integers
        }
        selectedIntegers += freed.size
        val pins = (inputs?.asIterable() ?: (0 until integers)).filter { it !in freed }.associateWith { centers[it] }
        // The full continuous residual stays free: an omitted boundary row could invalidate a local repair.
        return ExactNeighborhoodCondition(bools.indices.associateWith { bools[it] }, pins, pins, cutoff)
    }

    fun relaxationNeighborhood(hint: LinearRepairGuidance.Hint?, centers: List<BigFraction>,
        bools: BooleanArray, cutoff: BigFraction?, budget: SourceLpBudget,
        token: ExecutionControl): ExactNeighborhoodCondition? {
        if (hint == null || cutoff == null || centers.size < integers || token()) return null
        val pins = linkedMapOf<Int, BigFraction>()
        var free = 0
        for (id in inputs?.asIterable() ?: (0 until integers)) {
            val center = centers[id]
            val bits = center.num.magnitudeBitLength().toLong() + center.den.magnitudeBitLength()
            if (bits > MAX_GUIDANCE_SCALAR_BITS ||
                !payRefutation(budget, token, 1L + bits * bits / 64L, 128L + 8L * bits)) return null
            val value = center.toDouble()
            val relaxed = hint.integer(id)
            if (!value.isFinite()) return null
            if (relaxed != null && abs(relaxed - value) <= 1e-6) pins[id] = center else free++
        }
        if (token() || pins.isEmpty() || free == 0) return null
        selectedIntegers += free
        // Agreement selects a subproblem; it proves neither a fixing nor a source bound.
        return ExactNeighborhoodCondition(bools.indices.associateWith { bools[it] }, pins, pins, cutoff)
    }

    fun binaryNeighborhood(hint: LinearRepairGuidance.Hint?, centers: List<BigFraction>, bounds: IntBounds,
        bools: BooleanArray, cutoff: BigFraction?, budget: SourceLpBudget,
        token: ExecutionControl): ExactNeighborhoodCondition? {
        if (inputs != null || hint == null || cutoff == null || integers !in 2..4096 ||
            centers.size < integers || token()) return null
        val pins = linkedMapOf<Int, BigFraction>()
        for (id in 0 until integers) {
            if (!payRefutation(budget, token, 16L, 256L) ||
                !bounds.hasLower(id) || !bounds.hasUpper(id) || bounds.lower(id) < 0L || bounds.upper(id) > 1L
            ) return null
            val center = centers[id]
            if (center != BigFraction.ZERO && center != BigFraction.ONE) return null
            val relaxed = hint.integer(id) ?: return null
            if (center.isZero && abs(relaxed) <= 1e-6) pins[id] = BigFraction.ZERO
        }
        if (token() || pins.size == integers ||
            !payRefutation(budget, token, integers.toLong(), 256L * integers)) return null
        selectedIntegers += integers - pins.size
        // Incumbent ones stay free so a repair can exchange them for several entering variables.
        return ExactNeighborhoodCondition(bools.indices.associateWith { bools[it] }, pins, pins, cutoff)
    }

    private fun connected(roots: List<Int>, count: Int,
        maximumVisits: Int = MAX_SELECTION_VISITS, advisory: Boolean = false): ConnectedSelection {
        val selected = roots.toCollection(linkedSetOf())
        val seenColumns = IntHashSet().also { seen -> roots.forEach { seen.add(it) } }
        val seenRows = IntHashSet()
        val queue = IntArrayDeque().also { queue -> roots.forEach { queue.addLast(it) } }
        var visits = 0
        while (queue.isNotEmpty() && selected.size < count && visits < maximumVisits) {
            val column = queue.removeFirst()
            for (row in occurrences[column]) {
                if (visits == maximumVisits) break
                visits++
                if ((advisory && ignoredPrivateRow(row)) || !seenRows.add(row)) continue
                for (peer in rows[row]) {
                    if (visits == maximumVisits) break
                    visits++
                    if (!seenColumns.add(peer)) continue
                    queue.addLast(peer)
                    if (peer < integers && (eligible == null || eligible[peer])) selected += peer
                    if (selected.size == count) break
                }
                if (selected.size == count) break
            }
        }
        visitedEntries += visits
        return ConnectedSelection(selected, queue.isEmpty() && visits < maximumVisits)
    }

    companion object {
        fun prepare(
            source: SourceProofContext, integers: Int, reals: Int, cancellation: ExecutionControl,
            sourceIntegers: IntArray? = null, privateObjective: Boolean = false,
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
            val objective = if (privateObjective) source.singleObjectiveCoordinate()
                ?.takeIf { it.kind == SourceCoordinate.Kind.Real } else null
            var objectiveRows = 0
            var firstObjective: Pair<Int, SourcePremise>? = null
            var secondObjective: Pair<Int, SourcePremise>? = null
            for (premise in source.premises.values) {
                cancellation.charge(1)
                if (cancellation() || ++entries > MAX_INDEX_ENTRIES) return null
                val columns = IntArrayList()
                var hasObjective = false
                for ((coordinate, coefficient) in premise.inequality.coefficients) {
                    cancellation.charge(1)
                    if (cancellation() || ++entries > MAX_INDEX_ENTRIES) return null
                    if (coefficient.isZero || coordinate.kind == SourceCoordinate.Kind.Boolean) continue
                    val column = if (coordinate.kind == SourceCoordinate.Kind.Integer) coordinate.id
                    else integers + coordinate.id
                    columns.add(column)
                    if (coordinate == objective) hasObjective = true
                }
                for (coordinate in premise.integerPins.keys) {
                    cancellation.charge(1)
                    if (cancellation() || ++entries > MAX_INDEX_ENTRIES) return null
                    if (coordinate.id !in columns) columns.add(coordinate.id)
                }
                if (columns.size < 2) continue
                if (hasObjective) {
                    objectiveRows++
                    when (objectiveRows) {
                        1 -> firstObjective = rows.size to premise
                        2 -> secondObjective = rows.size to premise
                        else -> {
                            firstObjective = null
                            secondObjective = null
                        }
                    }
                }
                columns.forEach { column ->
                    val adjacent = occurrences[column] ?: IntArrayList(1).also { occurrences[column] = it }
                    adjacent.add(rows.size)
                }
                rows += columns.toIntArray()
            }
            val definition = if (objectiveRows == 2) {
                val first = requireNotNull(firstObjective)
                val second = requireNotNull(secondObjective)
                ObjectiveDefinition(first.first, first.second, second.first, second.second)
            } else null
            return if (cancellation()) null else
                JointRepairNeighborhood(integers, rows,
                    Array(occurrences.size) { occurrences[it]?.toIntArray() ?: emptyIntArray() }, inputs, definition)
        }
    }
}

private const val MAX_FREE_INTEGERS = 32
private const val MAX_SELECTION_VISITS = 8192
private const val MAX_INDEX_ENTRIES = 1_048_576
private const val MAX_GUIDANCE_SCALAR_BITS = 4096
private const val MAX_REFUTED_PINS = 4
private const val MAX_REFUTATION_BOOLEANS = 4096
