package com.eignex.klause.solver.factor

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import kotlin.math.abs

/**
 * Shared factor-level encodings for the functional integer builtins that both front-ends
 * must lower the same way: `int_abs` and `int_min`/`int_max`. The schema compiler
 * ([com.eignex.klause.compile.Lowering]) and the FlatZinc compiler used to hand-build these
 * factors independently; this is the single source of truth so the two can't drift.
 *
 * Each function takes resolved integer-variable ids plus a `freshBool` allocator (the two
 * compilers number their auxiliary Booleans differently) and returns the replacement
 * factors over those vars. The caller is responsible for having declared `result` with an
 * adequate domain — these encodings constrain it but do not size it.
 *
 * `int_div`/`int_mod` are also here ([truncatedDivMod]). klause standardizes on MiniZinc's
 * truncated-toward-zero semantics for every front-end: SMT-LIB (which would want Euclidean)
 * rejects div/mod as nonlinear and XCSP3 doesn't build them, so nothing requires the old
 * Euclidean variant — one truncated encoding now serves both the schema DSL and FlatZinc.
 */
internal object IntFunctionLowering {

    /**
     * `result = |operand|`: `operand ≤ result` and `−operand ≤ result` (so `result ≥ |operand|`,
     * and `result ≥ 0` follows), plus `(result = operand) ∨ (result = −operand)` to pin it to
     * the absolute value exactly.
     */
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

    /**
     * `result = max(args)` (when [isMax]) or `result = min(args)`. For max: `result ≥ arg` for
     * every arg, plus `result` equals at least one arg; for min the bound flips to `result ≤ arg`.
     * The "equals at least one" half is a disjunction of reified equalities, which is what makes
     * the bound tight (otherwise `result` could float strictly above the max / below the min).
     */
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

    /** The vars and factors produced by [truncatedDivMod]: the quotient and remainder var
     *  ids (whichever the caller passed through, or the ones freshly allocated) plus the
     *  encoding [factors]. */
    class TruncDivMod(val quotient: Int, val remainder: Int, val factors: List<Factor>)

    /**
     * Truncated-toward-zero `q = a / b`, `rem = a − b·q` — MiniZinc's `int_div`/`int_mod`
     * semantics: `|rem| < |b|` and `rem` takes the sign of the dividend `a`. Pass the
     * caller-owned [quotient] / [remainder] var when it already exists (an FZN-declared
     * result, or the value the caller wants back); pass `null` to have one minted via
     * [freshInt]. [domainA] / [domainB] are the current domains of [a] / [b] and size the
     * fresh aux vars. `b ≠ 0` is the caller's responsibility (left to propagation here).
     */
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
        // q · b = prod, then prod + rem = a.
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
