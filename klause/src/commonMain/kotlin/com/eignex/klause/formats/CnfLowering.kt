package com.eignex.klause.formats

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.factor.Clause
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.factor.ReifiedLinear

/**
 * The CNF-lowering seam shared by the XCSP3 and SMT-LIB front-ends. Both compile boolean
 * structure to Tseitin gates and reify linear relations onto fresh aux bools over the same two
 * primitives — a [factors] sink and a fresh-bool allocator — so the gate/reify helpers below
 * live here once as extension functions rather than copy-pasted per format.
 */
internal interface CnfLowering {
    /** Where emitted [Clause] / [ReifiedLinear] factors are collected. */
    val factors: MutableList<Factor>

    /** Allocate a fresh boolean variable id. */
    fun newBool(): Int

    /** Backing cache for [trueLit]; implementers initialise to -1 (unallocated). */
    var trueLitCache: Int
}

/** A literal that is always true — allocated and unit-clamped lazily on first use, then cached. */
internal fun CnfLowering.trueLit(): Int {
    if (trueLitCache < 0) {
        trueLitCache = Lit.make(newBool(), true)
        factors.add(Clause(intArrayOf(trueLitCache)))
    }
    return trueLitCache
}

/** A fresh aux literal `a` with `a ⇔ ⋀ lits`: `a → lᵢ` per operand plus `(⋀lᵢ) → a`. */
internal fun CnfLowering.tseitinAnd(lits: List<Int>): Int {
    val a = Lit.make(newBool(), true)
    for (l in lits) factors.add(Clause(intArrayOf(Lit.negate(a), l)))
    factors.add(Clause((lits.map { Lit.negate(it) } + a).toIntArray()))
    return a
}

/** A fresh aux literal `a` with `a ⇔ ⋁ lits`: `a → ⋁lᵢ` plus `lᵢ → a` per operand. */
internal fun CnfLowering.tseitinOr(lits: List<Int>): Int {
    val a = Lit.make(newBool(), true)
    factors.add(Clause((lits + Lit.negate(a)).toIntArray()))
    for (l in lits) factors.add(Clause(intArrayOf(Lit.negate(l), a)))
    return a
}

/** A fresh aux literal `a` with `a ⇔ (x ⇔ y)`, from the four-row biconditional truth table. */
internal fun CnfLowering.tseitinIff(x: Int, y: Int): Int {
    val a = Lit.make(newBool(), true)
    factors.add(Clause(intArrayOf(Lit.negate(a), Lit.negate(x), y)))
    factors.add(Clause(intArrayOf(Lit.negate(a), x, Lit.negate(y))))
    factors.add(Clause(intArrayOf(a, x, y)))
    factors.add(Clause(intArrayOf(a, Lit.negate(x), Lit.negate(y))))
    return a
}

/** Reify a linear relation `coeffs·vars ⟨op⟩ bound` onto a fresh aux bool; returns its positive literal. */
internal fun CnfLowering.reifyLinear(coeffs: IntArray, vars: IntArray, op: LinearOp, bound: Int): Int {
    val aux = newBool()
    factors.add(ReifiedLinear(auxBoolVar = aux, coeffs = coeffs, vars = vars, op = op, bound = bound))
    return Lit.make(aux, true)
}
