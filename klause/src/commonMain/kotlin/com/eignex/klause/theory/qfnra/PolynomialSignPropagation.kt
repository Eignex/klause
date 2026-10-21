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
    onRefutation: ((List<Int>) -> Unit)? = null,
): Iterator<Boolean?> = preparePolynomialSigns(atoms, realCount, arithmetic, null, onRefutation)

internal fun preparePolynomialSignPropagation(
    atoms: List<PolynomialAtom>,
    realCount: Int,
    arithmetic: PolynomialArithmetic,
    onDomains: ((PolynomialSignDomains) -> Unit)?,
): Iterator<Boolean?> = preparePolynomialSigns(atoms, realCount, arithmetic, onDomains, null)

private fun preparePolynomialSigns(
    atoms: List<PolynomialAtom>,
    realCount: Int,
    arithmetic: PolynomialArithmetic,
    onDomains: ((PolynomialSignDomains) -> Unit)?,
    onRefutation: ((List<Int>) -> Unit)?,
): Iterator<Boolean?> = sequence {
    val reasons = onRefutation?.let { PolynomialSignReasons(realCount, arithmetic, it) }
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
        var premises: List<Int>? = null
        if (possibleSigns(constraint.terms, domains, arithmetic) and constraint.allowed == 0) {
            reasons?.refute(index, constraint.variables, domains, premises)
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
                    possibleSigns(constraint.terms, domains, arithmetic, variable, sign) and constraint.allowed == 0
                ) restricted = restricted and sign.inv()
            }
            if (restricted == 0) {
                reasons?.refute(index, constraint.variables, domains, premises)
                arithmetic.step()
                yield(true)
                return@sequence
            }
            if (restricted == previous) continue
            if (reasons != null) {
                // Further restrictions by this constraint derive from the same premises.
                val support = premises ?: reasons.support(index, constraint.variables, domains).also { premises = it }
                reasons.restrict(variable, previous, restricted, support)
            }
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
    onDomains?.invoke(PolynomialSignDomains(domains))
    yield(false)
}.iterator()

internal class PolynomialSignDomains(private val domains: IntArray) {
    fun truth(atom: PolynomialAtom, arithmetic: PolynomialArithmetic): Boolean? {
        arithmetic.step()
        val signs = possibleSigns(atom.polynomial.terms, domains, arithmetic)
        val allowed = relationSigns(atom.relation)
        return when {
            signs and allowed == 0 -> false
            signs and allowed.inv() == 0 -> true
            else -> null
        }
    }
}

private class SignConstraint(val terms: List<PolynomialTerm>, val variables: List<Int>, val allowed: Int)

private fun possibleSigns(
    terms: List<PolynomialTerm>,
    domains: IntArray,
    arithmetic: PolynomialArithmetic,
    fixedVariable: Int = -1,
    fixedSign: Int = 0,
): Int {
    var sum = ZERO_SIGN
    for (term in terms) {
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

private class PolynomialSignReasons(
    realCount: Int,
    private val arithmetic: PolynomialArithmetic,
    private val onRefutation: (List<Int>) -> Unit,
) {
    private val removed = Array(realCount) { arithmetic.step(); arrayOfNulls<List<Int>>(3) }
    private val signs = listOf(NEGATIVE_SIGN, ZERO_SIGN, POSITIVE_SIGN)

    fun support(index: Int, variables: List<Int>, domains: IntArray): List<Int> {
        arithmetic.step()
        val premises = HashSet<Int>()
        premises.add(index)
        for (variable in variables) {
            arithmetic.step()
            for ((slot, sign) in signs.withIndex()) {
                arithmetic.step()
                if (domains[variable] and sign != 0) continue
                for (premise in checkNotNull(removed[variable][slot])) {
                    arithmetic.step()
                    premises.add(premise)
                }
            }
        }
        return premises.sortedWith { left, right -> arithmetic.step(); left.compareTo(right) }
    }

    fun restrict(variable: Int, previous: Int, restricted: Int, premises: List<Int>) {
        for ((slot, sign) in signs.withIndex()) {
            arithmetic.step()
            if (previous and sign != 0 && restricted and sign == 0) removed[variable][slot] = premises
        }
    }

    fun refute(index: Int, variables: List<Int>, domains: IntArray, premises: List<Int>?) {
        val completed = premises ?: support(index, variables, domains)
        arithmetic.step()
        onRefutation(completed)
    }
}
