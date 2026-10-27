package com.eignex.klause.formats.json

import com.eignex.klause.factor.arithmetic.ReifiedCardinality
import com.eignex.klause.factor.arithmetic.ReifiedPseudoBoolean
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.GaussianXor
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.factor.bool.Xor
import com.eignex.klause.model.PbOp
import com.eignex.klause.util.BigInt
import kotlinx.serialization.Serializable

// Literals are `Lit` codes; `reified` is the Boolean variable equivalent to the body.

@Serializable
internal class ClausePayload(val literals: IntArray)

@Serializable
internal class CardinalityPayload(val literals: IntArray, val min: Int, val max: Int)

@Serializable
internal class XorPayload(val literals: IntArray, val parity: Int)

@Serializable
internal class GaussianXorPayload(val rows: List<XorPayload>)

@Serializable
internal class PseudoBooleanPayload(
    val literals: IntArray,
    val op: PbOp,
    val coefficients: LongArray? = null,
    val bound: Long? = null,
    val wideCoefficients: List<@Serializable(with = BigIntSerializer::class) BigInt>? = null,
    @Serializable(with = BigIntSerializer::class) val wideBound: BigInt? = null,
)

@Serializable
internal class ReifiedCardinalityPayload(val reified: Int, val literals: IntArray, val min: Int, val max: Int)

@Serializable
internal class ReifiedPseudoBooleanPayload(
    val reified: Int,
    val literals: IntArray,
    val op: PbOp,
    val coefficients: LongArray? = null,
    val bound: Long? = null,
    val wideCoefficients: List<@Serializable(with = BigIntSerializer::class) BigInt>? = null,
    @Serializable(with = BigIntSerializer::class) val wideBound: BigInt? = null,
)

private fun Xor.payload(): XorPayload = XorPayload(literals, targetParity)

private fun XorPayload.toXor(): Xor = Xor(literals, parity)

internal fun booleanFactorCodecs(): List<FactorCodec<*, *>> = listOf(
    factorCodec<Clause, ClausePayload>("clause", { ClausePayload(it.literals) }, { Clause(it.literals) }),
    factorCodec<Cardinality, CardinalityPayload>(
        "cardinality",
        { CardinalityPayload(it.literals, it.min, it.max) },
        { Cardinality(it.literals, it.min, it.max) },
    ),
    factorCodec<Xor, XorPayload>("xor", { it.payload() }, { it.toXor() }),
    factorCodec<GaussianXor, GaussianXorPayload>(
        "gaussianXor",
        { factor -> GaussianXorPayload(factor.constraints.map { it.payload() }) },
        { payload -> GaussianXor(payload.rows.map { it.toXor() }) },
    ),
    factorCodec<PseudoBoolean, PseudoBooleanPayload>(
        "pseudoBoolean",
        { pb ->
            val c = pb.constants
            PseudoBooleanPayload(pb.literals, pb.op, c.longCoefficients, c.longBound, c.wideCoefficients, c.wideBound)
        },
        { p ->
            integralRow(
                p.coefficients, p.bound, p.wideCoefficients, p.wideBound,
                { weights, bound -> PseudoBoolean(weights, p.literals, p.op, bound) },
                { weights, bound -> PseudoBoolean(weights, p.literals, p.op, bound) },
            )
        },
    ),
    factorCodec<ReifiedCardinality, ReifiedCardinalityPayload>(
        "reifiedCardinality",
        { ReifiedCardinalityPayload(it.auxBoolVar, it.literals, it.min, it.max) },
        { ReifiedCardinality(it.reified, it.literals, it.min, it.max) },
    ),
    factorCodec<ReifiedPseudoBoolean, ReifiedPseudoBooleanPayload>(
        "reifiedPseudoBoolean",
        { pb ->
            val c = pb.constants
            ReifiedPseudoBooleanPayload(
                pb.auxBoolVar, pb.literals, pb.op, c.longCoefficients, c.longBound, c.wideCoefficients, c.wideBound,
            )
        },
        { p ->
            integralRow(
                p.coefficients, p.bound, p.wideCoefficients, p.wideBound,
                { weights, bound -> ReifiedPseudoBoolean(p.reified, weights, p.literals, p.op, bound) },
                { weights, bound -> ReifiedPseudoBoolean(p.reified, weights, p.literals, p.op, bound) },
            )
        },
    ),
)
