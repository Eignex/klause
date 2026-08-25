package com.eignex.klause.lowering

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.table.Element
import com.eignex.klause.ir.Lit

// A shared presence literal gates the global on the true branch. The false branch witnesses equal values
// at two ordered positions, preserving `d ↔ all_different(vars)` without a quadratic pairwise encoding.
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

// Below four terms, pairwise reification has a smaller search footprint.
internal const val ALL_DIFFERENT_WITNESS_MIN_ARITY: Int = 4

// The global's indexed value window must fit an Int; callers retain the pairwise encoding otherwise.
internal fun allDifferentWindowSize(min: Long, max: Long): Int? {
    if (min > max) return null
    if (min < 0L && max > Long.MAX_VALUE + min) return null
    val span = max - min
    if (span > Int.MAX_VALUE - 1L) return null
    return (span + 1L).toInt()
}
