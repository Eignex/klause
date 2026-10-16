package com.eignex.klause.factor.global

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.FactorKind
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.MixedVars
import com.eignex.klause.ir.StructuralKey
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BigInt

/** Pairwise distinct exact affine values, optionally reified by one Boolean coordinate. */
class Distinct(
    operands: List<Operand>,
    /** Boolean coordinate equivalent to the whole relation, or [LinearRow.ALWAYS] for an assertion. */
    val activator: Int = LinearRow.ALWAYS,
) : Factor {
    /** Immutable exact affine operand over tagged integer, Boolean and real coordinates. */
    class Operand(
        references: IntArray,
        coefficients: List<BigFraction>,
        constant: BigFraction = BigFraction.ZERO,
    ) {
        private val terms: Map<Int, BigFraction>
        /** Exact additive constant after Boolean literal normalization. */
        val constant: BigFraction

        init {
            require(references.size == coefficients.size)
            val captured = HashMap<Int, BigFraction>()
            var offset = constant
            for (index in references.indices) {
                val reference = references[index]
                require(reference >= 0 && (Term.isInt(reference) || Term.isReal(reference) || Term.isBool(reference)))
                val coefficient = coefficients[index]
                val complemented = Term.isBool(reference) && !Lit.isPositive(Term.lit(reference))
                val normalized = if (complemented) Term.ofLit(Lit.make(Lit.variable(Term.lit(reference)), true))
                    else reference
                if (complemented) offset += coefficient
                captured[normalized] = (captured[normalized] ?: BigFraction.ZERO) +
                    if (complemented) coefficient.negated() else coefficient
            }
            terms = captured.entries.filter { !it.value.isZero }.sortedBy { it.key }.associate { it.key to it.value }
            this.constant = offset
        }

        /** Number of retained nonzero terms. */
        val size: Int get() = terms.size

        /** Tagged coordinates in canonical order, independent of caller buffers. */
        val references: IntArray get() = terms.keys.toIntArray()

        /** Coefficients corresponding to [references], independent of caller buffers. */
        val coefficients: List<BigFraction> get() = terms.values.toList()

        internal fun value(
            integer: (Int) -> BigInt,
            boolean: (Int) -> Boolean,
            real: (Int) -> BigFraction,
        ): BigFraction {
            var result = constant
            for ((reference, coefficient) in terms) {
                val coordinate = when {
                    Term.isInt(reference) -> BigFraction.of(integer(Term.intVar(reference)), BIG_ONE)
                    Term.isReal(reference) -> real(Term.realVar(reference))
                    else -> if (boolean(Lit.variable(Term.lit(reference)))) BigFraction.ONE else BigFraction.ZERO
                }
                result += coefficient * coordinate
            }
            return result
        }

        internal fun remap(mapping: VarRemap): Operand = Operand(
            terms.keys.map { reference ->
                val target = when {
                    Term.isInt(reference) -> Term.ofIntVar(mapping.int(Term.intVar(reference)))
                    Term.isReal(reference) -> Term.ofRealVar(mapping.real(Term.realVar(reference)))
                    else -> Term.ofLit(mapping.lit(Term.lit(reference)))
                }
                require(target >= 0) { "removed distinct operand coordinate" }
                target
            }.toIntArray(), coefficients, constant,
        )
    }

    private val captured = operands.toList()

    init { require(activator >= LinearRow.ALWAYS) }

    /** Immutable source operands in their original order. */
    val operands: List<Operand> get() = captured.toList()

    override val variables: MixedVars = run {
        val references = captured.flatMap { it.references.toList() }.distinct()
        MixedVars(
            boundInts = references.filter(Term::isInt).map(Term::intVar).distinct().toIntArray(),
            boolVars = (references.filter(Term::isBool).map { Lit.variable(Term.lit(it)) } +
                if (activator == LinearRow.ALWAYS) emptyList() else listOf(activator)).distinct().toIntArray(),
            reals = references.filter(Term::isReal).map(Term::realVar).distinct().toIntArray(),
        )
    }

    internal fun collisions(
        integer: (Int) -> BigInt,
        boolean: (Int) -> Boolean,
        real: (Int) -> BigFraction,
    ): Int {
        val values = HashSet<BigFraction>()
        var collisions = 0
        for (operand in captured) if (!values.add(operand.value(integer, boolean, real))) collisions++
        return collisions
    }

    internal fun isSatisfied(
        integer: (Int) -> BigInt,
        boolean: (Int) -> Boolean,
        real: (Int) -> BigFraction,
    ): Boolean = (collisions(integer, boolean, real) == 0) ==
        (activator == LinearRow.ALWAYS || boolean(activator))

    override fun remap(mapping: VarRemap): Distinct = Distinct(
        captured.map { it.remap(mapping) },
        if (activator == LinearRow.ALWAYS) activator else mapping.bool(activator).also {
            require(it >= 0) { "removed distinct activator" }
        },
    )

    override fun structuralKey(): StructuralKey = StructuralKey.of(FactorKind.DISTINCT) {
        int(activator)
        int(captured.size)
        for (operand in captured) {
            ints(operand.references)
            for (value in operand.coefficients + operand.constant) {
                ints(value.num.toString().map { it.code }.toIntArray())
                ints(value.den.toString().map { it.code }.toIntArray())
            }
        }
    }

    override fun remapStructuralHash(mapping: VarRemap): Int = remap(mapping).structuralKey().hashCode()
}
