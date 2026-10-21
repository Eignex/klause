package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.theory.qfnra.PolynomialFormula.Companion.FALSE
import com.eignex.klause.theory.qfnra.PolynomialFormula.Companion.TRUE
import com.eignex.klause.theory.qfnra.PolynomialFormula.Companion.UNASSIGNED

internal class PolynomialRequiredAtoms(
    private val nodes: List<FormulaNode>,
    private val root: Int,
    private val atomCount: Int,
    private val arithmetic: PolynomialArithmetic,
) {
    private val atomNodes = nodes.indices.filter { arithmetic.step(); nodes[it] is FormulaNode.Atom }
    private var state: PolynomialBooleanPropagation? = null
    private var constants = HashMap<Int, PolynomialAtom>()
    private var inconsistent = false

    fun capture(sourceNodes: List<FormulaNode>, sourceRoot: Int, atoms: List<PolynomialAtom>): List<PolynomialAtom>? {
        var completed = false
        try {
            arithmetic.step()
            require(sourceNodes === nodes && sourceRoot == root && atoms.size == atomCount)
            // Total substitutions preserve constant predicates and the Boolean graph.
            for ((index, constant) in constants) {
                arithmetic.step()
                val atom = atoms[(nodes[index] as FormulaNode.Atom).atom]
                require(atom.relation == constant.relation && atom.polynomial == constant.polynomial)
            }
            val result = if (inconsistent) null else collect(atoms)
            arithmetic.step()
            completed = true
            return result
        } finally {
            if (!completed) {
                state = null
                constants = HashMap()
                inconsistent = false
            }
        }
    }

    private fun collect(atoms: List<PolynomialAtom>): List<PolynomialAtom>? {
        var current = state
        if (current == null) {
            current = PolynomialBooleanPropagation(nodes, root, arithmetic, skipConfirmedParents = true)
            if (!current.initialize()) {
                inconsistent = true
                return null
            }
            state = current
        }
        for (index in atomNodes) {
            arithmetic.step()
            if (index in constants) continue
            val atom = atoms[(nodes[index] as FormulaNode.Atom).atom]
            if (atom.polynomial.mainVariable != null) continue
            val sign = atom.polynomial.terms.firstOrNull()?.coefficient?.signum() ?: 0
            constants[index] = atom
            if (!current.assign(index, if (atom.holds(sign)) TRUE else FALSE)) {
                inconsistent = true
                return null
            }
        }
        if (!current.propagate()) {
            inconsistent = true
            return null
        }
        val result = ArrayList<PolynomialAtom>()
        for (index in atomNodes) {
            arithmetic.step()
            val value = current.value(index)
            if (value == UNASSIGNED) continue
            val atom = atoms[(nodes[index] as FormulaNode.Atom).atom]
            result.add(if (value == TRUE) atom else PolynomialAtom(atom.polynomial, when (atom.relation) {
                PolynomialRelation.EQ -> PolynomialRelation.NE
                PolynomialRelation.NE -> PolynomialRelation.EQ
                PolynomialRelation.LT -> PolynomialRelation.GE
                PolynomialRelation.LE -> PolynomialRelation.GT
                PolynomialRelation.GT -> PolynomialRelation.LE
                PolynomialRelation.GE -> PolynomialRelation.LT
            }))
        }
        return result
    }
}
