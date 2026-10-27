package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.ir.PolynomialRelation

// Premises are indices in the caller's signed assignment, never source-coordinate identifiers.
internal class PolynomialCoupledSupport(val atom: PolynomialAtom, val premises: Set<Int>)

internal fun prepareCoupledSupport(
    input: List<PolynomialCoupledSupport>,
    arithmetic: PolynomialArithmetic,
): List<PolynomialCoupledSupport>? {
    var current = input
    var changed = false
    while (true) {
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
