package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.theory.qfnra.PolynomialFormula.Companion.FALSE
import com.eignex.klause.theory.qfnra.PolynomialFormula.Companion.TRUE
import com.eignex.klause.theory.qfnra.PolynomialFormula.Companion.UNASSIGNED

internal data class PolynomialBooleanRegion(val atoms: Map<Int, Boolean>, val bools: Map<Int, Boolean>)

internal class PolynomialBooleanRegions(
    private val nodes: List<FormulaNode>,
    private val root: Int,
    private val atoms: List<PolynomialAtom>,
    private val arithmetic: PolynomialArithmetic,
) {
    private val state = PolynomialBooleanPropagation(nodes, root, arithmetic, skipConfirmedParents = true)
    private val exclusions = ArrayList<List<Literal>>()

    fun prepare(): Iterator<PolynomialBooleanRegion?> = sequence<PolynomialBooleanRegion?> {
        if (!initialize()) return@sequence
        while (true) {
            arithmetic.step()
            if (!propagate()) {
                if (!state.backtrack()) return@sequence
                yield(null)
                continue
            }
            val proof = proof()
            if (proof == null) {
                check(state.decide())
            } else {
                yield(proof.region)
                arithmetic.step()
                // Exclusions record visited regions; only the numeric caller can certify their exhaustion.
                exclusions.add(proof.literals)
            }
            yield(null)
        }
    }.iterator()

    private fun initialize(): Boolean {
        if (!state.initialize()) return false
        // Constant predicates have the same truth in every cell of this conditioned query.
        for ((index, node) in nodes.withIndex()) {
            arithmetic.step()
            if (node !is FormulaNode.Atom) continue
            val atom = atoms[node.atom]
            if (atom.polynomial.mainVariable != null) continue
            val sign = atom.polynomial.terms.firstOrNull()?.coefficient?.signum() ?: 0
            if (!state.assign(index, if (atom.holds(sign)) TRUE else FALSE)) return false
        }
        return true
    }

    private fun propagate(): Boolean {
        while (true) {
            arithmetic.step()
            val mark = state.trailSize
            if (!state.propagate()) return false
            for (excluded in exclusions) {
                arithmetic.step()
                if (!propagate(excluded)) return false
            }
            if (state.trailSize == mark) return true
        }
    }

    private fun propagate(excluded: List<Literal>): Boolean {
        var unknowns = 0
        var pending: Literal? = null
        for (literal in excluded) {
            arithmetic.step()
            val value = state.value(literal.node)
            if (value == UNASSIGNED) {
                unknowns++
                pending = literal
            } else if (value != literal.value) return true
        }
        if (unknowns == 0) return false
        if (unknowns == 1) {
            val literal = checkNotNull(pending)
            return state.assign(literal.node, TRUE - literal.value)
        }
        return true
    }

    private fun proof(): Proof? {
        val witnessed = BooleanArray(nodes.size)
        val selected = IntArray(nodes.size) { -1 }
        for ((index, node) in nodes.withIndex()) {
            arithmetic.step()
            val value = state.value(index)
            witnessed[index] = when (node) {
                is FormulaNode.Constant -> value == if (node.value) TRUE else FALSE
                is FormulaNode.BooleanVariable, is FormulaNode.Atom -> value != UNASSIGNED
                is FormulaNode.Not -> witnessed[node.child] && value == TRUE - state.value(node.child)
                is FormulaNode.Junction -> witnessed(index, node, witnessed, selected)
            }
        }
        if (!witnessed[root]) return null
        return capture(selected)
    }

    private fun witnessed(
        index: Int,
        node: FormulaNode.Junction,
        witnessed: BooleanArray,
        selected: IntArray,
    ): Boolean {
        val value = state.value(index)
        if (value == UNASSIGNED) return false
        val decisive = if (node.conjunction) FALSE else TRUE
        if (value == decisive) {
            for (child in node.children) {
                arithmetic.step()
                if (witnessed[child] && state.value(child) == value) {
                    selected[index] = child
                    return true
                }
            }
            return false
        }
        for (child in node.children) {
            arithmetic.step()
            if (!witnessed[child] || state.value(child) != value) return false
        }
        return true
    }

    private fun capture(selected: IntArray): Proof {
        val visited = BooleanArray(nodes.size)
        val pending = ArrayDeque<Int>()
        pending.addLast(root)
        while (pending.isNotEmpty()) {
            arithmetic.step()
            val index = pending.removeLast()
            if (visited[index]) continue
            visited[index] = true
            when (val node = nodes[index]) {
                is FormulaNode.Not -> pending.addLast(node.child)
                is FormulaNode.Junction -> {
                    if (selected[index] >= 0) pending.addLast(selected[index])
                    else for (child in node.children) {
                        arithmetic.step()
                        pending.addLast(child)
                    }
                }
                else -> Unit
            }
        }
        val atoms = LinkedHashMap<Int, Boolean>()
        val bools = LinkedHashMap<Int, Boolean>()
        val literals = ArrayList<Literal>()
        for ((index, node) in nodes.withIndex()) {
            arithmetic.step()
            if (!visited[index]) continue
            val value = state.value(index)
            when (node) {
                is FormulaNode.Atom -> {
                    atoms[node.atom] = value == TRUE
                    literals.add(Literal(index, value))
                }
                is FormulaNode.BooleanVariable -> {
                    bools[node.variable] = value == TRUE
                    literals.add(Literal(index, value))
                }
                else -> Unit
            }
        }
        return Proof(PolynomialBooleanRegion(atoms, bools), literals)
    }

    private class Literal(val node: Int, val value: Int)
    private class Proof(val region: PolynomialBooleanRegion, val literals: List<Literal>)
}
