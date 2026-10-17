package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialTerm
import com.eignex.klause.theory.qfnra.PolynomialSigns.ALL_SIGNS
import com.eignex.klause.theory.qfnra.PolynomialSigns.NEGATIVE_SIGN
import com.eignex.klause.theory.qfnra.PolynomialSigns.NONZERO_SIGNS
import com.eignex.klause.theory.qfnra.PolynomialSigns.POSITIVE_SIGN
import com.eignex.klause.theory.qfnra.PolynomialSigns.ZERO_SIGN
import com.eignex.klause.theory.qfnra.PolynomialSigns.addSigns
import com.eignex.klause.theory.qfnra.PolynomialSigns.multiplySigns
import com.eignex.klause.theory.qfnra.PolynomialSigns.relationSigns

internal fun preparePolynomialSignRefutation(
    atoms: List<PolynomialAtom>,
    realCount: Int,
    arithmetic: PolynomialArithmetic,
): Iterator<Boolean?> = sequence {
    val domains = IntArray(realCount) { arithmetic.step(); ALL_SIGNS }
    val adjacent = Array(realCount) { arithmetic.step(); ArrayList<Int>() }
    val constraints = ArrayList<SignConstraint>()
    val pending = ArrayDeque<Int>()
    val queued = BooleanArray(atoms.size)
    for ((index, atom) in atoms.withIndex()) {
        arithmetic.step()
        val variables = atom.polynomial.variables
        for (variable in variables) {
            arithmetic.step()
            require(variable in 0 until realCount)
            adjacent[variable].add(index)
        }
        constraints.add(SignConstraint(atom.polynomial.terms, variables, relationSigns(atom.relation)))
        pending.addLast(index)
        queued[index] = true
        yield(null)
    }
    while (pending.isNotEmpty()) {
        arithmetic.step()
        val index = pending.removeFirst()
        queued[index] = false
        val constraint = constraints[index]
        if (possibleSigns(constraint, domains, arithmetic) and constraint.allowed == 0) {
            arithmetic.step()
            yield(true)
            return@sequence
        }
        for (variable in constraint.variables) {
            arithmetic.step()
            val previous = domains[variable]
            var restricted = previous
            for (sign in listOf(NEGATIVE_SIGN, ZERO_SIGN, POSITIVE_SIGN)) {
                arithmetic.step()
                if (previous and sign != 0 &&
                    possibleSigns(constraint, domains, arithmetic, variable, sign) and constraint.allowed == 0
                ) restricted = restricted and sign.inv()
            }
            if (restricted == 0) {
                arithmetic.step()
                yield(true)
                return@sequence
            }
            if (restricted == previous) continue
            domains[variable] = restricted
            for (neighbor in adjacent[variable]) {
                arithmetic.step()
                if (!queued[neighbor]) {
                    pending.addLast(neighbor)
                    queued[neighbor] = true
                }
            }
        }
        yield(null)
    }
    arithmetic.step()
    yield(false)
}.iterator()

private class SignConstraint(val terms: List<PolynomialTerm>, val variables: List<Int>, val allowed: Int)

private fun possibleSigns(
    constraint: SignConstraint,
    domains: IntArray,
    arithmetic: PolynomialArithmetic,
    fixedVariable: Int = -1,
    fixedSign: Int = 0,
): Int {
    var sum = ZERO_SIGN
    for (term in constraint.terms) {
        arithmetic.step()
        var product = if (term.coefficient.signum() > 0) POSITIVE_SIGN else NEGATIVE_SIGN
        for (variable in term.monomial.variables) {
            arithmetic.step()
            var factor = if (variable == fixedVariable) fixedSign else domains[variable]
            if (term.monomial.exponent(variable) and 1 == 0) {
                factor = (factor and ZERO_SIGN) or
                    if (factor and NONZERO_SIGNS == 0) 0 else POSITIVE_SIGN
            }
            product = multiplySigns(product, factor)
        }
        sum = addSigns(sum, product)
    }
    return sum
}
