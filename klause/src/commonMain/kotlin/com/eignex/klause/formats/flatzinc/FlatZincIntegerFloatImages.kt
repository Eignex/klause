package com.eignex.klause.formats.flatzinc

import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.formats.flatzinc.FlatZincCompiler.IntegerFloatImage
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.fitsLong
import com.eignex.klause.util.times
import com.eignex.klause.util.toLong

internal data class IntegerFloatProduct(val factor: Product?, val image: AffineFloatImage)

internal fun FlatZincCompiler.collectIntegerFloatProduct(c: FznConstraint): Boolean {
    if (c in integerFloatProducts) return false
    val aRef = resolveFloatVarOrConst(c.args[0])
    val bRef = resolveFloatVarOrConst(c.args[1])
    if (aRef is FloatRef.Const && bRef is FloatRef.Const) return false
    val a = floatImage(aRef) ?: return false
    val b = floatImage(bRef) ?: return false
    val target = resolveFloatVarOrConst(c.args[2])
    if (target is FloatRef.Var && !target.bk.lpOnly) return false
    if (a.terms.isEmpty() || b.terms.isEmpty()) {
        val image = if (a.terms.isEmpty()) b.scaled(a.constant) else a.scaled(b.constant)
        integerFloatProducts[c] = IntegerFloatProduct(null, image)
        return recordProductImage(target, image)
    }
    if (!a.constant.isZero || !b.constant.isZero || a.terms.size != 1 || b.terms.size != 1) {
        return false
    }
    val aTerm = a.terms.entries.single()
    val bTerm = b.terms.entries.single()
    val aDomain = intDomains[aTerm.key]
    val bDomain = intDomains[bTerm.key]
    val corners = listOf(aDomain.min, aDomain.max).flatMap { left ->
        listOf(bDomain.min, bDomain.max).map { right -> bigIntOf(left) * bigIntOf(right) }
    }
    val lo = corners.minWith { left, right -> left.compareTo(right) }
    val hi = corners.maxWith { left, right -> left.compareTo(right) }
    if (!lo.fitsLong() || !hi.fitsLong()) return false
    val product = allocInt("__integer_image_product_${intDomains.size}", lo.toLong(), hi.toLong())
    val image = AffineFloatImage(mapOf(product to aTerm.value * bTerm.value))
    integerFloatProducts[c] = IntegerFloatProduct(Product(aTerm.key, bTerm.key, product), image)
    return recordProductImage(target, image)
}

private fun FlatZincCompiler.recordProductImage(target: FloatRef, image: AffineFloatImage): Boolean {
    if (target !is FloatRef.Var || target.bk.varId in affineFloatImages) return false
    affineFloatImages[target.bk.varId] = image
    return true
}

internal fun FlatZincCompiler.emitIntegerImageProduct(c: FznConstraint, target: FloatRef) {
    val product = integerFloatProducts[c] ?: return
    product.factor?.let(factors::add)
    val image = floatImage(target) ?: return
    postAffinePredicate(image + product.image.scaled(BigFraction.MINUS_ONE), LinearOp.EQ, BigFraction.ZERO)
}

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

internal fun FlatZincCompiler.floatReferences(e: FznExpr): List<FloatRef> = when (e) {
    is FznExpr.ArrayLit -> e.elements.map(::resolveFloatVarOrConst)
    is FznExpr.Ident -> (arrays[e.name] as? FlatZincArray.Vars)?.floatBucketings?.map { FloatRef.Var(it) }.orEmpty()
    else -> emptyList()
}

internal fun FlatZincCompiler.needsRoundedFloatBounds(variable: Int, image: IntegerFloatImage, limit: Int): Boolean {
    val declaredBounds = model.varDecls.any { declaration ->
        declaration.isVar && when (val type = declaration.type) {
            is FznType.FloatRange -> floatVars[declaration.name]?.varId == variable

            is FznType.Array -> {
                val variables = (arrays[declaration.name] as? FlatZincArray.Vars)?.floatBucketings
                type.element is FznType.FloatRange && variables?.any { it.varId == variable } == true
            }

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
