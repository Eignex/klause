package com.eignex.klause.ir

internal enum class PolynomialRelation { EQ, NE, LT, LE, GT, GE }

internal sealed interface PolynomialRealFormula {
    data class Constant(val value: Boolean) : PolynomialRealFormula
    data class BooleanVariable(val id: SourceBoolId) : PolynomialRealFormula
    class Atom(
        val left: PolynomialExpression,
        val relation: PolynomialRelation,
        val right: PolynomialExpression,
    ) : PolynomialRealFormula {
        private val hash = (31 * left.hashCode() + relation.hashCode()) * 31 + right.hashCode()
        override fun hashCode(): Int = hash
        override fun equals(other: Any?): Boolean = other is Atom && polynomialFormulasEqual(this, other)
    }

    class Not(val operand: PolynomialRealFormula) : PolynomialRealFormula {
        private val hash = 31 * operand.hashCode() + 3
        override fun hashCode(): Int = hash
        override fun equals(other: Any?): Boolean = other is Not && polynomialFormulasEqual(this, other)
    }

    class And(operands: List<PolynomialRealFormula>) : PolynomialRealFormula {
        private val values = operands.toTypedArray()
        val operands: List<PolynomialRealFormula> get() = values.toList()
        override fun equals(other: Any?): Boolean = other is And && polynomialFormulasEqual(this, other)
        private val hash = 31 * values.contentHashCode() + 1
        override fun hashCode(): Int = hash
    }

    class Or(operands: List<PolynomialRealFormula>) : PolynomialRealFormula {
        private val values = operands.toTypedArray()
        val operands: List<PolynomialRealFormula> get() = values.toList()
        override fun equals(other: Any?): Boolean = other is Or && polynomialFormulasEqual(this, other)
        private val hash = 31 * values.contentHashCode() + 2
        override fun hashCode(): Int = hash
    }
}

internal fun PolynomialRealFormula.remap(remap: VarRemap): PolynomialRealFormula {
    val work = ArrayDeque<Pair<PolynomialRealFormula, Boolean>>()
    val values = ArrayDeque<PolynomialRealFormula>()
    val remapped = HashMap<PolynomialRealFormula, PolynomialRealFormula>()
    work.addLast(this to false)
    while (work.isNotEmpty()) {
        val (node, combine) = work.removeLast()
        val retained = remapped[node]
        if (!combine && retained != null) {
            values.addLast(retained)
            continue
        }
        when (node) {
            is PolynomialRealFormula.Constant -> values.addLast(node)
            is PolynomialRealFormula.BooleanVariable -> values.addLast(
                PolynomialRealFormula.BooleanVariable(
                    requireNotNull(remap.bool(node.id)) { "removed polynomial Boolean variable" },
                ),
            )
            is PolynomialRealFormula.Atom -> values.addLast(
                PolynomialRealFormula.Atom(node.left.remap(remap), node.relation, node.right.remap(remap)),
            )
            is PolynomialRealFormula.Not -> if (combine) {
                values.addLast(PolynomialRealFormula.Not(values.removeLast()))
            } else {
                work.addLast(node to true)
                work.addLast(node.operand to false)
            }
            is PolynomialRealFormula.And, is PolynomialRealFormula.Or -> {
                val operands = when (node) {
                    is PolynomialRealFormula.And -> node.operands
                    is PolynomialRealFormula.Or -> node.operands
                    else -> error("unreachable")
                }
                if (combine) {
                    val args = List(operands.size) { values.removeLast() }.reversed()
                    values.addLast(
                        if (node is PolynomialRealFormula.And) PolynomialRealFormula.And(args)
                        else PolynomialRealFormula.Or(args),
                    )
                } else {
                    work.addLast(node to true)
                    for (operand in operands.asReversed()) work.addLast(operand to false)
                }
            }
        }
        if (combine || node is PolynomialRealFormula.Constant ||
            node is PolynomialRealFormula.BooleanVariable || node is PolynomialRealFormula.Atom
        ) {
            remapped[node] = values.last()
        }
    }
    return values.single()
}

internal fun PolynomialRealFormula.requireVariableCounts(boolCount: Int, realCount: Int) {
    require(boolCount >= 0 && realCount >= 0) { "negative polynomial variable count" }
    val formulas = ArrayDeque<PolynomialRealFormula>()
    val expressions = ArrayDeque<PolynomialExpression>()
    val seenFormulas = HashSet<PolynomialRealFormula>()
    val seenExpressions = HashSet<PolynomialExpression>()
    formulas.addLast(this)
    while (formulas.isNotEmpty()) {
        val node = formulas.removeLast()
        if (!seenFormulas.add(node)) continue
        when (node) {
            is PolynomialRealFormula.Constant -> Unit
            is PolynomialRealFormula.BooleanVariable -> require(node.id.index < boolCount) { "polynomial Boolean id out of range" }
            is PolynomialRealFormula.Atom -> {
                expressions.addLast(node.left)
                expressions.addLast(node.right)
            }
            is PolynomialRealFormula.Not -> formulas.addLast(node.operand)
            is PolynomialRealFormula.And -> formulas.addAll(node.operands)
            is PolynomialRealFormula.Or -> formulas.addAll(node.operands)
        }
    }
    while (expressions.isNotEmpty()) {
        val node = expressions.removeLast()
        if (!seenExpressions.add(node)) continue
        when (node) {
            is PolynomialExpression.Constant -> Unit
            is PolynomialExpression.Variable -> require(node.id.index < realCount) { "polynomial real id out of range" }
            is PolynomialExpression.Sum -> expressions.addAll(node.operands)
            is PolynomialExpression.Product -> expressions.addAll(node.operands)
            is PolynomialExpression.Power -> expressions.addLast(node.base)
        }
    }
}

private fun polynomialFormulasEqual(left: PolynomialRealFormula, right: PolynomialRealFormula): Boolean {
    val work = ArrayDeque<Pair<PolynomialRealFormula, PolynomialRealFormula>>()
    val seen = HashSet<PolynomialNodePair<PolynomialRealFormula>>()
    work.addLast(left to right)
    while (work.isNotEmpty()) {
        val (first, second) = work.removeLast()
        if (first === second || !seen.add(PolynomialNodePair(first, second))) continue
        when (first) {
            is PolynomialRealFormula.Constant -> if (second !is PolynomialRealFormula.Constant || first.value != second.value) return false
            is PolynomialRealFormula.BooleanVariable -> if (second !is PolynomialRealFormula.BooleanVariable || first.id != second.id) return false
            is PolynomialRealFormula.Atom -> {
                if (second !is PolynomialRealFormula.Atom || first.relation != second.relation ||
                    !polynomialExpressionsEqual(first.left, second.left) || !polynomialExpressionsEqual(first.right, second.right)
                ) return false
            }
            is PolynomialRealFormula.Not -> {
                if (second !is PolynomialRealFormula.Not) return false
                work.addLast(first.operand to second.operand)
            }
            is PolynomialRealFormula.And, is PolynomialRealFormula.Or -> {
                val firstOperands: List<PolynomialRealFormula>
                val secondOperands: List<PolynomialRealFormula>
                when (first) {
                    is PolynomialRealFormula.And -> {
                        if (second !is PolynomialRealFormula.And) return false
                        firstOperands = first.operands
                        secondOperands = second.operands
                    }
                    is PolynomialRealFormula.Or -> {
                        if (second !is PolynomialRealFormula.Or) return false
                        firstOperands = first.operands
                        secondOperands = second.operands
                    }
                    else -> error("unreachable")
                }
                if (firstOperands.size != secondOperands.size) return false
                for (index in firstOperands.indices) work.addLast(firstOperands[index] to secondOperands[index])
            }
        }
    }
    return true
}
