package com.eignex.klause.factor.codec

import com.eignex.klause.factor.arithmetic.ComparisonClause
import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearIndicator
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.ir.IntegerConstants
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.RealConstants
import com.eignex.klause.ir.WideConstants
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BigInt
import kotlinx.serialization.Serializable

/**
 * `Σ terms ⟨op⟩ bound` in one of three widths: 64-bit integer constants, `wide…` arbitrary-width ones,
 * or an LP-only row over real columns with binary64 constants (`intCoefficients` for [vars],
 * `realCoefficients` for [realVars], `realBound`, `strict`).
 */
@Serializable
internal class LinearPayload(
    val vars: IntArray,
    val op: LinearOp,
    val coefficients: LongArray? = null,
    val bound: Long? = null,
    val wideCoefficients: List<@Serializable(with = BigIntSerializer::class) BigInt>? = null,
    @Serializable(with = BigIntSerializer::class) val wideBound: BigInt? = null,
    val realVars: IntArray? = null,
    val intCoefficients: DoubleArray? = null,
    val realCoefficients: DoubleArray? = null,
    val realBound: Double? = null,
    val strict: Boolean = false,
)

@Serializable
internal class ReifiedLinearPayload(
    val reified: Int,
    val vars: IntArray,
    val op: LinearOp,
    val coefficients: LongArray? = null,
    val bound: Long? = null,
    val wideCoefficients: List<@Serializable(with = BigIntSerializer::class) BigInt>? = null,
    @Serializable(with = BigIntSerializer::class) val wideBound: BigInt? = null,
)

/** Exact row over `Term` codes; `activator`, when present, is the Boolean variable equivalent to it. */
@Serializable
internal class ExactLinearPayload(
    val terms: IntArray,
    val coefficients: List<@Serializable(with = BigIntSerializer::class) BigInt>,
    @Serializable(with = BigIntSerializer::class) val bound: BigInt,
    val op: LinearOp,
    val strict: Boolean = false,
    val activator: Int? = null,
)

@Serializable
internal class LinearIndicatorPayload(val indicator: Int, val whenOne: Boolean, val row: ExactLinearPayload)

@Serializable
internal class ReifiedRealLinearPayload(
    val reified: Int,
    val vars: IntArray,
    val intCoefficients: DoubleArray,
    val realVars: IntArray,
    val realCoefficients: DoubleArray,
    val op: LinearOp,
    val bound: Double,
    val strict: Boolean = false,
)

@Serializable
internal class ComparisonClausePayload(val vars: IntArray, val ops: List<LinearOp>, val constants: LongArray)

private fun Linear.payload(): LinearPayload = when (val c = constants) {
    is IntegerConstants -> LinearPayload(vars, op, coefficients = c.coeffs, bound = c.bound)
    is WideConstants -> LinearPayload(
        vars, op, wideCoefficients = c.coefficients.toTypedArray().toList(), wideBound = c.bound,
    )
    is RealConstants -> LinearPayload(
        vars,
        op,
        realVars = realVars,
        intCoefficients = c.intCoefficients.toDoubleArray(),
        realCoefficients = c.realCoefficients.toDoubleArray(),
        realBound = c.bound,
        strict = c.strict,
    )
}

private fun LinearPayload.toLinear(): Linear {
    val realVars = realVars
    if (realVars == null) {
        require(intCoefficients == null && realCoefficients == null && realBound == null && !strict) {
            "real row fields need 'realVars'"
        }
        return integralRow(
            coefficients, bound, wideCoefficients, wideBound,
            { coeffs, bound -> Linear(coeffs, vars, op, bound) },
            { coeffs, bound -> Linear(vars, coeffs, op, bound) },
        )
    }
    require(coefficients == null && bound == null && wideCoefficients == null && wideBound == null) {
        "a real row states 'intCoefficients', 'realCoefficients' and 'realBound'"
    }
    return Linear(
        vars,
        intCoefficients ?: DoubleArray(0),
        realVars,
        requireNotNull(realCoefficients) { "a real row needs 'realCoefficients'" },
        op,
        requireNotNull(realBound) { "a real row needs 'realBound'" },
        strict,
    )
}

private fun ExactLinear.payload(): ExactLinearPayload {
    val c = constants
    return ExactLinearPayload(
        terms = IntArray(size) { ref(it) },
        coefficients = List(size) { c.exactCoeff(it) },
        bound = c.exactBound,
        op = relation,
        strict = strict,
        activator = activator.takeUnless { it == LinearRow.ALWAYS },
    )
}

// Stored rows are already integral and canonical, so the factory keeps them as written.
private fun ExactLinearPayload.toExactLinear(): ExactLinear {
    require(terms.size == coefficients.size) { "terms and coefficients must align" }
    return ExactLinear.of(
        terms,
        coefficients.map { BigFraction.of(it, BIG_ONE) },
        op,
        BigFraction.of(bound, BIG_ONE),
        strict,
        activator ?: LinearRow.ALWAYS,
    )
}

internal fun linearFactorCodecs(): List<FactorCodec<*, *>> = listOf(
    factorCodec<Linear, LinearPayload>("linear", { it.payload() }, { it.toLinear() }),
    factorCodec<ReifiedLinear, ReifiedLinearPayload>(
        "reifiedLinear",
        { row ->
            val c = row.constants
            ReifiedLinearPayload(
                row.auxBoolVar, row.vars, row.op, c.longCoefficients, c.longBound, c.wideCoefficients, c.wideBound,
            )
        },
        { p ->
            integralRow(
                p.coefficients, p.bound, p.wideCoefficients, p.wideBound,
                { coeffs, bound -> ReifiedLinear(p.reified, coeffs, p.vars, p.op, bound) },
                { coeffs, bound -> ReifiedLinear(p.reified, p.vars, coeffs, p.op, bound) },
            )
        },
    ),
    factorCodec<ExactLinear, ExactLinearPayload>("exactLinear", { it.payload() }, { it.toExactLinear() }),
    factorCodec<LinearIndicator, LinearIndicatorPayload>(
        "linearIndicator",
        { LinearIndicatorPayload(it.indicator, it.whenOne, it.row.payload()) },
        { LinearIndicator(it.indicator, it.whenOne, it.row.toExactLinear()) },
    ),
    factorCodec<ReifiedRealLinear, ReifiedRealLinearPayload>(
        "reifiedRealLinear",
        {
            ReifiedRealLinearPayload(
                it.aux, it.vars, it.intCoeffs, it.realVars, it.realCoeffs, it.op, it.bound, it.strict,
            )
        },
        {
            ReifiedRealLinear(
                it.reified, it.vars, it.intCoefficients, it.realVars, it.realCoefficients, it.op, it.bound, it.strict,
            )
        },
    ),
    factorCodec<ComparisonClause, ComparisonClausePayload>(
        "comparisonClause",
        { ComparisonClausePayload(it.vars, it.ops.toList(), it.consts) },
        { ComparisonClause(it.vars, it.ops.toTypedArray(), it.constants) },
    ),
)
