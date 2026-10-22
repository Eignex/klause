package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.Monomial
import com.eignex.klause.arithmetic.polynomial.Polynomial
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.theory.qfnra.PolynomialSigns.ALL_SIGNS
import com.eignex.klause.theory.qfnra.PolynomialSigns.POSITIVE_SIGN
import com.eignex.klause.theory.qfnra.PolynomialSigns.negateSigns
import com.eignex.klause.theory.qfnra.PolynomialSigns.relationSigns

internal class PolynomialPredicateSharing(
    private val originalNodes: List<FormulaNode>,
    private val originalAtoms: List<PolynomialAtom>,
    private val arithmetic: PolynomialArithmetic,
    private val definitions: Map<Int, PolynomialBooleanDefinition> = emptyMap(),
) {
    val nodes = ArrayList<FormulaNode>()
    val atoms = ArrayList<PolynomialAtom>()
    private val normalized = HashMap<Polynomial, ScaledPredicate>()
    private val representatives = HashMap<PredicateKey, PredicateRepresentative>()
    private val negations = HashMap<Int, Int>()
    private val constants = HashMap<Boolean, Int>()
    private val definitionPredicates = HashMap<PolynomialAtom, Int>()

    fun rewrite(root: Int): Int {
        val predicates = if (definitions.isEmpty()) null else IntArray(originalAtoms.size) { index ->
            arithmetic.step()
            val atom = originalAtoms[index]
            val nodeIndex = predicate(atom)
            if (atom !in definitionPredicates) definitionPredicates[atom] = nodeIndex
            nodeIndex
        }
        val indices = IntArray(originalNodes.size)
        for ((index, node) in originalNodes.withIndex()) {
            arithmetic.step()
            indices[index] = when (node) {
                is FormulaNode.Atom -> predicates?.get(node.atom) ?: predicate(originalAtoms[node.atom])
                is FormulaNode.Not -> negate(indices[node.child])
                is FormulaNode.Constant -> constant(node.value)
                is FormulaNode.BooleanVariable -> boolean(node)
                is FormulaNode.Junction -> {
                    val children = IntArray(node.children.size) {
                        arithmetic.step()
                        indices[node.children[it]]
                    }
                    if (definitions.isEmpty()) append(FormulaNode.Junction(children, node.conjunction))
                    else junction(children, node.conjunction)
                }
            }
        }
        arithmetic.step()
        return indices[root]
    }

    private fun boolean(node: FormulaNode.BooleanVariable): Int {
        arithmetic.step()
        val definition = definitions[node.variable] ?: return append(node)
        val index = definitionPredicates.getValue(definition.atom)
        return if (definition.sameTruth) index else negate(index)
    }

    private fun junction(children: IntArray, conjunction: Boolean): Int {
        val retained = LinkedHashSet<Int>()
        val signs = HashMap<Int, Boolean>()
        for (child in children) {
            arithmetic.step()
            val node = nodes[child]
            if (node is FormulaNode.Constant) {
                if (node.value != conjunction) return constant(node.value)
                continue
            }
            val base = if (node is FormulaNode.Not) node.child else child
            val positive = node !is FormulaNode.Not
            val previous = signs.put(base, positive)
            if (previous != null && previous != positive) return constant(!conjunction)
            retained.add(child)
        }
        arithmetic.step()
        return when (retained.size) {
            0 -> constant(conjunction)
            1 -> retained.single()
            else -> append(FormulaNode.Junction(retained.toIntArray(), conjunction))
        }
    }

    fun compact(root: Int): Int {
        val used = BooleanArray(nodes.size)
        val pending = ArrayDeque<Int>()
        pending.addLast(root)
        while (pending.isNotEmpty()) {
            arithmetic.step()
            val index = pending.removeLast()
            if (used[index]) continue
            used[index] = true
            when (val node = nodes[index]) {
                is FormulaNode.Not -> pending.addLast(node.child)
                is FormulaNode.Junction -> for (child in node.children) {
                    arithmetic.step()
                    pending.addLast(child)
                }
                else -> Unit
            }
        }
        val indices = IntArray(nodes.size)
        val retainedNodes = ArrayList<FormulaNode>()
        val retainedAtoms = ArrayList<PolynomialAtom>()
        for ((index, node) in nodes.withIndex()) {
            arithmetic.step()
            if (!used[index]) continue
            indices[index] = retainedNodes.size
            retainedNodes.add(when (node) {
                is FormulaNode.Atom -> {
                    retainedAtoms.add(atoms[node.atom])
                    FormulaNode.Atom(retainedAtoms.lastIndex)
                }
                is FormulaNode.Not -> FormulaNode.Not(indices[node.child])
                is FormulaNode.Junction -> FormulaNode.Junction(IntArray(node.children.size) {
                    arithmetic.step()
                    indices[node.children[it]]
                }, node.conjunction)
                else -> node
            })
        }
        arithmetic.step()
        nodes.clear()
        nodes.addAll(retainedNodes)
        atoms.clear()
        atoms.addAll(retainedAtoms)
        return indices[root]
    }

    fun booleanVariables(): List<Int> {
        val variables = LinkedHashSet<Int>()
        for (node in nodes) {
            arithmetic.step()
            if (node is FormulaNode.BooleanVariable) variables.add(node.variable)
        }
        return variables.sortedWith(Comparator { left, right -> arithmetic.step(); left.compareTo(right) })
    }

    private fun predicate(atom: PolynomialAtom): Int {
        arithmetic.step()
        val terms = atom.polynomial.terms
        if (terms.isEmpty() || terms.singleOrNull()?.monomial == Monomial.ONE) {
            arithmetic.step()
            atoms.add(atom)
            return append(FormulaNode.Atom(atoms.lastIndex))
        }
        val scaled = normalized.getOrPut(atom.polynomial) {
            val coefficient = terms.first().coefficient
            ScaledPredicate(arithmetic.scale(atom.polynomial, coefficient.reciprocal()), coefficient.signum() > 0)
        }
        val signs = relationSigns(atom.relation).let { if (scaled.sameSign) it else negateSigns(it) }
        val truth = signs and POSITIVE_SIGN == 0
        val key = PredicateKey(scaled.polynomial, if (truth) signs else ALL_SIGNS xor signs)
        val previous = representatives[key]
        if (previous != null) return if (previous.truth == truth) previous.node else negate(previous.node)
        arithmetic.step()
        atoms.add(atom)
        val index = append(FormulaNode.Atom(atoms.lastIndex))
        representatives[key] = PredicateRepresentative(index, truth)
        return index
    }

    private fun negate(index: Int): Int {
        arithmetic.step()
        return when (val node = nodes[index]) {
            is FormulaNode.Constant -> constant(!node.value)
            is FormulaNode.Not -> node.child
            else -> negations.getOrPut(index) { append(FormulaNode.Not(index)) }
        }
    }

    private fun constant(value: Boolean): Int = constants.getOrPut(value) { append(FormulaNode.Constant(value)) }

    private fun append(node: FormulaNode): Int {
        arithmetic.step()
        nodes.add(node)
        return nodes.lastIndex
    }
}

private data class ScaledPredicate(val polynomial: Polynomial, val sameSign: Boolean)
private data class PredicateKey(val polynomial: Polynomial, val signs: Int)
private data class PredicateRepresentative(val node: Int, val truth: Boolean)
