package com.eignex.klause.theory.qfnia

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialResourceLimit
import com.eignex.klause.arithmetic.polynomial.ceilInteger
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.theory.qfnra.CheckedPolynomialCell
import com.eignex.klause.theory.qfnra.PolynomialCellScope
import com.eignex.klause.theory.qfnra.PolynomialCondition
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.minus
import com.eignex.klause.util.plus
import com.eignex.klause.util.unaryMinus

internal data class NonlinearIntegerCellStats(
    val observed: Long = 0,
    val retained: Long = 0,
    val declined: Long = 0,
    val excluded: Long = 0,
    val repeated: Long = 0,
    val selections: Long = 0,
    val steered: Long = 0,
    val allowed: Long = 0,
    val duplicates: Long = 0,
) {
    operator fun plus(other: NonlinearIntegerCellStats): NonlinearIntegerCellStats = NonlinearIntegerCellStats(
        observed + other.observed, retained + other.retained, declined + other.declined,
        excluded + other.excluded, repeated + other.repeated,
        selections + other.selections, steered + other.steered,
        allowed + other.allowed, duplicates + other.duplicates,
    )
}

internal class NonlinearIntegerCells(
    private val source: PolynomialRealFormula,
    private val coordinates: List<Int>,
    private val arithmetic: PolynomialArithmetic,
) {
    private class Entry(val cell: CheckedPolynomialCell, val coordinate: Int) {
        var lower: BigInt? = null
        var upper: BigInt? = null
        var prepared = false
        var declined = false
        var used = false
    }

    private val entries = ArrayList<Entry>()
    private var observed = 0L
    private var retained = 0L
    private var declined = 0L
    private var excluded = 0L
    private var repeated = 0L
    private var selections = 0L
    private var steered = 0L
    private var allowed = 0L
    private var duplicates = 0L
    val stats: NonlinearIntegerCellStats get() =
        NonlinearIntegerCellStats(observed, retained, declined, excluded, repeated,
            selections, steered, allowed, duplicates)

    fun retain(cell: CheckedPolynomialCell) {
        arithmetic.step()
        observed++
        val scope = cell.scope
        val coordinate = coordinates.indexOfFirst {
            arithmetic.step()
            it == cell.variable
        }
        if (scope !is PolynomialCellScope.Source || scope.source !== source || coordinate < 0 || entries.size == 8) {
            declined++
            return
        }
        entries.add(Entry(cell, coordinate))
        retained++
    }

    fun excludes(tuple: List<BigInt>, algebraic: AlgebraicArithmetic): Boolean {
        for (entry in entries) {
            algebraic.arithmetic.step()
            if (!available(entry, algebraic)) continue
            val value = tuple[entry.coordinate]
            if (entry.lower?.let { value < it } == true || entry.upper?.let { value > it } == true) continue
            excluded++
            if (entry.used) repeated++
            entry.used = true
            return true
        }
        return false
    }

    fun clear() = entries.clear()

    fun recordDuplicate() { duplicates++ }
    fun recordAllowed() { allowed++ }

    fun propose(
        tuple: List<BigInt>,
        condition: PolynomialCondition,
        visited: Set<List<BigInt>>,
        algebraic: AlgebraicArithmetic,
    ): List<BigInt>? {
        arithmetic.step()
        selections++
        if (entries.isEmpty()) return null
        for (entry in entries) {
            arithmetic.step()
            available(entry, algebraic)
        }
        val proposal = ArrayList<BigInt>(tuple.size)
        for (index in tuple.indices) {
            arithmetic.step()
            val id = coordinates[index]
            val lower = condition.lower[id]?.ceilInteger()
            val upper = condition.upper[id]?.let { -it.negated().ceilInteger() }
            var seed = tuple[index]
            if (lower != null && seed < lower) seed = lower
            if (upper != null && seed > upper) seed = upper
            val above = outside(index, seed, lower, upper, true)
            val below = if (above == seed) seed else outside(index, seed, lower, upper, false)
            val value = when {
                above == null -> below
                below == null -> above
                above - seed <= seed - below -> above
                else -> below
            } ?: return null
            proposal.add(value)
        }
        repeat(proposal.size) { arithmetic.step() }
        if (proposal == tuple || proposal in visited) return null
        steered++
        return proposal
    }

    private fun outside(index: Int, seed: BigInt, lower: BigInt?, upper: BigInt?, forward: Boolean): BigInt? {
        var value = seed
        // Each jump crosses one complete hull; monotonicity prevents crossing it twice.
        repeat(entries.size + 1) {
            arithmetic.step()
            if (lower?.let { value < it } == true || upper?.let { value > it } == true) return null
            val covering = entries.firstOrNull { entry ->
                arithmetic.step()
                entry.prepared && !entry.declined && entry.coordinate == index &&
                    entry.lower?.let { value < it } != true && entry.upper?.let { value > it } != true
            } ?: return value
            value = if (forward) covering.upper?.plus(BIG_ONE) ?: return null
            else covering.lower?.minus(BIG_ONE) ?: return null
        }
        return null
    }

    private fun available(entry: Entry, algebraic: AlgebraicArithmetic): Boolean {
        if (entry.declined) return false
        if (!entry.prepared) {
            try {
                prepare(entry, algebraic)
            } catch (_: PolynomialResourceLimit) {
                entry.declined = true
                declined++
                return false
            }
        }
        return true
    }

    private fun prepare(entry: Entry, algebraic: AlgebraicArithmetic) {
        val cell = entry.cell
        entry.lower = cell.lower?.let { endpoint ->
            val ceiling = algebraic.ceilInteger(endpoint)
            if (!cell.lowerClosed &&
                algebraic.compare(endpoint, algebraic.rational(BigFraction.of(ceiling, BIG_ONE))) == 0
            ) ceiling + BIG_ONE else ceiling
        }
        entry.upper = cell.upper?.let { endpoint ->
            val ceiling = algebraic.ceilInteger(endpoint)
            if (!cell.upperClosed ||
                algebraic.compare(endpoint, algebraic.rational(BigFraction.of(ceiling, BIG_ONE))) < 0
            ) ceiling - BIG_ONE else ceiling
        }
        entry.prepared = true
    }
}
