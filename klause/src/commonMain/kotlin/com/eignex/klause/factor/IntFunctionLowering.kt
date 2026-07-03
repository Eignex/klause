package com.eignex.klause.factor

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import kotlin.math.abs

// Single source of truth for int_abs, int_min/max, int_div/mod: the schema and FlatZinc compilers
// used to encode these independently and could drift. Truncated-toward-zero throughout (MiniZinc
// semantics); nothing in the supported front-ends requires Euclidean.
internal object IntFunctionLowering {

    // result = |operand|: operand ≤ result ∧ −operand ≤ result ∧ (result = operand ∨ result = −operand).
    fun absFactors(operand: Int, result: Int, freshBool: () -> Int): List<Factor> {
        val pa = freshBool()
        val pb = freshBool()
        return listOf(
            Linear(intArrayOf(1, -1), intArrayOf(operand, result), LinearOp.LE, 0), // operand ≤ result
            Linear(intArrayOf(-1, -1), intArrayOf(operand, result), LinearOp.LE, 0), // −operand ≤ result
            ReifiedLinear(pa, intArrayOf(1, -1), intArrayOf(result, operand), LinearOp.EQ, 0), // pa ↔ result = operand
            ReifiedLinear(pb, intArrayOf(1, 1), intArrayOf(result, operand), LinearOp.EQ, 0), // pb ↔ result = −operand
            Clause(intArrayOf(Lit.make(pa, true), Lit.make(pb, true))),
        )
    }

    // The disjunction of reified equalities pins result to exactly one arg value; without it
    // result could float strictly above the max or below the min.
    fun minMaxFactors(result: Int, args: IntArray, isMax: Boolean, freshBool: () -> Int): List<Factor> {
        val out = ArrayList<Factor>(args.size * 2 + 1)
        for (arg in args) {
            out += if (isMax) {
                Linear(intArrayOf(1, -1), intArrayOf(arg, result), LinearOp.LE, 0) // arg ≤ result
            } else {
                Linear(intArrayOf(-1, 1), intArrayOf(arg, result), LinearOp.LE, 0) // result ≤ arg
            }
        }
        val eqLits = IntArray(args.size) { i ->
            val p = freshBool()
            out += ReifiedLinear(p, intArrayOf(1, -1), intArrayOf(result, args[i]), LinearOp.EQ, 0)
            Lit.make(p, true)
        }
        out += Clause(eqLits)
        return out
    }

    class TruncDivMod(val quotient: Int, val remainder: Int, val factors: List<Factor>)

    // b ≠ 0 is the caller's responsibility (left to propagation).
    fun truncatedDivMod(
        a: Int,
        b: Int,
        domainA: IntDomain,
        domainB: IntDomain,
        quotient: Int?,
        remainder: Int?,
        freshInt: (IntDomain) -> Int,
        freshBool: () -> Int,
    ): TruncDivMod {
        val out = ArrayList<Factor>()
        // Constant positive divisor over a non-negative dividend: truncated == floor, so the
        // whole relation is one linear `a = B·q + r` with tight aux domains. The general
        // encoding below posts a var·var product plus an |b| reification chain and gives q a
        // ±|a| span — half a million spurious q values per constraint on divisibility-grid models.
        if (domainB.min == domainB.max && domainB.min > 0 && domainA.min >= 0) {
            val bConst = domainB.min
            val q = quotient ?: freshInt(IntDomain(domainA.min / bConst, domainA.max / bConst))
            val rem = remainder ?: freshInt(IntDomain(0, bConst - 1))
            out += Linear(intArrayOf(1, -bConst, -1), intArrayOf(a, q, rem), LinearOp.EQ, 0)
            if (remainder != null) {
                // A supplied (FZN-declared) rem may carry a wider/signed domain — bound it.
                out += Linear(intArrayOf(1), intArrayOf(rem), LinearOp.GE, 0)
                out += Linear(intArrayOf(1), intArrayOf(rem), LinearOp.LE, bConst - 1)
            }
            return TruncDivMod(q, rem, out)
        }
        val bMag = maxOf(abs(domainB.min), abs(domainB.max))
        val aMag = maxOf(abs(domainA.min), abs(domainA.max))
        val qDomain = if (bMag == 0) IntDomain(-aMag, aMag) else IntDomain(-aMag - 1, aMag + 1)
        val q = quotient ?: freshInt(qDomain)
        val rem = remainder ?: freshInt(IntDomain(-bMag + 1, bMag - 1))
        val prod = freshInt(IntDomain(-aMag - bMag - 1, aMag + bMag + 1))
        out += Product(a = q, b = b, result = prod)
        out += Linear(intArrayOf(1, 1, -1), intArrayOf(prod, rem, a), LinearOp.EQ, 0)
        // |rem| < |b|, channeling |b| via an abs reification: absB ≥ b, absB ≥ −b, (absB = b ∨ absB = −b).
        val absB = freshInt(IntDomain(0, bMag))
        out += Linear(intArrayOf(1, -1), intArrayOf(b, absB), LinearOp.LE, 0)
        out += Linear(intArrayOf(-1, -1), intArrayOf(b, absB), LinearOp.LE, 0)
        val absBpa = freshBool()
        val absBpb = freshBool()
        out += ReifiedLinear(absBpa, intArrayOf(1, -1), intArrayOf(absB, b), LinearOp.EQ, 0)
        out += ReifiedLinear(absBpb, intArrayOf(1, 1), intArrayOf(absB, b), LinearOp.EQ, 0)
        out += Clause(intArrayOf(Lit.make(absBpa, true), Lit.make(absBpb, true)))
        out += Linear(intArrayOf(1, -1), intArrayOf(rem, absB), LinearOp.LE, -1) // rem ≤ |b| − 1
        out += Linear(intArrayOf(-1, -1), intArrayOf(rem, absB), LinearOp.LE, -1) // rem ≥ −|b| + 1
        // Truncated sign rule: rem > 0 → a ≥ 0; rem < 0 → a ≤ 0 (rem = 0 unconstrained).
        val remPos = freshBool()
        val remNeg = freshBool()
        out += ReifiedLinear(remPos, intArrayOf(1), intArrayOf(rem), LinearOp.GE, 1)
        out += ReifiedLinear(remNeg, intArrayOf(1), intArrayOf(rem), LinearOp.LE, -1)
        val aNonNeg = freshBool()
        val aNonPos = freshBool()
        out += ReifiedLinear(aNonNeg, intArrayOf(1), intArrayOf(a), LinearOp.GE, 0)
        out += ReifiedLinear(aNonPos, intArrayOf(1), intArrayOf(a), LinearOp.LE, 0)
        out += Clause(intArrayOf(Lit.make(remPos, false), Lit.make(aNonNeg, true))) // remPos → aNonNeg
        out += Clause(intArrayOf(Lit.make(remNeg, false), Lit.make(aNonPos, true))) // remNeg → aNonPos
        return TruncDivMod(q, rem, out)
    }
}
