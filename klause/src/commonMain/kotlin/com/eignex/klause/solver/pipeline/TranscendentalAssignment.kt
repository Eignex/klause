package com.eignex.klause.solver.pipeline

import com.eignex.klause.arithmetic.real.ExactRealExpression
import com.eignex.klause.arithmetic.real.ExactRealValue
import com.eignex.klause.solver.Sample

/** An original-source checked assignment with exact transcendental expression coordinates. */
class TranscendentalAssignment internal constructor(
    /** Owned compatibility sample, retaining symbolic real authority. */
    val sample: Sample,
) : OpenTheoryAssignment {
    override fun boolValue(id: Int): Boolean = sample.boolValue(id)
    override fun intValue(id: Int): String = sample.exactIntValue(id).toString()
    override fun realValue(id: Int): String = exactRealValue(id).expression.exactRealText()

    /** Immutable exact coordinate, including its algebraic leaves and function applications. */
    fun exactRealValue(id: Int): ExactRealValue = requireNotNull(sample.symbolicReals)[id]
}

/** Typed exact real expression metadata for a transcendental witness, including reconstructed values. */
fun OpenTheoryAssignment.symbolicRealValue(id: Int): ExactRealValue? = when (this) {
    is TranscendentalAssignment -> exactRealValue(id)
    is OpenTheoryAssignment.Rebuilt -> base.symbolicRealValue(id)
    else -> null
}

internal fun ExactRealExpression.exactRealText(): String {
    val pending = ArrayDeque<Pair<ExactRealExpression, Boolean>>()
    val text = HashMap<ExactRealExpression, String>()
    pending.addLast(this to false)
    while (pending.isNotEmpty()) {
        val (node, combine) = pending.removeLast()
        if (node in text) continue
        val children = when (node) {
            is ExactRealExpression.Function -> listOf(node.argument)
            is ExactRealExpression.Sum -> node.operands
            is ExactRealExpression.Product -> node.operands
            is ExactRealExpression.Power -> listOf(node.base)
            else -> emptyList()
        }
        if (!combine && children.isNotEmpty()) {
            pending.addLast(node to true)
            children.asReversed().forEach { pending.addLast(it to false) }
            continue
        }
        text[node] = when (node) {
            is ExactRealExpression.Algebraic -> node.value.rational?.toString()
                ?: "root(${node.value.rootIndex};[${node.value.definingCoefficients.joinToString(",")}])"
            ExactRealExpression.Pi -> "pi"
            is ExactRealExpression.Function -> "${node.function.name.lowercase()}(${text.getValue(node.argument)})"
            is ExactRealExpression.Sum -> "sum(${children.joinToString(",") { text.getValue(it) }})"
            is ExactRealExpression.Product -> "product(${children.joinToString(",") { text.getValue(it) }})"
            is ExactRealExpression.Power -> "power(${text.getValue(node.base)},${node.exponent})"
        }
    }
    return text.getValue(this)
}
