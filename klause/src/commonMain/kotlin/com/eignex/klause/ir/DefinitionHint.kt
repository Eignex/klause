package com.eignex.klause.ir

/**
 * Source orientation of an auxiliary definition. Constraints remain authoritative; a consumer may
 * decline a hint or retain its output as a searched coordinate. Represented relations must be implied
 * by the retained source factors; references retain an unverified orientation without asserting a
 * functional expression. Payload arrays are borrowed read-only
 * for the source model's lifetime. Integer and Boolean output IDs occupy their original namespaces.
 */
sealed interface DefinitionHint {
    /** Original output coordinate in the hint's value namespace. */
    val output: Int

    /** Renumber every source role, or decline when a required coordinate is removed. */
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    fun remap(mapping: VarRemap): DefinitionHint? {
        val target = if (this is Boolean) mapping.bool(output) else mapping.int(output)
        if (target < 0) return null
        return when (this) {
            is Abs -> Abs(target, input.remapped(mapping) ?: return null)
            is Extreme -> Extreme(target, inputs.remapped(mapping) ?: return null, maximum)
            is Product -> Product(
                target, left.remapped(mapping) ?: return null, right.remapped(mapping) ?: return null,
            )
            is Power -> Power(target, base.remapped(mapping) ?: return null, exponent)
            is Plus -> Plus(
                target, left.remapped(mapping) ?: return null, right.remapped(mapping) ?: return null,
            )
            is Linear -> Linear(target, coefficients, inputs.remapped(mapping) ?: return null, bound)
            is BooleanValue -> BooleanValue(target, literal.remappedLiteral(mapping) ?: return null)
            is Element -> {
                val sourceIndex = mapping.int(index)
                if (sourceIndex < 0) return null
                val mapped = variables?.let { mapping.ints(it) }
                if (mapped?.any { it < 0 } == true) return null
                Element(target, sourceIndex, mapped, values, offset)
            }
            is Comparison -> Comparison(
                target, coefficients, inputs.remapped(mapping) ?: return null, bound, relation, strict,
            )
            is Membership -> mapping.int(input).takeIf { it >= 0 }?.let { Membership(target, it, values) }
            is Fold -> {
                val literals = IntArray(definition.lits.size)
                for (k in literals.indices) {
                    literals[k] = definition.lits[k].remappedLiteral(mapping) ?: return null
                }
                Fold(BoolFoldDefinition(target, literals, definition.isAnd))
            }
            is IntegerReference -> IntegerReference(target)
            is BooleanReference -> BooleanReference(target, literal.remappedLiteral(mapping) ?: return null)
        }
    }

    /** Definition whose output belongs to the integer namespace. */
    sealed interface Integer : DefinitionHint

    /** Definition whose output belongs to the Boolean namespace. */
    sealed interface Boolean : DefinitionHint

    /** Integer output orientation whose defining relation remains in the source factors. */
    class IntegerReference(override val output: Int) : Integer

    /** Boolean output orientation whose defining relation remains in the source factors. */
    class BooleanReference(
        override val output: Int,
        /** [Lit]-encoded annotation target, retaining an alias's polarity. */
        val literal: Int = Lit.make(output, true),
    ) : Boolean

    /** An integer source variable or literal. */
    sealed interface Operand {
        /** Original integer coordinate. */
        class Variable(
            /** Integer variable ID. */
            val id: Int,
        ) : Operand

        /** Exact signed-word integer literal. */
        class Constant(
            /** Source value. */
            val value: Long,
        ) : Operand
    }

    /** Absolute-value relation oriented toward its result. */
    class Abs(
        override val output: Int,
        /** Source operand. */
        val input: Operand,
    ) : Integer

    /** Minimum or maximum relation oriented toward its result. */
    class Extreme(
        override val output: Int,
        /** Ordered source operands, including repeated roles. */
        val inputs: Array<Operand>,
        /** Whether the result is the maximum. */
        val maximum: kotlin.Boolean,
    ) : Integer

    /** Multiplication relation oriented toward its result. */
    class Product(
        override val output: Int,
        /** Left source operand. */
        val left: Operand,
        /** Right source operand. */
        val right: Operand,
    ) : Integer

    /** Fixed nonnegative integer power oriented toward its result. */
    class Power(
        override val output: Int,
        /** Source base. */
        val base: Operand,
        /** Source exponent. */
        val exponent: Int,
    ) : Integer

    /** Addition relation oriented toward its result. */
    class Plus(
        override val output: Int,
        /** Left source operand. */
        val left: Operand,
        /** Right source operand. */
        val right: Operand,
    ) : Integer

    /** Source equality `sum(coefficients * inputs) = bound`, including the output's original roles. */
    class Linear(
        override val output: Int,
        /** Exact source coefficients. */
        val coefficients: LongArray,
        /** Ordered source operands. */
        val inputs: Array<Operand>,
        /** Exact source right-hand side. */
        val bound: Long,
    ) : Integer

    /** Integer value of a Boolean source literal. */
    class BooleanValue(
        override val output: Int,
        /** [Lit]-encoded source literal, with its polarity retained. */
        val literal: Int,
    ) : Integer

    /** Source array selection oriented toward an integer result. */
    class Element(
        override val output: Int,
        /** Original integer index coordinate. */
        val index: Int,
        /** Ordered integer coordinates, or null for a constant array. */
        val variables: IntArray?,
        /** Ordered source literals, or null for a variable array. */
        val values: LongArray?,
        /** Source index of the first array position. */
        val offset: Int,
    ) : Integer

    /** Exact integer comparison oriented toward its Boolean indicator. */
    class Comparison(
        override val output: Int,
        /** Exact source coefficients. */
        val coefficients: LongArray,
        /** Ordered integer operands. */
        val inputs: Array<Operand>,
        /** Exact source right-hand side. */
        val bound: Long,
        /** Source comparison, with strictness retained separately. */
        val relation: LinearOp,
        /** Whether LE or GE denotes its strict counterpart. */
        val strict: kotlin.Boolean = false,
    ) : Boolean

    /** Constant-set membership oriented toward its Boolean indicator. */
    class Membership(
        override val output: Int,
        /** Original integer member coordinate. */
        val input: Int,
        /** Exact source set values. */
        val values: LongArray,
    ) : Boolean

    /** Boolean fold whose source literals retain polarity and multiplicity. */
    class Fold(
        /** Source definition. */
        val definition: BoolFoldDefinition,
    ) : Boolean {
        override val output: Int get() = definition.out
    }
}

private fun DefinitionHint.Operand.remapped(mapping: VarRemap): DefinitionHint.Operand? = when (this) {
    is DefinitionHint.Operand.Variable -> mapping.int(id).takeIf { it >= 0 }?.let { DefinitionHint.Operand.Variable(it) }
    is DefinitionHint.Operand.Constant -> this
}

private fun Array<DefinitionHint.Operand>.remapped(mapping: VarRemap): Array<DefinitionHint.Operand>? {
    val mapped = ArrayList<DefinitionHint.Operand>(size)
    for (operand in this) mapped.add(operand.remapped(mapping) ?: return null)
    return mapped.toTypedArray()
}

private fun Int.remappedLiteral(mapping: VarRemap): Int? =
    mapping.bool(Lit.variable(this)).takeIf { it >= 0 }?.let { Lit.make(it, Lit.isPositive(this)) }
