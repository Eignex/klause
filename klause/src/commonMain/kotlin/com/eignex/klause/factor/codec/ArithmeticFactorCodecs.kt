package com.eignex.klause.factor.codec

import com.eignex.klause.factor.arithmetic.Abs
import com.eignex.klause.factor.arithmetic.ExactRealEquality
import com.eignex.klause.arithmetic.real.ExactRealValue
import com.eignex.klause.factor.arithmetic.AbsAffineValue
import com.eignex.klause.factor.arithmetic.AffineAbs
import com.eignex.klause.factor.arithmetic.ArrayMinMax
import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.factor.arithmetic.DivMod
import com.eignex.klause.factor.arithmetic.DivModExpression
import com.eignex.klause.factor.arithmetic.DivisionConvention
import com.eignex.klause.factor.arithmetic.GridAbs
import com.eignex.klause.factor.arithmetic.GridArrayMinMax
import com.eignex.klause.factor.arithmetic.GridCoordinate
import com.eignex.klause.factor.arithmetic.GridPower
import com.eignex.klause.factor.arithmetic.GridProduct
import com.eignex.klause.factor.arithmetic.Power
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.factor.arithmetic.RealAbs
import com.eignex.klause.factor.arithmetic.RealArrayMinMax
import com.eignex.klause.factor.arithmetic.RealFiniteDomain
import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.factor.arithmetic.WideDivMod
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.util.BigInt
import kotlinx.serialization.Serializable

// Integer operands are integer column ids and real operands real column ids unless a field says otherwise.

@Serializable
internal class ProductPayload(val a: Int, val b: Int, val result: Int)

/** `result = base^exponent`; [real] places both coordinates in the real namespace. */
@Serializable
internal class PowerPayload(val base: Int, val exponent: Int, val result: Int, val real: Boolean = false)

/** `result = int · real` with the real operand's declared bounds; an absent side is infinite. */
@Serializable
internal class RealProductPayload(
    val intOperand: Int,
    val realOperand: Int,
    val result: Int,
    val realLower: Double? = null,
    val realUpper: Double? = null,
)

/** `result = a · b` over reals; [choices] retain the declared finite values of [choiceOperand]. */
@Serializable
internal class ContinuousProductPayload(
    val a: Int,
    val b: Int,
    val result: Int,
    val choiceOperand: Int? = null,
    val choices: DoubleArray? = null,
    val guards: IntArray? = null,
)

@Serializable
internal class AbsPayload(val operand: Int, val result: Int)

/** Exact affine value `Σ coefficients·vars + constant` over integer columns. */
@Serializable
internal class AffinePayload(
    val vars: IntArray,
    val coefficients: List<@Serializable(with = BigIntSerializer::class) BigInt>,
    @Serializable(with = BigIntSerializer::class) val constant: BigInt,
)

@Serializable
internal class AffineAbsPayload(val operand: AffinePayload, val result: AffinePayload)

@Serializable
internal class GridAbsPayload(
    val operand: Int,
    val result: Int,
    val operandLower: Double,
    val operandUpper: Double,
    val operandBuckets: Int,
    val resultLower: Double,
    val resultUpper: Double,
    val resultBuckets: Int,
)

@Serializable
internal class ArrayMinMaxPayload(val result: Int, val xs: IntArray, val max: Boolean)

@Serializable
internal class GridArrayMinMaxPayload(
    val coordinates: IntArray,
    val steps: DoubleArray,
    val origins: DoubleArray,
    val constants: BooleanArray,
    val scale: Long,
    val max: Boolean,
)

@Serializable
internal class GridCoordinatePayload(val variable: Int, val lower: Double, val upper: Double, val buckets: Int)

@Serializable
internal class GridProductPayload(
    val a: GridCoordinatePayload,
    val b: GridCoordinatePayload,
    val result: GridCoordinatePayload,
)

@Serializable
internal class GridPowerPayload(val base: GridCoordinatePayload, val exponent: Int, val result: GridCoordinatePayload)

/** Exactly one of [divisor] (a column) and [constantDivisor] (an exact literal) is present. */
@Serializable
internal class DivModPayload(
    val dividend: Int,
    val quotient: Int,
    val remainder: Int,
    val divisor: Int? = null,
    @Serializable(with = BigIntSerializer::class) val constantDivisor: BigInt? = null,
    val convention: DivisionConvention = DivisionConvention.TRUNCATING,
)

/** Euclidean division of exact affine values by an exact literal. */
@Serializable
internal class WideDivModPayload(
    val dividend: AffinePayload,
    @Serializable(with = BigIntSerializer::class) val divisor: BigInt,
    val quotient: AffinePayload,
    val remainder: AffinePayload,
)

@Serializable
internal class RealFiniteDomainPayload(val variable: Int, val values: DoubleArray)

private fun affinePayload(coefficients: Map<Int, BigInt>, constant: BigInt): AffinePayload =
    AffinePayload(coefficients.keys.toIntArray(), coefficients.values.toList(), constant)

private fun AffinePayload.terms(): Map<Int, BigInt> {
    require(vars.size == coefficients.size) { "vars and coefficients must align" }
    require(vars.distinct().size == vars.size) { "affine vars must be distinct" }
    return vars.indices.associate { vars[it] to coefficients[it] }
}

private fun GridCoordinate.payload(): GridCoordinatePayload = GridCoordinatePayload(variable, lower, upper, buckets)

private fun GridCoordinatePayload.toCoordinate(): GridCoordinate = GridCoordinate(variable, lower, upper, buckets)

private fun Power.payload(): PowerPayload = when (val coordinate = base) {
    is Power.Coordinate.Integer -> PowerPayload(
        coordinate.id.index, exponent, (result as Power.Coordinate.Integer).id.index,
    )
    is Power.Coordinate.Real -> PowerPayload(
        coordinate.id.index, exponent, (result as Power.Coordinate.Real).id.index, real = true,
    )
}

private fun PowerPayload.toPower(): Power = if (real) {
    Power(Power.Coordinate.Real(SourceRealId(base)), exponent, Power.Coordinate.Real(SourceRealId(result)))
} else {
    Power(Power.Coordinate.Integer(SourceIntId(base)), exponent, Power.Coordinate.Integer(SourceIntId(result)))
}

private fun ContinuousProduct.payload(): ContinuousProductPayload {
    val choices = finiteChoices ?: return ContinuousProductPayload(a, b, result)
    return ContinuousProductPayload(
        a,
        b,
        result,
        choices.operand,
        DoubleArray(choices.size) { choices.valueAt(it) },
        if (choices.hasGuards) IntArray(choices.size) { choices.literalAt(it) } else null,
    )
}

private fun ContinuousProductPayload.toProduct(): ContinuousProduct {
    if (choiceOperand == null) {
        require(choices == null && guards == null) { "'choices' and 'guards' need a 'choiceOperand'" }
        return ContinuousProduct(a, b, result)
    }
    return ContinuousProduct(a, b, result, choiceOperand, requireNotNull(choices) { "missing 'choices'" }, guards)
}

private fun DivModPayload.toDivMod(): DivMod {
    require((divisor == null) != (constantDivisor == null)) { "state exactly one of 'divisor' and 'constantDivisor'" }
    return if (divisor != null) {
        DivMod(dividend, divisor, quotient, remainder, convention)
    } else {
        DivMod(dividend, requireNotNull(constantDivisor), quotient, remainder, convention)
    }
}

@Serializable
internal class ExactRealEqualityPayload(
    val real: Int,
    @Serializable(with = ExactRealValueSerializer::class) val value: ExactRealValue,
)

internal fun arithmeticFactorCodecs(): List<FactorCodec<*, *>> = listOf(
    factorCodec<ExactRealEquality, ExactRealEqualityPayload>(
        "exactRealEquality",
        { ExactRealEqualityPayload(it.id.index, it.value) },
        { ExactRealEquality(SourceRealId(it.real), it.value) },
    ),
    factorCodec<Product, ProductPayload>(
        "product",
        { ProductPayload(it.a, it.b, it.result) },
        { Product(it.a, it.b, it.result) },
    ),
    factorCodec<Power, PowerPayload>("power", { it.payload() }, { it.toPower() }),
    factorCodec<RealProduct, RealProductPayload>(
        "realProduct",
        {
            RealProductPayload(
                it.intOperand, it.realOperand, it.result,
                it.realOperandLo.finiteOrNull(-1), it.realOperandHi.finiteOrNull(1),
            )
        },
        {
            RealProduct(
                it.intOperand, it.realOperand, it.result, it.realLower.orInfinity(-1), it.realUpper.orInfinity(1),
            )
        },
    ),
    factorCodec<ContinuousProduct, ContinuousProductPayload>(
        "continuousProduct",
        { it.payload() },
        { it.toProduct() },
    ),
    factorCodec<Abs, AbsPayload>("abs", { AbsPayload(it.operand, it.result) }, { Abs(it.operand, it.result) }),
    factorCodec<RealAbs, RealAbsPayload>(
        "realAbs",
        { RealAbsPayload(it.operand, it.result) },
        { RealAbs(it.operand, it.result) },
    ),
    factorCodec<AffineAbs, AffineAbsPayload>(
        "affineAbs",
        {
            AffineAbsPayload(
                affinePayload(it.operand.coefficients, it.operand.constant),
                affinePayload(it.result.coefficients, it.result.constant),
            )
        },
        {
            AffineAbs(
                AbsAffineValue(it.operand.terms(), it.operand.constant),
                AbsAffineValue(it.result.terms(), it.result.constant),
            )
        },
    ),
    factorCodec<GridAbs, GridAbsPayload>(
        "gridAbs",
        {
            GridAbsPayload(
                it.operand, it.result, it.operandLower, it.operandUpper, it.operandBuckets,
                it.resultLower, it.resultUpper, it.resultBuckets,
            )
        },
        {
            GridAbs(
                it.operand, it.result, it.operandLower, it.operandUpper, it.operandBuckets,
                it.resultLower, it.resultUpper, it.resultBuckets,
            )
        },
    ),
    factorCodec<ArrayMinMax, ArrayMinMaxPayload>(
        "arrayMinMax",
        { ArrayMinMaxPayload(it.result, it.xs, it.max) },
        { ArrayMinMax(it.result, it.xs, it.max) },
    ),
    factorCodec<RealArrayMinMax, RealArrayMinMaxPayload>(
        "realArrayMinMax",
        { RealArrayMinMaxPayload(it.result, it.xs, it.max) },
        { RealArrayMinMax(it.result, it.xs, it.max) },
    ),
    factorCodec<GridArrayMinMax, GridArrayMinMaxPayload>(
        "gridArrayMinMax",
        { GridArrayMinMaxPayload(it.coordinates, it.steps, it.origins, it.constants, it.scale, it.max) },
        { GridArrayMinMax(it.coordinates, it.steps, it.origins, it.constants, it.scale, it.max) },
    ),
    factorCodec<GridProduct, GridProductPayload>(
        "gridProduct",
        { GridProductPayload(it.a.payload(), it.b.payload(), it.output.payload()) },
        { GridProduct(it.a.toCoordinate(), it.b.toCoordinate(), it.result.toCoordinate()) },
    ),
    factorCodec<GridPower, GridPowerPayload>(
        "gridPower",
        { GridPowerPayload(it.base.payload(), it.exponent, it.output.payload()) },
        { GridPower(it.base.toCoordinate(), it.exponent, it.result.toCoordinate()) },
    ),
    factorCodec<DivMod, DivModPayload>(
        "divMod",
        { DivModPayload(it.dividend, it.quotient, it.remainder, it.divisor, it.constantDivisor, it.convention) },
        { it.toDivMod() },
    ),
    factorCodec<WideDivMod, WideDivModPayload>(
        "wideDivMod",
        {
            WideDivModPayload(
                affinePayload(it.dividend.coefficients, it.dividend.constant),
                it.divisor,
                affinePayload(it.quotient.coefficients, it.quotient.constant),
                affinePayload(it.remainder.coefficients, it.remainder.constant),
            )
        },
        {
            WideDivMod(
                DivModExpression(it.dividend.terms(), it.dividend.constant),
                it.divisor,
                DivModExpression(it.quotient.terms(), it.quotient.constant),
                DivModExpression(it.remainder.terms(), it.remainder.constant),
            )
        },
    ),
    factorCodec<RealFiniteDomain, RealFiniteDomainPayload>(
        "realFiniteDomain",
        { RealFiniteDomainPayload(it.variable, it.values) },
        { RealFiniteDomain(it.variable, it.values) },
    ),
)

// Distinct payload classes per codec: the registry maps a payload class back to exactly one codec.

@Serializable
internal class RealAbsPayload(val operand: Int, val result: Int)

@Serializable
internal class RealArrayMinMaxPayload(val result: Int, val xs: IntArray, val max: Boolean)
