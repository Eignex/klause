package com.eignex.klause.factor.codec

import com.eignex.klause.factor.circuit.Circuit
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.global.AllDifferentTuples
import com.eignex.klause.factor.global.Distinct
import com.eignex.klause.factor.global.GlobalCardinality
import com.eignex.klause.factor.global.Increasing
import com.eignex.klause.factor.global.Inverse
import com.eignex.klause.factor.global.LexLess
import com.eignex.klause.factor.global.NValue
import com.eignex.klause.factor.global.Sort
import com.eignex.klause.factor.global.SymmetricAllDifferent
import com.eignex.klause.factor.global.ValuePrecede
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.simplex.exact.BigFraction
import kotlinx.serialization.Serializable

// `presents` are the optional-presence literals of an optional global, aligned with its operands.

@Serializable
internal class AllDifferentPayload(
    val vars: IntArray,
    val presents: IntArray? = null,
    val except: LongArray? = null,
    val boundsConsistent: Boolean = false,
)

@Serializable
internal class AllDifferentTuplesPayload(val tuples: List<IntArray>)

/** Exact affine operand over `Term` codes. */
@Serializable
internal class DistinctOperandPayload(
    val terms: IntArray,
    val coefficients: List<@Serializable(with = BigFractionSerializer::class) BigFraction>,
    @Serializable(with = BigFractionSerializer::class) val constant: BigFraction = BigFraction.ZERO,
)

@Serializable
internal class DistinctPayload(val operands: List<DistinctOperandPayload>, val activator: Int? = null)

/** Counts are either [countVars] columns or fixed [countLow]..[countHigh] ranges. */
@Serializable
internal class GlobalCardinalityPayload(
    val xs: IntArray,
    val cover: LongArray,
    val countVars: IntArray? = null,
    val countLow: IntArray? = null,
    val countHigh: IntArray? = null,
    val closed: Boolean = false,
    val presents: IntArray? = null,
)

@Serializable
internal class NValuePayload(
    val n: Int,
    val xs: IntArray,
    val mode: NValue.Mode = NValue.Mode.Eq,
    val presents: IntArray? = null,
    val except: LongArray? = null,
)

@Serializable
internal class IncreasingPayload(val xs: IntArray, val strict: Boolean)

@Serializable
internal class SortPayload(val xs: IntArray, val ys: IntArray)

@Serializable
internal class ValuePrecedePayload(val s: Long, val t: Long, val xs: IntArray)

@Serializable
internal class LexLessPayload(val xs: IntArray, val ys: IntArray, val strict: Boolean)

@Serializable
internal class InversePayload(val f: IntArray, val g: IntArray, val fOffset: Int = 0, val gOffset: Int = 0)

@Serializable
internal class SymmetricAllDifferentPayload(val xs: IntArray, val indexOffset: Int = 0)

@Serializable
internal class CircuitPayload(val succ: IntArray, val subcircuit: Boolean = false)

private fun IntArray.orNullIfEmpty(): IntArray? = takeIf { it.isNotEmpty() }

private fun LongArray.orNullIfEmpty(): LongArray? = takeIf { it.isNotEmpty() }

private fun Distinct.payload(): DistinctPayload = DistinctPayload(
    operands.map { DistinctOperandPayload(it.references, it.coefficients, it.constant) },
    activator.takeUnless { it == LinearRow.ALWAYS },
)

private fun DistinctPayload.toDistinct(): Distinct = Distinct(
    operands.map { operand ->
        require(operand.terms.size == operand.coefficients.size) { "terms and coefficients must align" }
        Distinct.Operand(operand.terms, operand.coefficients, operand.constant)
    },
    activator ?: LinearRow.ALWAYS,
)

internal fun globalFactorCodecs(): List<FactorCodec<*, *>> = listOf(
    factorCodec<AllDifferent, AllDifferentPayload>(
        "allDifferent",
        {
            AllDifferentPayload(
                it.vars, it.presents.orNullIfEmpty(), it.exceptSet.orNullIfEmpty(), it.boundsConsistent,
            )
        },
        {
            AllDifferent(
                it.vars, it.presents ?: IntArray(0), it.except ?: LongArray(0), it.boundsConsistent,
            )
        },
    ),
    factorCodec<AllDifferentTuples, AllDifferentTuplesPayload>(
        "allDifferentTuples",
        { AllDifferentTuplesPayload(it.tuples) },
        { AllDifferentTuples(it.tuples) },
    ),
    factorCodec<Distinct, DistinctPayload>("distinct", { it.payload() }, { it.toDistinct() }),
    factorCodec<GlobalCardinality, GlobalCardinalityPayload>(
        "globalCardinality",
        {
            GlobalCardinalityPayload(
                it.xs, it.cover, it.countVars, it.countLow, it.countHigh, it.closed, it.presents.orNullIfEmpty(),
            )
        },
        {
            GlobalCardinality(
                it.xs, it.cover, it.countVars, it.countLow, it.countHigh, it.closed, it.presents ?: IntArray(0),
            )
        },
    ),
    factorCodec<NValue, NValuePayload>(
        "nValue",
        { NValuePayload(it.n, it.xs, it.mode, it.presents.orNullIfEmpty(), it.exceptSet.orNullIfEmpty()) },
        { NValue(it.n, it.xs, it.mode, it.presents ?: IntArray(0), it.except ?: LongArray(0)) },
    ),
    factorCodec<Increasing, IncreasingPayload>(
        "increasing",
        { IncreasingPayload(it.xs, it.strict) },
        { Increasing(it.xs, it.strict) },
    ),
    factorCodec<Sort, SortPayload>("sort", { SortPayload(it.xs, it.ys) }, { Sort(it.xs, it.ys) }),
    factorCodec<ValuePrecede, ValuePrecedePayload>(
        "valuePrecede",
        { ValuePrecedePayload(it.s, it.t, it.xs) },
        { ValuePrecede(it.s, it.t, it.xs) },
    ),
    factorCodec<LexLess, LexLessPayload>(
        "lexLess",
        { LexLessPayload(it.xs, it.ys, it.strict) },
        { LexLess(it.xs, it.ys, it.strict) },
    ),
    factorCodec<Inverse, InversePayload>(
        "inverse",
        { InversePayload(it.f, it.g, it.fOffset, it.gOffset) },
        { Inverse(it.f, it.g, it.fOffset, it.gOffset) },
    ),
    factorCodec<SymmetricAllDifferent, SymmetricAllDifferentPayload>(
        "symmetricAllDifferent",
        { SymmetricAllDifferentPayload(it.xs, it.indexOffset) },
        { SymmetricAllDifferent(it.xs, it.indexOffset) },
    ),
    factorCodec<Circuit, CircuitPayload>(
        "circuit",
        { CircuitPayload(it.succ, it.subcircuit) },
        { Circuit(it.succ, it.subcircuit) },
    ),
)
