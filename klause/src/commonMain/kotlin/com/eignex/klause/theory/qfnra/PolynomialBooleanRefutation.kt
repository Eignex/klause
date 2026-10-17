package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.util.magnitudeBitLength

internal fun refutePolynomialBooleans(
    nodes: List<FormulaNode>,
    root: Int,
    atoms: List<PolynomialAtom>,
    arithmetic: PolynomialArithmetic,
    maximumAtoms: Int,
    maximumTerms: Int,
    maximumBits: Int,
): Boolean {
    if (atoms.size > maximumAtoms || nodes.size > maximumTerms) return false
    var terms = 0
    for (atom in atoms) {
        arithmetic.step()
        if (atom.polynomial.terms.size > maximumTerms - terms) return false
        terms += atom.polynomial.terms.size
        for (term in atom.polynomial.terms) {
            arithmetic.step()
            if (term.coefficient.num.magnitudeBitLength() > maximumBits ||
                term.coefficient.den.magnitudeBitLength() > maximumBits) return false
        }
    }
    var references = 0
    for (node in nodes) {
        arithmetic.step()
        val count = when (node) {
            is FormulaNode.Not -> 1
            is FormulaNode.Junction -> node.children.size
            else -> 0
        }
        if (count > maximumTerms - references) return false
        references += count
    }
    // Free numeric predicates weaken the source; only exhaustion can refute it.
    return refutePolynomialBooleanState(PolynomialBooleanPropagation(nodes, root, arithmetic), arithmetic)
}

private fun refutePolynomialBooleanState(
    state: PolynomialBooleanPropagation,
    arithmetic: PolynomialArithmetic,
): Boolean {
    if (!state.initialize()) return true
    while (true) {
        arithmetic.step()
        if (state.propagate()) {
            if (!state.decide()) return false
        } else if (!state.backtrack()) {
            arithmetic.step()
            return true
        }
    }
}
