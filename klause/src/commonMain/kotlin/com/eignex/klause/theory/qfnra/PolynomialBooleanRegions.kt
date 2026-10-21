package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.theory.qfnra.PolynomialFormula.Companion.FALSE
import com.eignex.klause.theory.qfnra.PolynomialFormula.Companion.TRUE
import com.eignex.klause.theory.qfnra.PolynomialFormula.Companion.UNASSIGNED
import com.eignex.klause.util.IndexedMaxHeap

internal data class PolynomialBooleanRegion(val atoms: Map<Int, Boolean>, val bools: Map<Int, Boolean>)

internal class PolynomialBooleanRegions(
    private val nodes: List<FormulaNode>,
    private val root: Int,
    private val atoms: List<PolynomialAtom>,
    private val arithmetic: PolynomialArithmetic,
) {
    private val proofValues = IntArray(nodes.size) { arithmetic.step(); UNASSIGNED }
    private val trueChildren = IntArray(nodes.size) { arithmetic.step(); 0 }
    private val falseChildren = IntArray(nodes.size) { arithmetic.step(); 0 }
    private val selected = IntArray(nodes.size) { arithmetic.step(); -1 }
    private val dirtyProofs = IndexedMaxHeap(nodes.size)
    private var proofInitialized = false
    private val state = PolynomialBooleanPropagation(nodes, root, arithmetic,
        skipConfirmedParents = true, valueChanged = ::invalidateProof)
    private val exclusions = ArrayList<List<Literal>>()
    private val signs = PolynomialBooleanSigns(nodes, atoms, state, arithmetic)
    private var decision: Literal? = null

    fun prepare(): Iterator<PolynomialBooleanRegion?> = sequence<PolynomialBooleanRegion?> {
        if (!initialize()) return@sequence
        for (ready in signs.prepare()) {
            if (ready == false) return@sequence
            if (ready == null) yield(null)
        }
        while (true) {
            arithmetic.step()
            if (!propagate()) {
                if (!state.backtrack()) return@sequence
                yield(null)
                continue
            }
            val proof = proof()
            if (proof == null) {
                val next = decision
                check(if (next == null) state.decide() else state.decide(next.node, next.value))
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

    private fun invalidateProof(index: Int) {
        if (!proofInitialized) return
        arithmetic.step()
        // Normalization numbers every child before its parents.
        dirtyProofs.insert(index, -index.toDouble())
    }

    private fun proof(): Proof? {
        decision = null
        if (!proofInitialized) {
            for (index in nodes.indices) updateProof(index)
            proofInitialized = true
        } else {
            while (dirtyProofs.size > 0) {
                arithmetic.step()
                updateProof(dirtyProofs.extractMax())
            }
        }
        if (proofValues[root] != TRUE) {
            decision = demandedLeaf()
            return null
        }
        return capture(selected)
    }

    private fun updateProof(index: Int) {
        arithmetic.step()
        selected[index] = -1
        val value = state.value(index)
        val witnessed = when (val node = nodes[index]) {
            is FormulaNode.Constant -> value == if (node.value) TRUE else FALSE
            is FormulaNode.BooleanVariable, is FormulaNode.Atom -> value != UNASSIGNED
            is FormulaNode.Not -> value != UNASSIGNED && proofValues[node.child] == TRUE - value
            is FormulaNode.Junction -> witnessed(index, node, value)
        }
        val previous = proofValues[index]
        val current = if (witnessed) value else UNASSIGNED
        if (current == previous) return
        proofValues[index] = current
        for (parent in state.parentsOf(index)) {
            arithmetic.step()
            if (previous == TRUE) trueChildren[parent]--
            if (previous == FALSE) falseChildren[parent]--
            if (current == TRUE) trueChildren[parent]++
            if (current == FALSE) falseChildren[parent]++
            invalidateProof(parent)
        }
    }

    private fun witnessed(index: Int, node: FormulaNode.Junction, value: Int): Boolean {
        if (value == UNASSIGNED) return false
        val count = if (value == TRUE) trueChildren[index] else falseChildren[index]
        val decisive = if (node.conjunction) FALSE else TRUE
        if (value != decisive) return count == node.children.size
        if (count == 0) return false
        for (child in node.children) {
            arithmetic.step()
            if (proofValues[child] == value) {
                selected[index] = child
                return true
            }
        }
        error("Witness counts require a supporting child")
    }

    private fun demandedLeaf(): Literal? {
        var index = root
        var required = TRUE
        while (true) {
            arithmetic.step()
            val value = state.value(index)
            if ((value != UNASSIGNED && value != required) || proofValues[index] != UNASSIGNED) return null
            when (val node = nodes[index]) {
                is FormulaNode.BooleanVariable, is FormulaNode.Atom -> return Literal(index, required)
                is FormulaNode.Constant -> return null
                is FormulaNode.Not -> {
                    index = node.child
                    required = TRUE - required
                }
                is FormulaNode.Junction -> {
                    index = demandedChild(node, required) ?: return null
                }
            }
        }
    }

    private fun demandedChild(node: FormulaNode.Junction, required: Int): Int? {
        val decisive = if (node.conjunction) FALSE else TRUE
        if (required != decisive) return node.children.firstOrNull {
            arithmetic.step()
            proofValues[it] == UNASSIGNED
        }
        var pending: Int? = null
        for (child in node.children) {
            arithmetic.step()
            val value = state.value(child)
            if (value == required && proofValues[child] == UNASSIGNED) return child
            if (pending == null && value == UNASSIGNED) pending = child
        }
        return pending
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
        signs.expand(visited)
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
