package com.eignex.klause.factor

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit

// Shared source relations for the schema and FlatZinc frontends.
internal object IntFunctionLowering {

    @Suppress("UNUSED_PARAMETER")
    fun absFactors(operand: Int, result: Int, freshBool: () -> Int): List<Factor> =
        listOf(com.eignex.klause.factor.arithmetic.Abs(operand, result))

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

    fun truncatedDivMod(
        a: Int,
        b: Int,
        domainA: IntDomain,
        domainB: IntDomain,
        quotient: Int?,
        remainder: Int?,
        freshInt: (IntDomain) -> Int,
        @Suppress("UNUSED_PARAMETER") freshBool: () -> Int,
        existing: List<Factor> = emptyList(),
    ): TruncDivMod {
        val relation = com.eignex.klause.lowering.retainDivMod(a, b, domainA, domainB,
            quotient, remainder, existing, freshInt)
        return TruncDivMod(relation.quotient, relation.remainder, relation.factors)
    }
}
