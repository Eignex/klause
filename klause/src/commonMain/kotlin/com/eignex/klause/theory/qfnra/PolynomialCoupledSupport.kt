package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.Polynomial
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.theory.qfnra.PolynomialSigns.ALL_SIGNS
import com.eignex.klause.theory.qfnra.PolynomialSigns.ZERO_SIGN
import com.eignex.klause.theory.qfnra.PolynomialSigns.negateSigns
import com.eignex.klause.theory.qfnra.PolynomialSigns.relationSigns

// Premises are indices in the caller's signed assignment, never source-coordinate identifiers.
internal class PolynomialCoupledSupport(val atom: PolynomialAtom, val premises: Set<Int>)

internal fun prepareCoupledSupport(
    input: List<PolynomialCoupledSupport>,
    arithmetic: PolynomialArithmetic,
): List<PolynomialCoupledSupport>? {
    var current = input
    var changed = false
    while (true) {
        coupledEqualities(current, arithmetic)?.let { current = it; changed = true }
        val equations = current.filter { arithmetic.step(); it.atom.relation == PolynomialRelation.EQ }
        val graph = equationalSection(equations.map { arithmetic.step(); it.atom.polynomial },
            current.map { arithmetic.step(); it.atom.polynomial }, arithmetic) ?: break
        val definition = equations.first {
            arithmetic.step()
            it.atom.polynomial == graph.equation
        }
        val updated = ArrayList<PolynomialCoupledSupport>()
        for (entry in current) {
            arithmetic.step()
            if (entry === definition) continue
            if (graph.variable !in entry.atom.polynomial.variables) updated.add(entry)
            else {
                val polynomial = arithmetic.substitute(entry.atom.polynomial, graph.variable, graph.replacement)
                if (!fitsCellLimits(polynomial, arithmetic)) return null
                val premises = LinkedHashSet<Int>()
                for (premise in entry.premises + definition.premises) {
                    arithmetic.step()
                    premises.add(premise)
                }
                updated.add(PolynomialCoupledSupport(PolynomialAtom(polynomial, entry.atom.relation), premises))
            }
        }
        arithmetic.step()
        current = updated
        changed = true
    }
    return current.takeIf { changed }
}

private fun coupledEqualities(
    input: List<PolynomialCoupledSupport>,
    arithmetic: PolynomialArithmetic,
): List<PolynomialCoupledSupport>? {
    val groups = LinkedHashMap<Polynomial, MutableList<PolynomialCoupledSupport>>()
    for (entry in input) {
        arithmetic.step()
        val polynomial = entry.atom.polynomial
        val leading = polynomial.terms.firstOrNull()?.coefficient
        val key = if (leading == null) polynomial else arithmetic.scale(polynomial, leading.reciprocal())
        groups.getOrPut(key) { ArrayList() }.add(entry)
    }
    val result = ArrayList<PolynomialCoupledSupport>()
    var changed = false
    for ((polynomial, entries) in groups) {
        arithmetic.step()
        var signs = ALL_SIGNS
        for (entry in entries) {
            arithmetic.step()
            val allowed = relationSigns(entry.atom.relation)
            val positive = (entry.atom.polynomial.terms.firstOrNull()?.coefficient?.signum() ?: 1) > 0
            signs = signs and if (positive) allowed else negateSigns(allowed)
        }
        if (entries.size < 2 || signs != ZERO_SIGN) {
            for (entry in entries) { arithmetic.step(); result.add(entry) }
            continue
        }
        val premises = LinkedHashSet<Int>()
        for (entry in entries) for (premise in entry.premises) { arithmetic.step(); premises.add(premise) }
        result.add(PolynomialCoupledSupport(PolynomialAtom(polynomial, PolynomialRelation.EQ), premises))
        changed = true
    }
    return result.takeIf { changed }
}
