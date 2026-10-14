package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.Polynomial
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.simplex.exact.BigFraction

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
            return PolynomialFormula(nodes, indices.getValue(formula), atoms, variables)
        }
    }
}

private sealed interface FormulaNode {
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
