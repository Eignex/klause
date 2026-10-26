package com.eignex.klause.theory.qfnia

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialResourceLimit
import com.eignex.klause.arithmetic.polynomial.ceilInteger
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.theory.qfnra.CheckedPolynomialCell
import com.eignex.klause.theory.qfnra.PolynomialCellScope
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.minus
import com.eignex.klause.util.plus

internal data class NonlinearIntegerCellStats(
    val observed: Long = 0,
    val retained: Long = 0,
    val declined: Long = 0,
    val excluded: Long = 0,
    val repeated: Long = 0,
) {
    operator fun plus(other: NonlinearIntegerCellStats): NonlinearIntegerCellStats = NonlinearIntegerCellStats(
        observed + other.observed, retained + other.retained, declined + other.declined,
        excluded + other.excluded, repeated + other.repeated,
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
    val stats: NonlinearIntegerCellStats get() =
        NonlinearIntegerCellStats(observed, retained, declined, excluded, repeated)

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
            if (entry.declined) continue
            if (!entry.prepared) {
                try {
                    prepare(entry, algebraic)
                } catch (_: PolynomialResourceLimit) {
                    entry.declined = true
                    declined++
                    continue
                }
            }
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
