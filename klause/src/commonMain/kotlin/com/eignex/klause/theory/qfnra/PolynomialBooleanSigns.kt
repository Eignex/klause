package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.theory.qfnra.PolynomialFormula.Companion.FALSE
import com.eignex.klause.theory.qfnra.PolynomialFormula.Companion.TRUE
import com.eignex.klause.theory.qfnra.PolynomialFormula.Companion.UNASSIGNED

internal class PolynomialBooleanSigns(
    private val nodes: List<FormulaNode>,
    private val atoms: List<PolynomialAtom>,
    private val state: PolynomialBooleanPropagation,
    private val arithmetic: PolynomialArithmetic,
) {
    private val inferred = LinkedHashSet<Int>()
    private val premises = ArrayList<Int>()
    private var completed = false
    var realCount: Int = 0
        private set

    fun prepare(): Iterator<Boolean?> = sequence {
        for (atom in atoms) for (variable in atom.polynomial.variables) {
            arithmetic.step()
            require(variable in 0 until Int.MAX_VALUE)
            realCount = maxOf(realCount, variable + 1)
        }
        while (true) {
            arithmetic.step()
            if (!state.propagate()) {
                yield(false)
                return@sequence
            }
            val required = requirements()
            var domains: PolynomialSignDomains? = null
            val preparing = preparePolynomialSignPropagation(required, realCount, arithmetic) { domains = it }
            var conflict: Boolean? = null
            while (conflict == null) {
                conflict = preparing.next()
                if (conflict == null) yield(null)
            }
            if (conflict) {
                yield(false)
                return@sequence
            }
            val mark = state.trailSize
            for ((index, node) in nodes.withIndex()) {
                arithmetic.step()
                if (node !is FormulaNode.Atom || state.value(index) != UNASSIGNED) continue
                val truth = checkNotNull(domains).truth(atoms[node.atom], arithmetic) ?: continue
                check(state.assign(index, if (truth) TRUE else FALSE))
                arithmetic.step()
                inferred.add(index)
                yield(null)
            }
            if (state.trailSize == mark) break
        }
        for ((index, node) in nodes.withIndex()) {
            arithmetic.step()
            if (node is FormulaNode.Atom && state.value(index) != UNASSIGNED && index !in inferred) {
                premises.add(index)
            }
        }
        arithmetic.step()
        completed = true
        yield(true)
    }.iterator()

    fun expand(visited: BooleanArray) {
        check(completed)
        var required = false
        for (index in inferred) {
            arithmetic.step()
            if (visited[index]) required = true
        }
        if (!required) return
        for (index in inferred) {
            arithmetic.step()
            visited[index] = false
        }
        for (index in premises) {
            arithmetic.step()
            visited[index] = true
        }
    }

    private fun requirements(): List<PolynomialAtom> {
        val required = ArrayList<PolynomialAtom>()
        for ((index, node) in nodes.withIndex()) {
            arithmetic.step()
            if (node !is FormulaNode.Atom) continue
            val value = state.value(index)
            if (value == UNASSIGNED) continue
            val atom = atoms[node.atom]
            required.add(if (value == TRUE) atom else PolynomialAtom(atom.polynomial, when (atom.relation) {
                PolynomialRelation.EQ -> PolynomialRelation.NE
                PolynomialRelation.NE -> PolynomialRelation.EQ
                PolynomialRelation.LT -> PolynomialRelation.GE
                PolynomialRelation.LE -> PolynomialRelation.GT
                PolynomialRelation.GT -> PolynomialRelation.LE
                PolynomialRelation.GE -> PolynomialRelation.LT
            }))
        }
        return required
    }
}
