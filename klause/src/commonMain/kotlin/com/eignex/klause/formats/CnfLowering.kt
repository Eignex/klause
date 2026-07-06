package com.eignex.klause.formats

import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit

/** Shared CNF-lowering hooks for format front-ends. */
internal interface CnfLowering {
    /** Sink for emitted factors. */
    val factors: MutableList<Factor>

    /** Allocate a fresh boolean variable id. */
    fun newBool(): Int

    /** Backing cache for [trueLit]; initialise to -1. */
    var trueLitCache: Int
}

/** Lazily allocated literal that is forced true. */
internal fun CnfLowering.trueLit(): Int {
    if (trueLitCache < 0) {
        trueLitCache = Lit.make(newBool(), true)
        factors.add(Clause(intArrayOf(trueLitCache)))
    }
    return trueLitCache
}

/** Tseitin gate for conjunction. */
internal fun CnfLowering.tseitinAnd(lits: List<Int>): Int {
    val a = Lit.make(newBool(), true)
    for (l in lits) factors.add(Clause(intArrayOf(Lit.negate(a), l)))
    factors.add(Clause((lits.map { Lit.negate(it) } + a).toIntArray()))
    return a
}

/** Tseitin gate for disjunction. */
internal fun CnfLowering.tseitinOr(lits: List<Int>): Int {
    val a = Lit.make(newBool(), true)
    factors.add(Clause((lits + Lit.negate(a)).toIntArray()))
    for (l in lits) factors.add(Clause(intArrayOf(Lit.negate(l), a)))
    return a
}

/** Tseitin gate for biconditional. */
internal fun CnfLowering.tseitinIff(x: Int, y: Int): Int {
    val a = Lit.make(newBool(), true)
    factors.add(Clause(intArrayOf(Lit.negate(a), Lit.negate(x), y)))
    factors.add(Clause(intArrayOf(Lit.negate(a), x, Lit.negate(y))))
    factors.add(Clause(intArrayOf(a, x, y)))
    factors.add(Clause(intArrayOf(a, Lit.negate(x), Lit.negate(y))))
    return a
}

/** Reify one linear relation onto a fresh positive literal. A relation whose terms all cancel to
 *  a constant (`0 ⟨op⟩ bound`) reifies to the true/false literal without a [ReifiedLinear] factor. */
internal fun CnfLowering.reifyLinear(coeffs: IntArray, vars: IntArray, op: LinearOp, bound: Int): Int {
    if (vars.isEmpty()) return if (constRelationHolds(op, bound)) trueLit() else Lit.negate(trueLit())
    val aux = newBool()
    factors.add(ReifiedLinear(auxBoolVar = aux, coeffs = coeffs, vars = vars, op = op, bound = bound))
    return Lit.make(aux, true)
}

/**
 * Channel Boolean [auxBoolVar]'s truth onto the 0/1 integer [intVar]: posts `x_intVar = 1` iff
 * [auxBoolVar] holds (or `= 0` when [whenTrue] is false, for a negated-literal channel). The single
 * `ReifiedLinear(aux, [1], [intVar], EQ, 0|1)` construction the FlatZinc, SMT-LIB, and XCSP3 front-ends
 * each use to bridge a bool decision into an integer sum. [intVar] must already be allocated with
 * domain `{0, 1}`; the caller sets up [auxBoolVar] (a raw literal's var, a Tseitin-tied bool, or a
 * reified equality's indicator).
 */
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

/** Whether the constant relation `0 ⟨op⟩ bound` holds — the value of a linear whose terms cancel. */
internal fun constRelationHolds(op: LinearOp, bound: Int): Boolean = when (op) {
    LinearOp.LE -> 0 <= bound
    LinearOp.GE -> 0 >= bound
    LinearOp.EQ -> 0 == bound
    LinearOp.NE -> 0 != bound
}
