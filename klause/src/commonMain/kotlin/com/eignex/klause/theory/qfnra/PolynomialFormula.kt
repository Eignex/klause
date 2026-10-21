package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.Monomial
import com.eignex.klause.arithmetic.polynomial.Polynomial
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.ir.PolynomialDistinct
import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.ir.PolynomialSelection
import com.eignex.klause.lowering.polynomialDistinctEncoding
import com.eignex.klause.lowering.polynomialSelectionEncoding
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.theory.qfnra.PolynomialSigns.ALL_SIGNS
import com.eignex.klause.theory.qfnra.PolynomialSigns.POSITIVE_SIGN
import com.eignex.klause.theory.qfnra.PolynomialSigns.negateSigns
import com.eignex.klause.theory.qfnra.PolynomialSigns.relationSigns

internal data class PolynomialAtom(val polynomial: Polynomial, val relation: PolynomialRelation) {
    fun holds(sign: Int): Boolean = when (relation) {
        PolynomialRelation.EQ -> sign == 0
        PolynomialRelation.NE -> sign != 0
        PolynomialRelation.LT -> sign < 0
        PolynomialRelation.LE -> sign <= 0
        PolynomialRelation.GT -> sign > 0
        PolynomialRelation.GE -> sign >= 0
    }
}

internal class PolynomialFormula private constructor(
    private val nodes: List<FormulaNode>,
    private val root: Int,
    val atoms: List<PolynomialAtom>,
    val booleanVariables: List<Int>,
) {
    fun booleanRegions(arithmetic: PolynomialArithmetic): Iterator<PolynomialBooleanRegion?> =
        PolynomialBooleanRegions(nodes, root, atoms, arithmetic).prepare()

    fun withAtoms(replacements: List<PolynomialAtom>, arithmetic: PolynomialArithmetic): PolynomialFormula {
        require(replacements.size == atoms.size)
        return PolynomialFormula(nodes, root, replacements.toList(), booleanVariables).sharePredicates(arithmetic)
    }

    private fun sharePredicates(arithmetic: PolynomialArithmetic): PolynomialFormula {
        val sharing = PolynomialPredicateSharing(nodes, atoms, arithmetic)
        val sharedRoot = sharing.rewrite(root)
        return PolynomialFormula(sharing.nodes, sharedRoot, sharing.atoms, booleanVariables)
    }

    fun forcedAtoms(arithmetic: PolynomialArithmetic): List<PolynomialAtom>? {
        val state = PolynomialBooleanPropagation(nodes, root, arithmetic, skipConfirmedParents = true)
        if (!state.initialize()) return null
        for ((index, node) in nodes.withIndex()) {
            arithmetic.step()
            if (node !is FormulaNode.Atom) continue
            val atom = atoms[node.atom]
            if (atom.polynomial.mainVariable != null) continue
            val sign = atom.polynomial.terms.firstOrNull()?.coefficient?.signum() ?: 0
            if (!state.assign(index, if (atom.holds(sign)) TRUE else FALSE)) return null
        }
        if (!state.propagate()) return null
        val result = ArrayList<PolynomialAtom>()
        for ((index, node) in nodes.withIndex()) {
            arithmetic.step()
            if (node !is FormulaNode.Atom) continue
            val value = state.value(index)
            if (value == UNASSIGNED) continue
            val atom = atoms[node.atom]
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

    fun refuteBooleans(
        arithmetic: PolynomialArithmetic,
        maximumAtoms: Int,
        maximumTerms: Int,
        maximumBits: Int,
    ): Boolean {
        if (booleanVariables.isEmpty()) return false
        return refutePolynomialBooleans(nodes, root, atoms, arithmetic, maximumAtoms, maximumTerms, maximumBits)
    }

    fun conjunctiveAtoms(
        arithmetic: PolynomialArithmetic,
        maximumAtoms: Int,
        atomIndices: MutableList<Int>? = null,
    ): List<PolynomialAtom>? {
        val result = ArrayList<PolynomialAtom>()
        val indices = if (atomIndices != null) ArrayList<Int>() else null
        val pending = ArrayDeque<Int>()
        val visited = HashSet<Int>()
        pending.addLast(root)
        while (pending.isNotEmpty()) {
            arithmetic.step()
            val index = pending.removeLast()
            if (!visited.add(index)) continue
            when (val node = nodes[index]) {
                is FormulaNode.Constant -> if (!node.value) return null
                is FormulaNode.Atom -> {
                    result.add(atoms[node.atom])
                    if (indices != null) {
                        arithmetic.step()
                        indices.add(node.atom)
                    }
                }
                is FormulaNode.Not -> {
                    val child = nodes[node.child] as? FormulaNode.Atom ?: return null
                    val atom = atoms[child.atom]
                    if (indices != null) {
                        arithmetic.step()
                        indices.add(child.atom)
                    }
                    result.add(PolynomialAtom(atom.polynomial, when (atom.relation) {
                        PolynomialRelation.EQ -> PolynomialRelation.NE
                        PolynomialRelation.NE -> PolynomialRelation.EQ
                        PolynomialRelation.LT -> PolynomialRelation.GE
                        PolynomialRelation.LE -> PolynomialRelation.GT
                        PolynomialRelation.GT -> PolynomialRelation.LE
                        PolynomialRelation.GE -> PolynomialRelation.LT
                    }))
                }
                is FormulaNode.Junction -> {
                    if (!node.conjunction) return null
                    for (child in node.children.indices.reversed()) {
                        arithmetic.step()
                        pending.addLast(node.children[child])
                    }
                }
                is FormulaNode.BooleanVariable -> return null
            }
            if (result.size > maximumAtoms) return null
        }
        if (indices != null) {
            repeat(indices.size) { arithmetic.step() }
            atomIndices?.addAll(indices)
        }
        return result
    }

    fun witnessAtoms(arithmetic: PolynomialArithmetic, maximumAtoms: Int): List<PolynomialAtom>? {
        val result = ArrayList<PolynomialAtom>()
        val pending = ArrayDeque<Int>()
        val visited = HashSet<Int>()
        pending.addLast(root)
        while (pending.isNotEmpty()) {
            arithmetic.step()
            val index = pending.removeLast()
            if (!visited.add(index)) continue
            when (val node = nodes[index]) {
                is FormulaNode.Constant -> if (!node.value) return null
                is FormulaNode.Atom -> result.add(atoms[node.atom])
                is FormulaNode.Not -> {
                    val child = nodes[node.child] as? FormulaNode.Atom ?: continue
                    val atom = atoms[child.atom]
                    result.add(PolynomialAtom(atom.polynomial, when (atom.relation) {
                        PolynomialRelation.EQ -> PolynomialRelation.NE
                        PolynomialRelation.NE -> PolynomialRelation.EQ
                        PolynomialRelation.LT -> PolynomialRelation.GE
                        PolynomialRelation.LE -> PolynomialRelation.GT
                        PolynomialRelation.GT -> PolynomialRelation.LE
                        PolynomialRelation.GE -> PolynomialRelation.LT
                    }))
                }
                is FormulaNode.Junction -> if (node.conjunction) {
                    for (child in node.children.indices.reversed()) {
                        arithmetic.step()
                        pending.addLast(node.children[child])
                    }
                }
                is FormulaNode.BooleanVariable -> Unit
            }
            if (result.size > maximumAtoms) return null
        }
        return result
    }

    fun truth(bools: IntArray, atomValues: IntArray, arithmetic: PolynomialArithmetic): Int {
        val values = IntArray(nodes.size)
        for ((index, node) in nodes.withIndex()) {
            arithmetic.step()
            values[index] = when (node) {
                is FormulaNode.Constant -> if (node.value) TRUE else FALSE
                is FormulaNode.BooleanVariable -> bools[node.variable]
                is FormulaNode.Atom -> atomValues[node.atom]
                is FormulaNode.Not -> when (values[node.child]) {
                    FALSE -> TRUE
                    TRUE -> FALSE
                    else -> UNASSIGNED
                }
                is FormulaNode.Junction -> {
                    val decisive = if (node.conjunction) FALSE else TRUE
                    var result = if (node.conjunction) TRUE else FALSE
                    for (child in node.children) {
                        arithmetic.step()
                        val value = values[child]
                        if (value == decisive) {
                            result = decisive
                            break
                        }
                        if (value == UNASSIGNED) result = UNASSIGNED
                    }
                    result
                }
            }
        }
        return values[root]
    }

    companion object {
        const val UNASSIGNED: Int = -1
        const val FALSE: Int = 0
        const val TRUE: Int = 1

        fun conjunction(atoms: List<PolynomialAtom>, arithmetic: PolynomialArithmetic): PolynomialFormula {
            val nodes = ArrayList<FormulaNode>()
            val children = IntArray(atoms.size) { index ->
                arithmetic.step()
                nodes.add(FormulaNode.Atom(index))
                index
            }
            arithmetic.step()
            nodes.add(FormulaNode.Junction(children, true))
            return PolynomialFormula(nodes, nodes.lastIndex, atoms.toList(), emptyList()).sharePredicates(arithmetic)
        }

        fun normalize(
            formula: PolynomialRealFormula,
            arithmetic: PolynomialArithmetic,
            fixed: Map<Int, BigFraction> = emptyMap(),
        ): PolynomialFormula {
            val expressions = PolynomialNormalizer(arithmetic, fixed)
            val indices = HashMap<PolynomialRealFormula, Int>()
            val nodes = mutableListOf<FormulaNode>()
            val atoms = mutableListOf<PolynomialAtom>()
            val booleans = LinkedHashSet<Int>()
            val expansions = HashMap<PolynomialRealFormula, PolynomialRealFormula>()
            val pending = ArrayDeque<Pair<PolynomialRealFormula, Boolean>>()
            pending.addLast(formula to false)
            while (pending.isNotEmpty()) {
                arithmetic.step()
                val (node, combine) = pending.removeLast()
                if (node in indices) continue
                val children = when (node) {
                    is PolynomialRealFormula.Not -> listOf(node.operand)
                    is PolynomialRealFormula.And -> node.operands
                    is PolynomialRealFormula.Or -> node.operands
                    is PolynomialDistinct -> listOf(expansions.getOrPut(node) {
                        polynomialDistinctEncoding(node, arithmetic::step)
                    })
                    is PolynomialSelection -> listOf(expansions.getOrPut(node) {
                        polynomialSelectionEncoding(node, arithmetic::step)
                    })
                    else -> emptyList()
                }
                if (!combine && children.isNotEmpty()) {
                    pending.addLast(node to true)
                    for (child in children.asReversed()) pending.addLast(child to false)
                    continue
                }
                val normalized = when (node) {
                    is PolynomialRealFormula.Constant -> FormulaNode.Constant(node.value)
                    is PolynomialRealFormula.BooleanVariable -> {
                        booleans.add(node.id.index)
                        FormulaNode.BooleanVariable(node.id.index)
                    }
                    is PolynomialRealFormula.Atom -> {
                        val polynomial = arithmetic.subtract(
                            expressions.normalize(node.left), expressions.normalize(node.right),
                        )
                        atoms.add(PolynomialAtom(polynomial, node.relation))
                        FormulaNode.Atom(atoms.lastIndex)
                    }
                    is PolynomialRealFormula.Not -> FormulaNode.Not(indices.getValue(node.operand))
                    is PolynomialDistinct, is PolynomialSelection -> FormulaNode.Junction(
                        intArrayOf(indices.getValue(children.single())), true,
                    )
                    is PolynomialRealFormula.And -> FormulaNode.Junction(
                        children.map {
                            arithmetic.step()
                            indices.getValue(it)
                        }.toIntArray(), true,
                    )
                    is PolynomialRealFormula.Or -> FormulaNode.Junction(
                        children.map {
                            arithmetic.step()
                            indices.getValue(it)
                        }.toIntArray(), false,
                    )
                }
                indices[node] = nodes.size
                nodes.add(normalized)
            }
            val variables = booleans.sortedWith(Comparator { left, right ->
                arithmetic.step()
                left.compareTo(right)
            })
            return PolynomialFormula(nodes, indices.getValue(formula), atoms, variables).sharePredicates(arithmetic)
        }
    }
}

private class PolynomialPredicateSharing(
    private val originalNodes: List<FormulaNode>,
    private val originalAtoms: List<PolynomialAtom>,
    private val arithmetic: PolynomialArithmetic,
) {
    val nodes = ArrayList<FormulaNode>()
    val atoms = ArrayList<PolynomialAtom>()
    private val normalized = HashMap<Polynomial, ScaledPredicate>()
    private val representatives = HashMap<PredicateKey, PredicateRepresentative>()
    private val negations = HashMap<Int, Int>()
    private val constants = HashMap<Boolean, Int>()

    fun rewrite(root: Int): Int {
        val indices = IntArray(originalNodes.size)
        for ((index, node) in originalNodes.withIndex()) {
            arithmetic.step()
            indices[index] = when (node) {
                is FormulaNode.Atom -> predicate(originalAtoms[node.atom])
                is FormulaNode.Not -> negate(indices[node.child])
                is FormulaNode.Constant -> constant(node.value)
                is FormulaNode.BooleanVariable -> append(node)
                is FormulaNode.Junction -> append(FormulaNode.Junction(IntArray(node.children.size) {
                    arithmetic.step()
                    indices[node.children[it]]
                }, node.conjunction))
            }
        }
        arithmetic.step()
        return indices[root]
    }

    private fun predicate(atom: PolynomialAtom): Int {
        arithmetic.step()
        val terms = atom.polynomial.terms
        if (terms.isEmpty() || terms.singleOrNull()?.monomial == Monomial.ONE) {
            return constant(atom.holds(terms.firstOrNull()?.coefficient?.signum() ?: 0))
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

internal sealed interface FormulaNode {
    data class Constant(val value: Boolean) : FormulaNode
    data class BooleanVariable(val variable: Int) : FormulaNode
    data class Atom(val atom: Int) : FormulaNode
    data class Not(val child: Int) : FormulaNode
    class Junction(val children: IntArray, val conjunction: Boolean) : FormulaNode
}

private class PolynomialNormalizer(
    private val arithmetic: PolynomialArithmetic,
    private val fixed: Map<Int, BigFraction>,
) {
    private val normalized = HashMap<PolynomialExpression, Polynomial>()

    fun normalize(expression: PolynomialExpression): Polynomial {
        val pending = ArrayDeque<Pair<PolynomialExpression, Boolean>>()
        pending.addLast(expression to false)
        while (pending.isNotEmpty()) {
            arithmetic.step()
            val (node, combine) = pending.removeLast()
            if (node in normalized) continue
            if (node is PolynomialExpression.Power && node.exponent == 0) {
                normalized[node] = Polynomial.ONE
                continue
            }
            val children = when (node) {
                is PolynomialExpression.Sum -> node.operands
                is PolynomialExpression.Product -> node.operands
                is PolynomialExpression.Power -> listOf(node.base)
                else -> emptyList()
            }
            if (!combine && children.isNotEmpty()) {
                pending.addLast(node to true)
                for (child in children.asReversed()) pending.addLast(child to false)
                continue
            }
            normalized[node] = when (node) {
                is PolynomialExpression.Constant -> Polynomial.constant(
                    BigFraction.of(node.numerator, node.denominator),
                )
                is PolynomialExpression.Variable -> fixed[node.id.index]?.let(Polynomial::constant)
                    ?: Polynomial.variable(node.id.index)
                is PolynomialExpression.Power -> arithmetic.power(normalized.getValue(node.base), node.exponent)
                is PolynomialExpression.Sum -> {
                    var value = Polynomial.ZERO
                    for (child in children) value = arithmetic.add(value, normalized.getValue(child))
                    value
                }
                is PolynomialExpression.Product -> {
                    var value = Polynomial.ONE
                    for (child in children) value = arithmetic.multiply(value, normalized.getValue(child))
                    value
                }
            }
        }
        return normalized.getValue(expression)
    }
}
