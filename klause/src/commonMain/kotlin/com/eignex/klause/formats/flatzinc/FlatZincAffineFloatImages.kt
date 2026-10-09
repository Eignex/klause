package com.eignex.klause.formats.flatzinc

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.lowering.reifyLinear
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.div
import com.eignex.klause.util.gcd
import com.eignex.klause.util.magnitudeBitLength
import com.eignex.klause.util.minus
import com.eignex.klause.util.times
import com.eignex.klause.util.toLong

internal data class AffineFloatImage(
    val terms: Map<Int, BigFraction>,
    val constant: BigFraction = BigFraction.ZERO,
) {
    fun scaled(scale: BigFraction): AffineFloatImage = AffineFloatImage(
        terms.mapValues { (_, coefficient) -> coefficient * scale }.filterValues { !it.isZero },
        constant * scale,
    )

    operator fun plus(other: AffineFloatImage): AffineFloatImage {
        val result = terms.toMutableMap()
        for ((variable, coefficient) in other.terms) {
            result[variable] = (result[variable] ?: BigFraction.ZERO) + coefficient
        }
        return AffineFloatImage(result.filterValues { !it.isZero }, constant + other.constant)
    }
}

internal fun FlatZincCompiler.collectAffineFloatImages() {
    for (variable in floatVars.values) {
        if (variable.lpOnly && variable.lo == variable.hi) {
            val constant = BigFraction.ofDouble(variable.lo) ?: continue
            affineFloatImages[variable.varId] = AffineFloatImage(emptyMap(), constant)
        }
    }
    for (c in model.constraints) {
        if (c.name != "int2float" || c.args.size != 2) continue
        locateConstraint(c)
        val target = resolveFloatVarOrConst(c.args[1]) as? FloatRef.Var ?: continue
        if (target.bk.lpOnly) {
            affineFloatImages[target.bk.varId] = AffineFloatImage(mapOf(resolveIntVar(c.args[0]) to BigFraction.ONE))
        }
    }
    var changed: Boolean
    do {
        changed = false
        for (c in model.constraints) {
            locateConstraint(c)
            val definition = affineDefinition(c) ?: continue
            val coefficients = definition.coefficients
            val variables = definition.variables
            if (coefficients.size != variables.size || variables.any { it is FloatRef.Var && !it.bk.lpOnly }) continue
            val unknown = variables.indices.filter {
                coefficients[it] != 0.0 && floatImage(variables[it]) == null
            }
            if (unknown.size != 1) continue
            val target = unknown.single()
            val divisor = BigFraction.ofDouble(coefficients[target]) ?: continue
            var image = AffineFloatImage(emptyMap(), BigFraction.ofDouble(definition.bound) ?: continue)
            var supported = true
            for (i in variables.indices) {
                if (i == target || coefficients[i] == 0.0) continue
                val coefficient = BigFraction.ofDouble(coefficients[i])
                val source = floatImage(variables[i])
                if (coefficient == null || source == null) {
                    supported = false
                    break
                }
                image += source.scaled(coefficient.negated())
            }
            if (supported) {
                affineFloatImages[(variables[target] as FloatRef.Var).bk.varId] = image.scaled(divisor.reciprocal())
                changed = true
            }
        }
        for (c in model.constraints) {
            if (c.name != "float_times" || c.args.size != 3) continue
            locateConstraint(c)
            if (collectIntegerFloatProduct(c)) changed = true
        }
    } while (changed)
}

private data class AffineDefinition(val coefficients: DoubleArray, val variables: List<FloatRef>, val bound: Double)

private fun FlatZincCompiler.affineDefinition(c: FznConstraint): AffineDefinition? = when {
    c.name == "float_lin_eq" && c.args.size == 3 -> AffineDefinition(
        evalFloatConstArray(c.args[0]),
        floatReferences(c.args[1]),
        evalFloatConst(c.args[2]),
    )
    c.name == "float_eq" && c.args.size == 2 -> AffineDefinition(
        doubleArrayOf(1.0, -1.0),
        c.args.map(::resolveFloatVarOrConst),
        0.0,
    )
    c.name == "float_times" && c.args.size == 3 -> {
        val a = resolveFloatVarOrConst(c.args[0])
        val b = resolveFloatVarOrConst(c.args[1])
        if ((a is FloatRef.Const) == (b is FloatRef.Const)) {
            null
        } else {
            val constant = (a as? FloatRef.Const)?.value ?: (b as FloatRef.Const).value
            val variable = if (a is FloatRef.Const) b else a
            AffineDefinition(doubleArrayOf(constant, -1.0), listOf(variable, resolveFloatVarOrConst(c.args[2])), 0.0)
        }
    }
    else -> null
}

internal fun FlatZincCompiler.floatImage(ref: FloatRef): AffineFloatImage? = when (ref) {
    is FloatRef.Var -> affineFloatImages[ref.bk.varId]
    is FloatRef.Const -> BigFraction.ofDouble(ref.value)?.let { AffineFloatImage(emptyMap(), it) }
}

internal fun FlatZincCompiler.postIntegerImagePredicate(
    coefficients: DoubleArray,
    variables: IntArray,
    op: LinearOp,
    bound: Double,
    strict: Boolean,
    reifier: Int?,
): Boolean {
    var image = AffineFloatImage(emptyMap())
    for (i in variables.indices) {
        if (coefficients[i] == 0.0) continue
        val source = affineFloatImages[variables[i]] ?: realLo[variables[i]].takeIf {
            it == realHi[variables[i]]
        }?.let { BigFraction.ofDouble(it) }?.let { AffineFloatImage(emptyMap(), it) } ?: return false
        val coefficient = BigFraction.ofDouble(coefficients[i]) ?: return false
        image += source.scaled(coefficient)
    }
    val rhs = BigFraction.ofDouble(bound) ?: return false
    postAffinePredicate(image, op, rhs, strict, reifier)
    return true
}

internal fun FlatZincCompiler.postAffinePredicate(
    image: AffineFloatImage,
    op: LinearOp,
    bound: BigFraction,
    strict: Boolean = false,
    reifier: Int? = null,
) {
    val rhs = bound - image.constant
    // Positive denominator clearing preserves exact comparisons on the integer lattice.
    var denominator = rhs.den
    for (coefficient in image.terms.values) {
        denominator = denominator / denominator.gcd(coefficient.den) * coefficient.den
    }
    val integral = image.terms.values.map { it.num * (denominator / it.den) }.toTypedArray()
    var integralBound: BigInt = rhs.num * (denominator / rhs.den)
    if (strict) integralBound -= BIG_ONE
    val ids = image.terms.keys.toIntArray()
    val narrow = integralBound.magnitudeBitLength() < Long.SIZE_BITS &&
        integral.all { it.magnitudeBitLength() < Long.SIZE_BITS }
    if (reifier == null && op != LinearOp.NE && ids.isNotEmpty()) {
        factors.add(
            if (narrow) {
                Linear(LongArray(integral.size) { integral[it].toLong() }, ids, op, integralBound.toLong())
            } else {
                Linear(ids, integral, op, integralBound)
            },
        )
    } else {
        val atom = if (narrow) {
            reifyLinear(LongArray(integral.size) { integral[it].toLong() }, ids, op, integralBound.toLong())
        } else {
            reifyLinear(integral, ids, op, integralBound)
        }
        if (reifier == null) {
            factors.add(Clause(intArrayOf(atom)))
        } else {
            factors.add(Clause(intArrayOf(Lit.negate(reifier), atom)))
            factors.add(Clause(intArrayOf(reifier, Lit.negate(atom))))
        }
    }
}
