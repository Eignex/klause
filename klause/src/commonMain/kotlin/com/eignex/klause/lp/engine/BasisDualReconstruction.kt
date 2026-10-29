package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.basis.RationalBasisBuild
import com.eignex.klause.simplex.basis.RationalBasisFactors
import com.eignex.klause.simplex.basis.RationalBasisSolve
import com.eignex.klause.simplex.basis.RationalBasisStats
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation

private class BasisDualStop(val reason: String) : RuntimeException()

internal fun reconstructBasisDual(
    authority: ReconstructionAuthority,
    basis: Basis,
    layout: LogicalBasisLayout,
    candidate: List<BigFraction>,
    meter: ReconstructionMeter,
): List<BigFraction>? = BasisDualRun(authority, basis, layout, candidate, meter).run()

private class BasisDualRun(
    private val a: ReconstructionAuthority,
    private val basis: Basis,
    private val layout: LogicalBasisLayout,
    candidate: List<BigFraction>,
    private val meter: ReconstructionMeter,
) {
    private class Deferred(val row: Int, val pivot: BigFraction, val rhs: BigFraction,
        val others: List<Pair<Int, BigFraction>>)

    private val y: MutableList<BigFraction>
    private val equations: List<List<Pair<Int, BigFraction>>>
    private val byRow: Array<ArrayList<Pair<Int, BigFraction>>>
    private val rhs: MutableList<BigFraction>
    private val activeRow: BooleanArray
    private val activeEquation: BooleanArray
    private val rowDegree: IntArray
    private val equationDegree: IntArray
    private val rows = ArrayDeque<Int>()
    private val columns = ArrayDeque<Int>()
    private val deferred = ArrayList<Deferred>()
    private var peeledRows = 0
    private var peeledColumns = 0
    private var coreRows = 0
    private var components = 0
    private var largest = 0
    private var solved = 0
    private var openBasicColumns = 0
    private var restrictedComponents = 0
    private var restrictedColumns = 0
    private var factorWork = 0L
    private var factorAllocation = 0L
    private var decline: String? = null
    private var factorWorkLeft = 0L
    private var factorAllocationLeft = 0L
    private val token = Cancellation { meter.poll(); false }

    init {
        meter.phase = ReconstructionPhase.BASIS
        meter.storage(a.m * 104L + layout.structuralSlots.size * 80L + 256L)
        y = candidate.toMutableList()
        activeRow = BooleanArray(a.m)
        for (row in layout.retainedRows) activeRow[row] = true
        for (row in layout.logicalSlots.indices) {
            meter.step()
            val slot = layout.logicalSlots[row]
            if (slot >= 0) y[row] = a.costs[basis.basicVars[slot]]
        }
        rhs = layout.structuralSlots.map { a.costs[basis.basicVars[it]] }.toMutableList()
        openBasicColumns = layout.structuralSlots.count {
            meter.step()
            val bounds = a.bounds[basis.basicVars[it]]
            bounds.lower == null || bounds.upper == null
        }
        byRow = Array(a.m) { ArrayList() }
        equations = layout.structuralSlots.mapIndexed { equation, slot ->
            val support = ArrayList<Pair<Int, BigFraction>>()
            for ((row, coefficient) in a.matrix[basis.basicVars[slot]]) {
                meter.step()
                if (coefficient.isZero) continue
                if (activeRow[row]) {
                    meter.storage(96L)
                    support += row to coefficient
                    byRow[row] += equation to coefficient
                } else {
                    rhs[equation] = subtract(rhs[equation], coefficient, y[row])
                }
            }
            support
        }
        activeEquation = BooleanArray(equations.size) { true }
        rowDegree = IntArray(a.m) { byRow[it].size }
        equationDegree = IntArray(equations.size) { equations[it].size }
        for (equation in equations.indices) if (equationDegree[equation] == 1) rows.addLast(equation)
        for (row in layout.retainedRows) if (rowDegree[row] == 1) columns.addLast(row)
    }

    fun run(): List<BigFraction>? = try {
        if (layout.retainedRows.any { meter.step(); rowDegree[it] == 0 } ||
            equationDegree.any { meter.step(); it == 0 }
        ) {
            throw BasisDualStop("SINGULAR")
        }
        peel()
        solveComponents()
        for (entry in deferred.asReversed()) {
            var value = entry.rhs
            for ((row, coefficient) in entry.others) value = subtract(value, coefficient, y[row])
            y[entry.row] = divide(value, entry.pivot)
        }
        meter.poll()
        y.toList()
    } catch (stop: BasisDualStop) {
        decline = stop.reason
        null
    } finally {
        meter.basisRepair = ReconstructionBasisMetrics(peeledRows, peeledColumns, coreRows, components,
            largest, solved, factorWork, factorAllocation, openBasicColumns, decline,
            restrictedComponents, restrictedColumns)
    }

    // Singleton elimination preserves the remaining equations without creating factor fill.
    private fun peel() {
        while (rows.isNotEmpty() || columns.isNotEmpty()) {
            meter.step()
            if (rows.isNotEmpty()) {
                val equation = rows.removeFirst()
                if (!activeEquation[equation] || equationDegree[equation] != 1) continue
                val pivot = equations[equation].first { meter.step(); activeRow[it.first] }
                val row = pivot.first
                y[row] = divide(rhs[equation], pivot.second)
                activeEquation[equation] = false
                activeRow[row] = false
                peeledRows++
                for ((other, coefficient) in byRow[row]) {
                    meter.step()
                    if (!activeEquation[other]) continue
                    rhs[other] = subtract(rhs[other], coefficient, y[row])
                    if (--equationDegree[other] == 0) throw BasisDualStop("SINGULAR")
                    if (equationDegree[other] == 1) rows.addLast(other)
                }
            } else {
                val row = columns.removeFirst()
                if (!activeRow[row] || rowDegree[row] != 1) continue
                val pivot = byRow[row].first { meter.step(); activeEquation[it.first] }
                val equation = pivot.first
                meter.storage(equations[equation].size * 24L + 64L)
                val others = equations[equation].filter { meter.step(); activeRow[it.first] && it.first != row }
                deferred += Deferred(row, pivot.second, rhs[equation], others)
                activeRow[row] = false
                activeEquation[equation] = false
                peeledColumns++
                for ((other, _) in others) {
                    meter.step()
                    if (--rowDegree[other] == 0) throw BasisDualStop("SINGULAR")
                    if (rowDegree[other] == 1) columns.addLast(other)
                }
            }
        }
    }

    private fun solveComponents() {
        meter.storage(a.m * 128L + equations.size * 64L)
        val parent = IntArray(a.m) { it }
        val size = IntArray(a.m) { 1 }
        fun root(row: Int): Int {
            var current = row
            while (parent[current] != current) {
                meter.step()
                parent[current] = parent[parent[current]]
                current = parent[current]
            }
            return current
        }
        for (equation in equations.indices) {
            meter.step()
            if (!activeEquation[equation]) continue
            var first = -1
            for ((row, _) in equations[equation]) {
                meter.step()
                if (!activeRow[row]) continue
                if (first < 0) first = row
                var left = root(first)
                var right = root(row)
                if (left == right) continue
                if (size[left] < size[right]) {
                    val previous = left
                    left = right
                    right = previous
                }
                parent[right] = left
                size[left] += size[right]
            }
        }
        val groups = LinkedHashMap<Int, ArrayList<Int>>()
        for (row in layout.retainedRows) {
            meter.step()
            if (activeRow[row]) groups.getOrPut(root(row)) { ArrayList() }.add(row)
        }
        val groupedEquations = HashMap<Int, ArrayList<Int>>()
        for (equation in equations.indices) {
            meter.step()
            if (!activeEquation[equation]) continue
            val row = equations[equation].first { meter.step(); activeRow[it.first] }.first
            groupedEquations.getOrPut(root(row)) { ArrayList() }.add(equation)
        }
        coreRows = groups.values.sumOf { it.size }
        components = groups.size
        largest = groups.values.maxOfOrNull { it.size } ?: 0
        val limits = meter.basisLimits()
        factorWorkLeft = limits.work
        factorAllocationLeft = limits.allocationBytes
        val local = IntArray(a.m) { -1 }
        for ((key, group) in groups) {
            meter.step()
            val selected = groupedEquations[key] ?: throw BasisDualStop("SINGULAR")
            if (selected.size != group.size) throw BasisDualStop("SINGULAR")
            if (group.size > limits.dimension) {
                val open = selected.filter { equation ->
                    meter.step()
                    val bounds = a.bounds[basis.basicVars[layout.structuralSlots[equation]]]
                    bounds.lower == null || bounds.upper == null
                }
                if (open.size > limits.dimension) {
                    decline = "DIMENSION"
                    continue
                }
                val matched = matchOpen(group, open)
                if (matched == null) {
                    decline = "OPEN_RANK"
                    continue
                }
                if (open.isNotEmpty() && !solve(matched, open, local, restricted = true)) break
                restrictedComponents++
                restrictedColumns += open.size
                continue
            }
            if (!solve(group, selected, local)) break
        }
    }

    private fun matchOpen(group: List<Int>, selected: List<Int>): List<Int>? {
        if (selected.isEmpty()) return emptyList()
        meter.step(a.m.toLong())
        meter.storage(a.m * 8L + selected.size * 8L)
        val matched = IntArray(a.m) { -1 }
        val seen = IntArray(a.m)
        var stamp = 0
        fun augment(index: Int): Boolean {
            for ((row, _) in equations[selected[index]]) {
                meter.step()
                if (!activeRow[row] || seen[row] == stamp) continue
                seen[row] = stamp
                val previous = matched[row]
                if (previous < 0 || augment(previous)) {
                    matched[row] = index
                    return true
                }
            }
            return false
        }
        for (index in selected.indices) {
            stamp++
            if (!augment(index)) return null
        }
        val result = IntArray(selected.size) { -1 }
        for (row in group) {
            meter.step()
            if (matched[row] >= 0) result[matched[row]] = row
        }
        return result.toList()
    }

    private fun solve(
        group: List<Int>, selected: List<Int>, local: IntArray, restricted: Boolean = false,
    ): Boolean {
        val size = group.size
        meter.step(size.toLong() * size)
        meter.storage(size.toLong() * size * 16L + size * 80L)
        for (id in group.indices) local[group[id]] = id
        val matrix = List(size) { MutableList(size) { BigFraction.ZERO } }
        val target = selected.map { rhs[it] }.toMutableList()
        for (id in selected.indices) {
            for ((row, value) in equations[selected[id]]) {
                meter.step()
                if (!activeRow[row]) continue
                if (local[row] >= 0) matrix[id][local[row]] = value else {
                    check(restricted)
                    target[id] = subtract(target[id], value, y[row])
                }
            }
        }
        val factorAllowance = allowance()
        val build = RationalBasisFactors.factor(matrix, limits = factorAllowance, cancellation = token)
        record(build.stats)
        val factors = when (build) {
            is RationalBasisBuild.Ready -> build.factors
            is RationalBasisBuild.Singular -> {
                if (!restricted) throw BasisDualStop("SINGULAR")
                decline = "OPEN_SINGULAR"
                return false
            }
            is RationalBasisBuild.Declined -> {
                decline = build.reason.name
                return false
            }
        }
        val result = factors.solve(target, limits = allowance(), cancellation = token)
        record(result.stats)
        when (result) {
            is RationalBasisSolve.Solved -> {
                for (id in group.indices) y[group[id]] = meter.fraction(result.values[id])
                if (!restricted) solved++
            }
            is RationalBasisSolve.Declined -> {
                decline = result.reason.name
                return false
            }
        }
        for (row in group) local[row] = -1
        return true
    }

    private fun record(stats: RationalBasisStats) {
        meter.recordBasis(stats)
        factorWork += stats.work
        factorAllocation += stats.allocationBytes
        factorWorkLeft -= stats.work
        factorAllocationLeft -= stats.allocationBytes
    }

    private fun allowance() = meter.basisLimits().let { limits ->
        limits.copy(work = minOf(limits.work, factorWorkLeft),
            allocationBytes = minOf(limits.allocationBytes, factorAllocationLeft))
    }

    private fun subtract(value: BigFraction, coefficient: BigFraction, other: BigFraction): BigFraction =
        meter.fraction(value - meter.fraction(coefficient * other))

    private fun divide(value: BigFraction, coefficient: BigFraction): BigFraction =
        meter.fraction(value * meter.fraction(coefficient.reciprocal()))
}
