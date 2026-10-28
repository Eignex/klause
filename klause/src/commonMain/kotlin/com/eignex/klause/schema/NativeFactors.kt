package com.eignex.klause.schema

import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.RealAbs
import com.eignex.klause.factor.arithmetic.RealArrayMinMax
import com.eignex.klause.factor.arithmetic.RealFiniteDomain
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.factor.codec.FactorCodecs
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.global.AllDifferentTuples
import com.eignex.klause.factor.global.Distinct
import com.eignex.klause.factor.global.NValue
import com.eignex.klause.factor.global.ValuePrecede
import com.eignex.klause.factor.table.RealElement
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Term
import com.eignex.klause.model.BoolExpr
import com.eignex.klause.model.BoolRef
import com.eignex.klause.model.BoolTerm
import com.eignex.klause.model.FactorConstraint
import com.eignex.klause.model.IntLit
import com.eignex.klause.model.IntRef
import com.eignex.klause.model.IntTerm
import com.eignex.klause.model.NValueExpr
import com.eignex.klause.model.NValueMode
import com.eignex.klause.model.PbOp
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BigInt
import kotlinx.serialization.json.JsonElement
import kotlin.jvm.JvmName

// Native factors bind declared variables by name: a factor built over local ids `0 until n` of each
// namespace reads its Boolean id `i` as `bools[i]`, its integer id `i` as `ints[i]` and its real id `i`
// as `reals[i]`. Compilation renames the ids to the compiled columns and posts the factor unchanged.

/**
 * Post [relation] as written, over the declared variables it binds by local id. [codecs] must know the
 * factor's class; compile with the same registry. Asserted at the top level only.
 */
fun factor(
    relation: Factor,
    bools: List<BoolTerm> = emptyList(),
    ints: List<IntTerm> = emptyList(),
    reals: List<FloatHandle> = emptyList(),
    codecs: FactorCodecs = FactorCodecs.builtin,
): BoolExpr = factor(codecs.encodeToJsonElement(relation), bools, ints, reals)

/**
 * Post the factor-codec [node], the same `{key: payload}` vocabulary problem documents use, over the
 * declared variables it binds by local id. Asserted at the top level only.
 */
fun factor(
    node: JsonElement,
    bools: List<BoolTerm> = emptyList(),
    ints: List<IntTerm> = emptyList(),
    reals: List<FloatHandle> = emptyList(),
): BoolExpr =
    FactorConstraint(node, bools.map { it.boolName() }, ints.map { it.intName() }, reals.map { it.name })

private fun BoolTerm.boolName(): String {
    val ref = toExpr() as? BoolRef
    require(ref != null && !ref.negated) { "a bound Boolean must be a declared variable" }
    return ref.name
}

private fun IntTerm.intName(): String =
    requireNotNull((toIntExpr() as? IntRef)?.name) { "a bound integer must be a declared variable" }

private fun localIds(count: Int): IntArray = IntArray(count) { it }

/** `xs` with every occurrence of [t] preceded by an occurrence of [s]. */
fun valuePrecede(s: Long, t: Long, xs: List<IntTerm>): BoolExpr =
    factor(ValuePrecede(s, t, localIds(xs.size)), ints = xs)

/** The integer [tuples] are pairwise different as whole tuples. */
fun allDifferentTuples(tuples: List<List<IntTerm>>): BoolExpr {
    var next = 0
    val ids = tuples.map { tuple -> IntArray(tuple.size) { next++ } }
    return factor(AllDifferentTuples(ids), ints = tuples.flatten())
}

/**
 * [xs] take pairwise different values, except that values in [except] may repeat. [boundsConsistent]
 * selects bounds rather than domain filtering.
 */
fun allDifferentExcept(xs: List<IntTerm>, except: Collection<Long>, boundsConsistent: Boolean = false): BoolExpr {
    val ids = localIds(xs.size)
    return factor(AllDifferent(ids, exceptSet = except.toLongArray(), boundsConsistent = boundsConsistent), ints = xs)
}

/** The number of distinct values of [xs] outside [except] relates to [n] by [mode]. */
fun nValue(
    n: Int,
    xs: List<IntTerm>,
    mode: NValue.Mode = NValue.Mode.Eq,
    except: Collection<Long> = emptySet(),
): BoolExpr = nValue(IntLit(n), xs, mode, except)

/** The number of distinct values of [xs] outside [except] relates to target [n] by [mode]. */
fun nValue(
    n: IntTerm,
    xs: List<IntTerm>,
    mode: NValue.Mode = NValue.Mode.Eq,
    except: Collection<Long> = emptySet(),
): BoolExpr = NValueExpr(
    n.toIntExpr(),
    xs.map { it.toIntExpr() },
    when (mode) {
        NValue.Mode.Eq -> NValueMode.EQ
        NValue.Mode.AtLeast -> NValueMode.AT_LEAST
        NValue.Mode.AtMost -> NValueMode.AT_MOST
    },
    except.toList(),
)

/** One exact affine operand of [distinct]: `Σ coefficient·variable + constant`. */
class AffineOperand(
    /** Integer terms. */
    val ints: List<Pair<IntTerm, BigFraction>> = emptyList(),
    /** Boolean terms; a negated reference contributes `1 - variable`. */
    val bools: List<Pair<BoolTerm, BigFraction>> = emptyList(),
    /** Real terms. */
    val reals: List<Pair<FloatHandle, BigFraction>> = emptyList(),
    /** Additive constant. */
    val constant: BigFraction = BigFraction.ZERO,
)

/** The exact values of [operands] are pairwise different. */
fun distinct(operands: List<AffineOperand>): BoolExpr {
    val ints = operands.flatMap { operand -> operand.ints.map { it.first.intName() } }.distinct()
    val bools = operands.flatMap { operand -> operand.bools.map { boolOperand(it.first).first } }.distinct()
    val reals = operands.flatMap { operand -> operand.reals.map { it.first.name } }.distinct()
    val native = operands.map { operand ->
        val refs = operand.ints.map { Term.ofIntVar(ints.indexOf(it.first.intName())) } +
            operand.bools.map { (term, _) ->
                val (name, positive) = boolOperand(term)
                Term.ofLit(Lit.make(bools.indexOf(name), positive))
            } +
            operand.reals.map { Term.ofRealVar(reals.indexOf(it.first.name)) }
        val coefficients = (operand.ints + operand.bools + operand.reals).map { it.second }
        Distinct.Operand(refs.toIntArray(), coefficients, operand.constant)
    }
    return FactorConstraint(FactorCodecs.builtin.encodeToJsonElement(Distinct(native)), bools, ints, reals)
}

private fun boolOperand(term: BoolTerm): Pair<String, Boolean> {
    val ref = requireNotNull(term.toExpr() as? BoolRef) { "a bound Boolean must be a declared variable" }
    return ref.name to !ref.negated
}

/** `Σ coefficients(i)·vars(i) ⟨op⟩ bound` with arbitrary-width integer constants. */
fun linear(coefficients: List<BigInt>, vars: List<IntTerm>, op: LinearOp, bound: BigInt): BoolExpr =
    factor(Linear(localIds(vars.size), coefficients.toTypedArray(), op, bound), ints = vars)

/**
 * `Σ c·x ⟨op⟩ bound` over integer and real variables with exact rational constants; [strict] excludes the
 * boundary of an inequality.
 */
fun exactLinear(
    ints: List<Pair<IntTerm, BigFraction>>,
    reals: List<Pair<FloatHandle, BigFraction>>,
    op: LinearOp,
    bound: BigFraction,
    strict: Boolean = false,
): BoolExpr {
    val refs = IntArray(ints.size) { Term.ofIntVar(it) } + IntArray(reals.size) { Term.ofRealVar(it) }
    val row = ExactLinear.of(refs, ints.map { it.second } + reals.map { it.second }, op, bound, strict)
    return factor(row, ints = ints.map { it.first }, reals = reals.map { it.first })
}

/** `Σ weights(i)·lits(i) ⟨op⟩ bound` with arbitrary-width integer constants. */
fun pseudoBoolean(weights: List<BigInt>, lits: List<BoolTerm>, op: PbOp, bound: BigInt): BoolExpr {
    val names = lits.map { boolOperand(it).first }.distinct()
    val literals = IntArray(lits.size) {
        val (name, positive) = boolOperand(lits[it])
        Lit.make(names.indexOf(name), positive)
    }
    val node = FactorCodecs.builtin.encodeToJsonElement(PseudoBoolean(weights.toTypedArray(), literals, op, bound))
    return FactorConstraint(node, bools = names)
}

/** `result = |operand|` over reals. */
fun realAbs(operand: FloatHandle, result: FloatHandle): BoolExpr =
    factor(RealAbs(0, 1), reals = listOf(operand, result))

/** `result = min(xs)` over reals. */
fun realMin(result: FloatHandle, xs: List<FloatHandle>): BoolExpr =
    factor(RealArrayMinMax(0, IntArray(xs.size) { it + 1 }, max = false), reals = listOf(result) + xs)

/** `result = max(xs)` over reals. */
fun realMax(result: FloatHandle, xs: List<FloatHandle>): BoolExpr =
    factor(RealArrayMinMax(0, IntArray(xs.size) { it + 1 }, max = true), reals = listOf(result) + xs)

/** `result = items(index - indexOffset)` over real variables. */
fun realElement(index: IntTerm, items: List<FloatHandle>, result: FloatHandle, indexOffset: Int = 0): BoolExpr =
    factor(
        RealElement(0, 0, IntArray(items.size) { it + 1 }, indexOffset),
        ints = listOf(index),
        reals = listOf(result) + items,
    )

/** `result = values(index - indexOffset)` over binary64 constants. */
@JvmName("realElementOfValues")
fun realElement(index: IntTerm, values: List<Double>, result: FloatHandle, indexOffset: Int = 0): BoolExpr =
    factor(RealElement(0, 0, values.toDoubleArray(), indexOffset), ints = listOf(index), reals = listOf(result))

/** This real takes one of [values]. */
infix fun FloatHandle.inValues(values: Collection<Double>): BoolExpr =
    factor(RealFiniteDomain(0, values.toDoubleArray()), reals = listOf(this))
