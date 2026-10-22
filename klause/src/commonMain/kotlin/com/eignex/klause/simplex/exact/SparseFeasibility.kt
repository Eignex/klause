package com.eignex.klause.simplex.exact

import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.magnitudeBitLength

internal interface SparseFeasibilityBounds {
    val size: Int
    fun lower(column: Int): BigFraction?
    fun upper(column: Int): BigFraction?
}

internal interface SparseFeasibilityModel {
    val n: Int
    val m: Int
    fun rhs(row: Int): BigFraction
    fun column(column: Int, visit: (Int, BigFraction) -> Unit)
}

internal class SparseFeasibilityBudget(limits: ExactContinuationLimits, private val token: Cancellation) {
    private val meter = ContinuationBudget(limits, token)
    val work get() = meter.work
    val allocation get() = meter.allocation

    private fun <T> debit(action: () -> T): T {
        val before = meter.work
        val value = try {
            action()
        } finally {
            token.charge(meter.work - before)
        }
        if (token()) throw ContinuationStop(ContinuationDecline.CANCELLED)
        return value
    }

    fun step(units: Long = 1L, bytes: Long = 0L) = debit { meter.step(units, bytes) }
    fun number(value: BigFraction): BigFraction = debit { meter.fraction(value) }
    private fun operands(a: BigFraction, b: BigFraction) = debit { meter.preflight(a, b) }

    fun retain(bytes: Long) {
        step()
        if (bytes > meter.limits.maxAllocation) throw ContinuationStop(ContinuationDecline.ALLOCATION)
    }

    fun add(a: BigFraction, b: BigFraction): BigFraction {
        operands(a, b)
        return number(a + b)
    }

    fun subtract(a: BigFraction, b: BigFraction): BigFraction {
        operands(a, b)
        return number(a - b)
    }

    fun multiply(a: BigFraction, b: BigFraction): BigFraction {
        operands(a, b)
        return number(a * b)
    }

    fun reciprocal(value: BigFraction): BigFraction {
        operands(value, BigFraction.ONE)
        return number(value.reciprocal())
    }

    fun negate(value: BigFraction): BigFraction {
        operands(value, BigFraction.MINUS_ONE)
        return number(value.negated())
    }

    fun compare(a: BigFraction, b: BigFraction): Int {
        if (a === b) {
            step()
            return 0
        }
        operands(a, b)
        return a.compareTo(b)
    }
}

internal class SparseFeasibilityResult(
    val values: List<BigFraction>? = null,
    val ray: List<BigFraction>? = null,
    val pivots: Int = 0,
    val decline: ContinuationDecline? = null,
)

private fun fractionBytes(value: BigFraction): Long =
    64L + (maxOf(value.num.magnitudeBitLength(), value.den.magnitudeBitLength()).toLong() + 7L) / 4L

private class FeasibilityRow(
    val basic: Int,
    val constant: BigFraction,
    val terms: List<Pair<Int, BigFraction>>,
    val proof: Int,
) {
    val bytes = 64L + fractionBytes(constant) + terms.sumOf { 56L + fractionBytes(it.second) }
}

// Each node denotes an exact combination of the original equalities, without a dense proof row per basic.
private class FeasibilityProof(
    val source: Int = -1,
    val left: Int = -1,
    val right: Int = -1,
    val scale: BigFraction = BigFraction.ONE,
) {
    val bytes = 96L + fractionBytes(scale)
}

internal class SparseFeasibility(private val model: SparseFeasibilityModel) {
    private var rows = emptyArray<FeasibilityRow>()
    private var incidence = emptyArray<IntArray>()
    private var values = emptyArray<BigFraction>()
    private var basicRow = IntArray(0)
    private val proofs = ArrayList<FeasibilityProof>()
    private var initialized = false
    private var retainedBytes = 0L

    fun resume(
        bounds: SparseFeasibilityBounds,
        budget: SparseFeasibilityBudget,
        pivotLimit: Int,
    ): SparseFeasibilityResult {
        var pivots = 0
        try {
            budget.step(bytes = retainedBytes)
            if (model.n < 0 || model.m < 0 || model.n.toLong() + model.m != bounds.size.toLong()) {
                throw ContinuationStop(ContinuationDecline.INVALID_INPUT)
            }
            if (!initialized) initialize(bounds, budget)
            reseat(bounds, budget)
            while (true) {
                val violated = violated(bounds, budget)
                if (violated == null) {
                    budget.step(values.size.toLong(), values.size.toLong() * 8L)
                    return SparseFeasibilityResult(values = values.toList(), pivots = pivots)
                }
                val (row, upper) = violated
                val entering = entering(rows[row], upper, bounds, budget)
                if (entering < 0) {
                    return SparseFeasibilityResult(ray = ray(rows[row], upper, budget), pivots = pivots)
                }
                if (pivots >= pivotLimit) throw ContinuationStop(ContinuationDecline.PIVOTS)
                val target = if (upper) bounds.upper(rows[row].basic) else bounds.lower(rows[row].basic)
                pivot(row, entering, requireNotNull(target), budget)
                pivots++
            }
        } catch (stop: ContinuationStop) {
            return SparseFeasibilityResult(pivots = pivots, decline = stop.reason)
        }
    }

    private fun initialize(bounds: SparseFeasibilityBounds, budget: SparseFeasibilityBudget) {
        val total = bounds.size
        budget.step(total.toLong() + model.m, total.toLong() * 64L + model.m.toLong() * 96L)
        val nextValues = Array(total) { BigFraction.ZERO }
        val nextBasic = IntArray(total) { -1 }
        val terms = Array(model.m) { ArrayList<Pair<Int, BigFraction>>() }
        val links = Array(total) { ArrayList<Int>() }
        for (column in 0 until total) nextValues[column] = clamp(BigFraction.ZERO, column, bounds, budget)
        for (column in 0 until model.n) {
            var previous = -1
            model.column(column) { row, coefficient ->
                budget.step(bytes = 80L)
                if (row !in 0 until model.m || row <= previous) {
                    throw ContinuationStop(ContinuationDecline.INVALID_INPUT)
                }
                previous = row
                if (!coefficient.isZero) {
                    terms[row] += column to budget.negate(coefficient)
                    links[column] += row
                }
            }
        }
        val nextProofs = ArrayList<FeasibilityProof>()
        val nextRows = Array(model.m) { row ->
            val constant = budget.number(model.rhs(row))
            var value = constant
            for ((column, coefficient) in terms[row]) {
                value = budget.add(value, budget.multiply(coefficient, nextValues[column]))
            }
            val basic = model.n + row
            nextBasic[basic] = row
            nextValues[basic] = value
            budget.step(terms[row].size.toLong() + 1L, 256L + terms[row].size.toLong() * 16L)
            nextProofs += FeasibilityProof(source = row)
            FeasibilityRow(basic, constant, terms[row].toList(), row)
        }
        val nextIncidence = Array(total) { column ->
            budget.step(links[column].size.toLong() + 1L, 16L + links[column].size.toLong() * 4L)
            links[column].toIntArray()
        }
        var bytes = total.toLong() * 36L + model.m.toLong() * 8L
        for (value in nextValues) {
            budget.step()
            bytes += fractionBytes(value)
        }
        for (row in nextRows) {
            budget.step(row.terms.size.toLong() + 1L)
            bytes += row.bytes
        }
        for (proof in nextProofs) {
            budget.step()
            bytes += proof.bytes
        }
        budget.step(nextIncidence.size.toLong())
        bytes += nextIncidence.sumOf { it.size.toLong() * 4L }
        budget.retain(bytes)
        rows = nextRows
        incidence = nextIncidence
        values = nextValues
        basicRow = nextBasic
        proofs.addAll(nextProofs)
        retainedBytes = bytes
        initialized = true
    }

    private fun clamp(
        value: BigFraction,
        column: Int,
        bounds: SparseFeasibilityBounds,
        budget: SparseFeasibilityBudget,
    ): BigFraction {
        val lower = bounds.lower(column)?.let(budget::number)
        val upper = bounds.upper(column)?.let(budget::number)
        if (lower != null && upper != null && budget.compare(lower, upper) > 0) {
            throw ContinuationStop(ContinuationDecline.INVALID_INPUT)
        }
        return when {
            lower != null && budget.compare(value, lower) < 0 -> lower
            upper != null && budget.compare(value, upper) > 0 -> upper
            else -> value
        }
    }

    private fun reseat(bounds: SparseFeasibilityBounds, budget: SparseFeasibilityBudget) {
        var next: Array<BigFraction>? = null
        var bytes = retainedBytes
        for (column in values.indices) {
            budget.step()
            if (basicRow[column] >= 0) continue
            val value = clamp(values[column], column, bounds, budget)
            if (value == values[column]) continue
            val current = next ?: run {
                budget.step(values.size.toLong(), values.size.toLong() * 8L)
                values.copyOf().also { next = it }
            }
            val delta = budget.subtract(value, values[column])
            bytes += fractionBytes(value) - fractionBytes(current[column])
            current[column] = value
            for (row in incidence[column]) {
                val basic = rows[row].basic
                val coefficient = coefficient(rows[row], column, budget)
                val updated = budget.add(current[basic], budget.multiply(coefficient, delta))
                bytes += fractionBytes(updated) - fractionBytes(current[basic])
                current[basic] = updated
            }
        }
        next?.let {
            budget.retain(bytes)
            values = it
            retainedBytes = bytes
        }
    }

    private fun violated(bounds: SparseFeasibilityBounds, budget: SparseFeasibilityBudget): Pair<Int, Boolean>? {
        for (column in basicRow.indices) {
            budget.step()
            val row = basicRow[column]
            if (row < 0) continue
            val lower = bounds.lower(column)
            if (lower != null && budget.compare(values[column], lower) < 0) return row to false
            val upper = bounds.upper(column)
            if (upper != null && budget.compare(values[column], upper) > 0) return row to true
        }
        return null
    }

    private fun entering(
        row: FeasibilityRow,
        upper: Boolean,
        bounds: SparseFeasibilityBounds,
        budget: SparseFeasibilityBudget,
    ): Int {
        for ((column, coefficient) in row.terms) {
            budget.step()
            val increase = (coefficient.signum() > 0) != upper
            val endpoint = if (increase) bounds.upper(column) else bounds.lower(column)
            if (endpoint == null || (if (increase) {
                    budget.compare(values[column], endpoint) < 0
                } else {
                    budget.compare(values[column], endpoint) > 0
                })
            ) return column
        }
        return -1
    }

    private fun coefficient(row: FeasibilityRow, column: Int, budget: SparseFeasibilityBudget): BigFraction {
        var lower = 0
        var upper = row.terms.lastIndex
        while (lower <= upper) {
            budget.step()
            val middle = lower + (upper - lower) / 2
            val entry = row.terms[middle]
            when {
                entry.first < column -> lower = middle + 1
                entry.first > column -> upper = middle - 1
                else -> return entry.second
            }
        }
        return BigFraction.ZERO
    }

    private fun pivot(row: Int, entering: Int, target: BigFraction, budget: SparseFeasibilityBudget) {
        val old = rows[row]
        val reciprocal = budget.reciprocal(coefficient(old, entering, budget))
        val delta = budget.multiply(budget.subtract(target, values[old.basic]), reciprocal)
        budget.step(rows.size.toLong() + values.size + incidence.size,
            rows.size.toLong() * 8L + values.size.toLong() * 8L + incidence.size.toLong() * 8L)
        val nextRows = rows.copyOf()
        val nextValues = values.copyOf()
        val nextIncidence = incidence.copyOf()
        budget.step(bytes = 256L + old.terms.size.toLong() * 64L)
        val pivotTerms = ArrayList<Pair<Int, BigFraction>>()
        for ((column, coefficient) in old.terms) {
            if (column != entering) pivotTerms += column to budget.negate(budget.multiply(coefficient, reciprocal))
        }
        budget.step(pivotTerms.size.toLong() + 1L)
        val insertion = pivotTerms.indexOfFirst { it.first > old.basic }.let { if (it < 0) pivotTerms.size else it }
        pivotTerms.add(insertion, old.basic to reciprocal)
        val nextProofs = ArrayList<FeasibilityProof>()
        val pivotProof = proofs.size
        nextProofs += FeasibilityProof(left = old.proof, scale = budget.negate(reciprocal))
        budget.step(pivotTerms.size.toLong() + 1L, 64L)
        val pivotRow = FeasibilityRow(entering, budget.negate(budget.multiply(old.constant, reciprocal)), pivotTerms, pivotProof)
        val affected = incidence[entering]
        var bytes = retainedBytes
        val changes = HashMap<Int, MutableList<Pair<Int, Boolean>>>()
        for (affectedRow in affected) {
            budget.step(bytes = 128L)
            val source = rows[affectedRow]
            val replacement = if (affectedRow == row) {
                pivotRow
            } else {
                val scale = coefficient(source, entering, budget)
                budget.step(bytes = 192L)
                val proof = proofs.size + nextProofs.size
                nextProofs += FeasibilityProof(left = source.proof, right = pivotProof, scale = scale)
                val terms = combine(source, entering, pivotRow, scale, budget)
                budget.step(terms.size.toLong() + 1L, 64L)
                FeasibilityRow(source.basic, budget.add(source.constant, budget.multiply(scale, pivotRow.constant)), terms, proof)
            }
            nextRows[affectedRow] = replacement
            bytes += replacement.bytes - source.bytes
            val basic = replacement.basic
            val value = if (affectedRow == row) {
                budget.add(values[entering], delta)
            } else {
                budget.add(values[basic], budget.multiply(coefficient(source, entering, budget), delta))
            }
            bytes += fractionBytes(value) - fractionBytes(nextValues[basic])
            nextValues[basic] = value
            incidenceChanges(source.terms, replacement.terms, affectedRow, changes, budget)
        }
        bytes += fractionBytes(target) - fractionBytes(nextValues[old.basic])
        nextValues[old.basic] = target
        budget.step(changes.size.toLong() * (33 - changes.size.countLeadingZeroBits()), changes.size.toLong() * 8L)
        for (column in changes.keys.sorted()) {
            val updated = updateIncidence(incidence[column], requireNotNull(changes[column]), budget)
            bytes += (updated.size.toLong() - incidence[column].size) * 4L
            nextIncidence[column] = updated
        }
        for (proof in nextProofs) {
            budget.step(bytes = proof.bytes)
            bytes += proof.bytes
        }
        budget.retain(bytes)
        // All operations that can decline precede publication of the algebraic checkpoint.
        proofs.addAll(nextProofs)
        rows = nextRows
        values = nextValues
        incidence = nextIncidence
        basicRow[old.basic] = -1
        basicRow[entering] = row
        retainedBytes = bytes
    }

    private fun combine(
        source: FeasibilityRow,
        entering: Int,
        pivot: FeasibilityRow,
        scale: BigFraction,
        budget: SparseFeasibilityBudget,
    ): List<Pair<Int, BigFraction>> {
        budget.step(bytes = 32L + (source.terms.size.toLong() + pivot.terms.size) * 64L)
        val terms = ArrayList<Pair<Int, BigFraction>>()
        var left = 0
        var right = 0
        while (left < source.terms.size || right < pivot.terms.size) {
            budget.step()
            if (left < source.terms.size && source.terms[left].first == entering) {
                left++
                continue
            }
            val a = source.terms.getOrNull(left)
            val b = pivot.terms.getOrNull(right)
            when {
                b == null || (a != null && a.first < b.first) -> terms.add(requireNotNull(a)).also { left++ }
                a == null || b.first < a.first -> {
                    terms += b.first to budget.multiply(scale, b.second)
                    right++
                }
                else -> {
                    val value = budget.add(a.second, budget.multiply(scale, b.second))
                    if (!value.isZero) terms += a.first to value
                    left++
                    right++
                }
            }
        }
        return terms
    }

    private fun incidenceChanges(
        before: List<Pair<Int, BigFraction>>,
        after: List<Pair<Int, BigFraction>>,
        row: Int,
        changes: MutableMap<Int, MutableList<Pair<Int, Boolean>>>,
        budget: SparseFeasibilityBudget,
    ) {
        var left = 0
        var right = 0
        while (left < before.size || right < after.size) {
            budget.step()
            val a = before.getOrNull(left)?.first ?: Int.MAX_VALUE
            val b = after.getOrNull(right)?.first ?: Int.MAX_VALUE
            when {
                a < b -> {
                    budget.step(bytes = 128L)
                    changes.getOrPut(a) { ArrayList() }.add(row to false)
                    left++
                }
                b < a -> {
                    budget.step(bytes = 128L)
                    changes.getOrPut(b) { ArrayList() }.add(row to true)
                    right++
                }
                else -> {
                    left++
                    right++
                }
            }
        }
    }

    private fun updateIncidence(before: IntArray, changes: List<Pair<Int, Boolean>>, budget: SparseFeasibilityBudget): IntArray {
        budget.step(before.size.toLong() + changes.size.toLong() * 3L, 32L + (before.size.toLong() + changes.size) * 8L)
        val after = IntArray(before.size + changes.count { it.second } - changes.count { !it.second })
        var left = 0
        var right = 0
        var out = 0
        while (left < before.size || right < changes.size) {
            budget.step()
            val a = before.getOrNull(left) ?: Int.MAX_VALUE
            val b = changes.getOrNull(right)
            when {
                b == null || a < b.first -> after[out++] = before[left++]
                b.second -> {
                    after[out++] = b.first
                    right++
                }
                else -> {
                    check(a == b.first)
                    left++
                    right++
                }
            }
        }
        check(out == after.size)
        return after
    }

    private fun ray(row: FeasibilityRow, upper: Boolean, budget: SparseFeasibilityBudget): List<BigFraction> {
        budget.step(proofs.size.toLong() + model.m, proofs.size.toLong() * 8L + model.m.toLong() * 16L)
        val weights = arrayOfNulls<BigFraction>(proofs.size)
        val ray = MutableList(model.m) { BigFraction.ZERO }
        weights[row.proof] = if (upper) BigFraction.MINUS_ONE else BigFraction.ONE
        for (index in proofs.indices.reversed()) {
            budget.step()
            val weight = weights[index]?.takeUnless { it.isZero } ?: continue
            val proof = proofs[index]
            if (proof.source >= 0) {
                ray[proof.source] = budget.add(ray[proof.source], weight)
            } else {
                val left = if (proof.right < 0) budget.multiply(weight, proof.scale) else weight
                weights[proof.left] = budget.add(weights[proof.left] ?: BigFraction.ZERO, left)
                if (proof.right >= 0) {
                    weights[proof.right] = budget.add(weights[proof.right] ?: BigFraction.ZERO,
                        budget.multiply(weight, proof.scale))
                }
            }
        }
        return ray
    }
}
