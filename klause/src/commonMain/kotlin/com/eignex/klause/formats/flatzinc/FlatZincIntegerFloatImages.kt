package com.eignex.klause.formats.flatzinc

import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.formats.flatzinc.FlatZincCompiler.IntegerFloatImage
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.simplex.exact.BigFraction

internal fun FlatZincCompiler.collectIntegerFloatImages(onlyRealColumns: Boolean): Map<Int, IntegerFloatImage> {
    val images = HashMap<Int, IntegerFloatImage>()
    for (c in model.constraints) {
        if (c.name != "int2float" || c.args.size != 2) continue
        locateConstraint(c)
        val target = resolveFloatVarOrConst(c.args[1]) as? FloatRef.Var ?: continue
        if (onlyRealColumns && !target.bk.lpOnly) continue
        images[target.bk.varId] = IntegerFloatImage(resolveIntVar(c.args[0]), 1.0)
    }
    val direct = images.toMap()
    for (c in model.constraints) {
        if (c.name != "float_lin_eq" || c.args.size != 3) continue
        locateConstraint(c)
        if (evalFloatConst(c.args[2]) != 0.0) continue
        val coefficients = evalFloatConstArray(c.args[0])
        val variables = floatReferences(c.args[1])
        if (coefficients.size != 2 || variables.size != 2) continue
        for (i in 0..1) {
            if (coefficients[i] != 1.0 && coefficients[i] != -1.0) continue
            val target = variables[i] as? FloatRef.Var ?: continue
            val source = variables[1 - i] as? FloatRef.Var ?: continue
            if (onlyRealColumns && !target.bk.lpOnly) continue
            val image = direct[source.bk.varId] ?: continue
            val scale = -coefficients[1 - i] / coefficients[i]
            if (scale.isFinite() && target.bk.varId !in images) {
                images[target.bk.varId] = image.copy(scale = scale)
            }
        }
    }
    return images
}

private fun FlatZincCompiler.floatReferences(e: FznExpr): List<FloatRef> = when (e) {
    is FznExpr.ArrayLit -> e.elements.map(::resolveFloatVarOrConst)
    is FznExpr.Ident -> (arrays[e.name] as? FlatZincArray.Vars)?.floatBucketings?.map { FloatRef.Var(it) }.orEmpty()
    else -> emptyList()
}

internal fun FlatZincCompiler.needsRoundedFloatBounds(variable: Int, image: IntegerFloatImage, limit: Int): Boolean {
    val declaredBounds = model.varDecls.any { declaration ->
        declaration.isVar && when (val type = declaration.type) {
            is FznType.FloatRange -> floatVars[declaration.name]?.varId == variable

            is FznType.Array ->
                type.element is FznType.FloatRange &&
                (arrays[declaration.name] as? FlatZincArray.Vars)?.floatBucketings?.any { it.varId == variable } == true

            else -> false
        }
    }
    if (!declaredBounds) return false
    val bounds = floatVars.values.first { it.varId == variable }
    val values = intDomains[image.variable].spanOrNull(limit.toLong()) ?: return false
    val scale = requireNotNull(BigFraction.ofDouble(image.scale))
    for (i in 0 until values.size) {
        val value = values.valueAt(i)
        val rounded = image.scale * value.toDouble()
        if (rounded != bounds.lo && rounded != bounds.hi) continue
        val exact = scale * BigFraction.ofLong(value)
        if ((rounded == bounds.lo && exact < requireNotNull(BigFraction.ofDouble(bounds.lo))) ||
            (rounded == bounds.hi && exact > requireNotNull(BigFraction.ofDouble(bounds.hi)))
        ) {
            return true
        }
    }
    return false
}

internal fun FlatZincCompiler.emitIntegerFloatProduct(
    image: IntegerFloatImage,
    other: FloatRef.Var,
    result: FloatRef.Var,
) {
    if (image.scale == 0.0) {
        postExactFloatLinear(doubleArrayOf(1.0), intArrayOf(result.bk.varId), LinearOp.EQ, 0.0)
        return
    }
    val product = if (image.scale == 1.0) {
        result.bk.varId
    } else {
        allocFloat(
        "__integer_float_product_${realLo.size}",
        Double.NEGATIVE_INFINITY,
        Double.POSITIVE_INFINITY,
        lpOnly = true,
    )
    }
    factors.add(
        RealProduct(
            image.variable,
            other.bk.varId,
            product,
            other.bk.lo,
            other.bk.hi,
        ),
    )
    if (image.scale != 1.0) {
        // Keep the source scale on a separate row instead of rounding scale * integer.
        postExactFloatLinear(
            doubleArrayOf(image.scale, -1.0),
            intArrayOf(product, result.bk.varId),
            LinearOp.EQ,
            0.0,
        )
    }
}
