package com.eignex.klause.formats

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.table.Element
import com.eignex.klause.solver.Lit

/**
 * Linear-size reification `d ↔ all_different(vars)`, shared by every front-end that lowers through
 * [CnfLowering].
 *
 * The true side is the native global itself: every position carries the *same* presence literal `d`, so
 * [AllDifferent] is required exactly when `d` holds and imposes nothing when it does not — no second
 * factor and no per-pair literal. The false side needs `¬all_different` *enforced*, not merely detected,
 * so it names a witness: two index variables `p ≤ q` into the array and one value variable `w` with
 * `w = x(p)` and `w = x(q)`, plus `d ↔ p ≥ q`. With `p ≤ q` asserted, `d` true pins `p = q` and the
 * witness reads one cell twice (vacuous), while `d` false forces `p < q` at equal values — an actual
 * duplicate. Checking entailment instead would be unsound here: a state where the terms merely *happen*
 * to be distinct would satisfy `¬d`.
 *
 * Cost is 5 factors, 3 integer variables and 1 Boolean against the `n(n-1)/2` reified disequalities and
 * the Tseitin conjunction over them that the pairwise form emits; the value window the global indexes
 * must be finite, which is the caller's gate ([allDifferentWindowSize]).
 *
 * Propagation trades one direction for the other. The true side gains the matching-based domain filter
 * (J.-C. Régin, "A filtering algorithm for constraints of difference in CSPs", AAAI 1994) that pairwise
 * disequalities cannot express. The false side loses immediacy: two terms fixed equal no longer pin `d`
 * false by unit propagation, they refute the `d`-true branch one decision later. Deciding `¬all_different`
 * stays polynomial either way (C. Bessiere, E. Hebrard, B. Hnich, T. Walsh, "The Complexity of Global
 * Constraints", AAAI 2004), so nothing is lost beyond the delay.
 */
internal fun CnfLowering.reifyAllDifferentWitness(
    vars: IntArray,
    domainMin: Long,
    domainSize: Int,
    freshInt: (Long, Long) -> Int,
): Int {
    val n = vars.size
    require(n >= 2) { "reified all_different needs at least two terms" }
    val d = newBool()
    val dLit = Lit.make(d, positive = true)
    val lastPos = (n - 1).toLong()
    val p = freshInt(0L, lastPos)
    val q = freshInt(0L, lastPos)
    val witness = freshInt(domainMin, domainMin + (domainSize - 1))
    val cells = LongArray(n) { vars[it].toLong() }
    factors.add(AllDifferent(vars, domainMin, domainSize, presents = IntArray(n) { dLit }))
    factors.add(Element(p, witness, cells, arrIsVars = true, indexOffset = 0))
    factors.add(Element(q, witness, cells, arrIsVars = true, indexOffset = 0))
    factors.add(Linear(intArrayOf(1, -1), intArrayOf(p, q), LinearOp.LE, 0))
    factors.add(ReifiedLinear(d, intArrayOf(1, -1), intArrayOf(q, p), LinearOp.LE, 0))
    return dLit
}

/**
 * Smallest arity at which [reifyAllDifferentWitness] is the smaller encoding, from the emitted factor and
 * variable counts (load-independent, unlike a timing). Plain reification costs `2·n(n−1)/2 + 1` factors and
 * `n(n−1)/2 + 1` Boolean variables pairwise against a flat 5 factors and 4 variables: 3/2 against 5/4 at two
 * terms, 7/4 against 5/4 at three (a variable tie, and the witness variables are integer-valued, so they
 * branch wider than the Booleans they replace), then 13/7 against 5/4 at four. The presence-gated form
 * measures 37 factors / 15 variables against 21 / 16 at four terms and 61 / 23 against 25 / 19 at five, so
 * the same threshold trades one variable for sixteen factors at its low end.
 */
internal const val ALL_DIFFERENT_WITNESS_MIN_ARITY: Int = 4

/**
 * The `domainSize` an [AllDifferent] over the value window `[min, max]` indexes, or null when that window
 * is empty or wider than the `Int` the global is parameterised by — the caller then keeps pairwise.
 */
internal fun allDifferentWindowSize(min: Long, max: Long): Int? {
    if (min > max) return null
    if (min < 0L && max > Long.MAX_VALUE + min) return null
    val span = max - min
    if (span > Int.MAX_VALUE - 1L) return null
    return (span + 1L).toInt()
}
