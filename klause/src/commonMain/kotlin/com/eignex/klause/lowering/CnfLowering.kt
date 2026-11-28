package com.eignex.klause.lowering

import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.util.numbers.BIG_ZERO
import com.eignex.util.numbers.BigInt
import com.eignex.util.numbers.compareTo

// Shared by schema compilation and textual front-ends.
internal interface CnfLowering {
    val factors: MutableList<Factor>

    fun newBool(): Int

    // Initialise to -1.
    var trueLitCache: Int
}

internal fun CnfLowering.trueLit(): Int {
    if (trueLitCache < 0) {
        trueLitCache = Lit.make(newBool(), true)
        factors.add(Clause(intArrayOf(trueLitCache)))
    }
    return trueLitCache
}

// G. S. Tseitin, "On the complexity of derivation in propositional calculus", 1968; reprinted in
// Automation of Reasoning 2, Springer 1983. https://doi.org/10.1007/978-3-642-81955-1_28
internal fun CnfLowering.tseitinAnd(lits: List<Int>): Int = tseitinAnd(lits, Lit.make(newBool(), true))

internal fun CnfLowering.tseitinAnd(lits: List<Int>, target: Int): Int {
    for (l in lits) factors.add(Clause(intArrayOf(Lit.negate(target), l)))
    factors.add(Clause(IntArray(lits.size + 1) { i -> if (i == lits.size) target else Lit.negate(lits[i]) }))
    return target
}

internal fun CnfLowering.tseitinOr(lits: List<Int>): Int = tseitinOr(lits, Lit.make(newBool(), true))

internal fun CnfLowering.tseitinOr(lits: List<Int>, target: Int): Int {
    factors.add(Clause(IntArray(lits.size + 1) { i -> if (i == lits.size) Lit.negate(target) else lits[i] }))
    for (l in lits) factors.add(Clause(intArrayOf(Lit.negate(l), target)))
    return target
}

internal fun CnfLowering.tseitinIff(x: Int, y: Int): Int {
    val a = Lit.make(newBool(), true)
    factors.add(Clause(intArrayOf(Lit.negate(a), Lit.negate(x), y)))
    factors.add(Clause(intArrayOf(Lit.negate(a), x, Lit.negate(y))))
    factors.add(Clause(intArrayOf(a, x, y)))
    factors.add(Clause(intArrayOf(a, Lit.negate(x), Lit.negate(y))))
    return a
}

// A relation whose terms all cancel reifies to the constant literal without a factor.
internal fun CnfLowering.reifyLinear(coeffs: IntArray, vars: IntArray, op: LinearOp, bound: Int): Int {
    if (vars.isEmpty()) return if (constRelationHolds(op, bound)) trueLit() else Lit.negate(trueLit())
    val aux = newBool()
    factors.add(ReifiedLinear(auxBoolVar = aux, coeffs = coeffs, vars = vars, op = op, bound = bound))
    return Lit.make(aux, true)
}

internal fun CnfLowering.reifyLinear(coeffs: LongArray, vars: IntArray, op: LinearOp, bound: Long): Int {
    if (vars.isEmpty()) return if (constRelationHolds(op, bound)) trueLit() else Lit.negate(trueLit())
    val aux = newBool()
    factors.add(ReifiedLinear(auxBoolVar = aux, coeffs = coeffs, vars = vars, op = op, bound = bound))
    return Lit.make(aux, true)
}

internal fun CnfLowering.reifyLinear(coeffs: Array<BigInt>, vars: IntArray, op: LinearOp, bound: BigInt): Int {
    if (vars.isEmpty()) {
        val holds = when (op) {
            LinearOp.LE -> BIG_ZERO <= bound
            LinearOp.GE -> BIG_ZERO >= bound
            LinearOp.EQ -> bound == BIG_ZERO
            LinearOp.NE -> bound != BIG_ZERO
        }
        return if (holds) trueLit() else Lit.negate(trueLit())
    }
    val aux = newBool()
    factors.add(ReifiedLinear(auxBoolVar = aux, vars = vars, wideCoeffs = coeffs, op = op, wideBound = bound))
    return Lit.make(aux, true)
}

internal fun CnfLowering.reifyDifferenceLinear(coeffs: LongArray, vars: IntArray, op: LinearOp, bound: Long): Int {
    val factor = ReifiedLinear(newBool(), coeffs, vars, op, bound)
    val literal = Lit.make(factor.auxBoolVar, true)
    val constants = factor.integerConstants
    val difference = constants != null && factor.vars.size == 2 &&
        (constants.coeff(0) == 1L || constants.coeff(0) == -1L) &&
        constants.coeff(1) == -constants.coeff(0)
    if (!difference || (factor.op != LinearOp.EQ && factor.op != LinearOp.NE)) {
        factors.add(factor)
        return literal
    }
    val rowConstants = requireNotNull(constants)
    // Splitting both meanings keeps guarded disequalities within signed difference coverage.
    tseitinAnd(
        listOf(
            reifyLinear(rowConstants.coeffs, factor.vars, LinearOp.LE, rowConstants.bound),
            reifyLinear(rowConstants.coeffs, factor.vars, LinearOp.GE, rowConstants.bound),
        ),
        if (factor.op == LinearOp.EQ) literal else Lit.negate(literal),
    )
    return literal
}

// The caller allocates `intVar` with domain `{0, 1}`.
internal fun channelBoolTo01(factors: MutableList<Factor>, auxBoolVar: Int, intVar: Int, whenTrue: Boolean = true) {
    factors.add(
        ReifiedLinear(
            auxBoolVar = auxBoolVar,
            coeffs = intArrayOf(1),
            vars = intArrayOf(intVar),
            op = LinearOp.EQ,
            bound = if (whenTrue) 1 else 0,
        ),
    )
}

internal fun constRelationHolds(op: LinearOp, bound: Int): Boolean = when (op) {
    LinearOp.LE -> 0 <= bound
    LinearOp.GE -> 0 >= bound
    LinearOp.EQ -> 0 == bound
    LinearOp.NE -> 0 != bound
}

internal fun constRelationHolds(op: LinearOp, bound: Long): Boolean = when (op) {
    LinearOp.LE -> 0L <= bound
    LinearOp.GE -> 0L >= bound
    LinearOp.EQ -> 0L == bound
    LinearOp.NE -> 0L != bound
}
