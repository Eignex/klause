package com.eignex.klause.formats

import com.eignex.klause.factor.arithmetic.IntegerConstants
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.arithmetic.WideConstants
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.solver.Factor
import com.ionspin.kotlin.bignum.integer.BigInteger

/**
 * The reified rows a unit clause decides, as unconditional linear rows.
 *
 * A `ReifiedLinear` states `b ↔ (Σ c·x ⟨op⟩ k)`. A unit clause fixes `b`, so the row (or its integer
 * negation) holds outright and is an ordinary constraint of the model — but it reaches nothing that reads
 * only the hard `Linear` factors. On a bit-blasted instance that is most of the model: `convert-jpg2gif-
 * query-1577` carries 1830 reified rows, 1475 of them fixed by a unit, against 2381 plain rows.
 *
 * Recovering them matters wherever a model's bounds live in its boolean structure — bound tightening and
 * the open-domain refutation both reason over linear rows alone, so what they never receive they can
 * never use. Front-end agnostic: MPS lowers indicator constraints to the same reified form.
 */
internal fun rootFixedReifiedRows(factors: List<Factor>): List<Linear> {
    val fixed = HashMap<Int, Boolean>()
    for (f in factors) {
        if (f !is Clause || f.literals.size != 1) continue
        val lit = f.literals[0]
        val variable = Lit.variable(lit)
        val positive = Lit.isPositive(lit)
        // A variable fixed both ways makes the model unsat on its own; leave that to the search rather
        // than picking a side here.
        if (fixed.put(variable, positive)?.let { it != positive } == true) fixed.remove(variable)
    }
    if (fixed.isEmpty()) return emptyList()
    val out = ArrayList<Linear>()
    for (f in factors) {
        if (f !is ReifiedLinear) continue
        val holds = fixed[f.auxBoolVar] ?: continue
        out.add(unconditional(f, holds) ?: continue)
    }
    return out
}

/**
 * [f]'s row when its literal is true, or its integer negation when false.
 *
 * Over the integers a strict complement is an ordinary bound shift — `¬(Σ ≤ k)` is `Σ ≥ k+1` — which is
 * why the negated form is usable at all. A negated equality is a disequality, which bounds no interval, so
 * it yields null rather than a row that claims more than it knows.
 */
private fun unconditional(f: ReifiedLinear, holds: Boolean): Linear? {
    val op = if (holds) f.op else negatedOp(f.op) ?: return null
    val shift = if (holds) 0L else negationShift(f.op)
    return when (val c = f.constants) {
        is WideConstants -> Linear(
            f.vars.copyOf(),
            c.coefficients.toTypedArray(),
            op,
            c.bound + BigInteger.fromLong(shift),
        )

        is IntegerConstants -> {
            // The shifted bound of a negation must stay exact; at the extreme of the range it would wrap,
            // and a wrapped bound is a constraint the model never stated.
            val shifted = c.bound + shift
            if (shift != 0L && ((c.bound > 0L && shifted < 0L) || (c.bound < 0L && shifted > 0L))) {
                null
            } else {
                Linear(c.coeffs, f.vars.copyOf(), op, shifted)
            }
        }
    }
}

/** The relation `¬(Σ ⟨op⟩ k)` uses; null where the negation is a disequality and bounds nothing. */
private fun negatedOp(op: LinearOp): LinearOp? = when (op) {
    LinearOp.LE -> LinearOp.GE
    LinearOp.GE -> LinearOp.LE
    LinearOp.NE -> LinearOp.EQ
    LinearOp.EQ -> null
}

/** The bound offset the negation carries: `¬(Σ ≤ k)` is `Σ ≥ k+1`, `¬(Σ ≥ k)` is `Σ ≤ k−1`. */
private fun negationShift(op: LinearOp): Long = when (op) {
    LinearOp.LE -> 1L
    LinearOp.GE -> -1L
    else -> 0L
}
