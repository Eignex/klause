package com.eignex.klause.presolve.linear

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.CheckedLongOverflowException
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.addExact
import com.eignex.klause.util.mulExact

internal class AffineObjective(private val source: LinearObjective, private val cancellation: Cancellation) {
    private val coefficients = HashMap<Int, Long>()
    private var constant = source.constant
    private var changed = false
    var interrupted = false
        private set

    init {
        for (variable in source.intCoefficientValues.indices) {
            if (variable and 63 == 0) {
                cancellation.charge(64L)
                if (cancellation()) {
                    interrupted = true
                    break
                }
            }
            val coefficient = source.intCoefficientValues[variable]
            if (coefficient != 0L) coefficients[variable] = coefficient
        }
    }

    fun permits(definition: Linear, pivot: Int): Boolean {
        val variable = definition.vars[pivot]
        if (variable !in coefficients) return true
        if (definition.vars.size != 2) return false
        val row = definition.integerConstants ?: return false
        val coefficient = row.coeff(pivot)
        if (coefficient == 0L || coefficient == Long.MIN_VALUE || row.coeff(1 - pivot) != -coefficient) return false
        if (row.bound % coefficient != 0L || (row.bound == Long.MIN_VALUE && coefficient == -1L)) return false
        return substitution(variable, definition.vars[1 - pivot], row.bound / coefficient) != null
    }

    fun substitute(candidate: AffineSingletons.AffineCandidate): (() -> Unit)? {
        if (candidate.x !in coefficients) return {}
        if (candidate.termVars.size != 1 || candidate.termCoeffs[0] != 1L) return null
        return substitution(candidate.x, candidate.termVars[0], candidate.constTerm)
    }

    private fun substitution(variable: Int, partner: Int, offset: Long): (() -> Unit)? {
        val coefficient = coefficients[variable] ?: return {}
        return try {
            val nextCoefficient = addExact(coefficients[partner] ?: 0L, coefficient)
            val nextConstant = addExact(constant, mulExact(coefficient, offset))
            if (nextCoefficient == Long.MIN_VALUE || nextConstant == Long.MIN_VALUE) return null
            val commit: () -> Unit = {
                coefficients.remove(variable)
                if (nextCoefficient == 0L) coefficients.remove(partner) else coefficients[partner] = nextCoefficient
                constant = nextConstant
                changed = true
            }
            commit
        } catch (_: CheckedLongOverflowException) {
            null
        }
    }

    fun protectRemaining(protected: IntHashSet) {
        for (variable in coefficients.keys) protected.add(variable)
    }

    fun result(): LinearObjective? {
        if (!changed) return null
        val size = maxOf(source.intCoefficientValues.size, (coefficients.keys.maxOrNull() ?: -1) + 1)
        cancellation.charge(size.toLong())
        val values = LongArray(size)
        for ((variable, coefficient) in coefficients) values[variable] = coefficient
        return source.copy(intCoefficients = values, constant = constant)
    }
}
