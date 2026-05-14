package com.eignex.klause.cnf

import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.factor.Clause
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.factor.Product
import com.eignex.klause.solver.factor.PseudoBoolean
import com.eignex.klause.solver.factor.ReifiedCardinality
import com.eignex.klause.solver.factor.ReifiedIntCompare
import com.eignex.klause.solver.factor.ReifiedLinear
import com.eignex.klause.solver.factor.ReifiedPseudoBoolean
import com.eignex.klause.solver.factor.Xor
import com.eignex.klause.solver.factor.AllDifferent as AllDifferentFactor
import com.eignex.klause.ast.IntCmpOp
import com.eignex.klause.ast.PbOp

/**
 * Compiles a [Problem] (mixed Boolean/integer factors) to propositional CNF using canonical
 * binary encoding for integer variables. Each integer variable in domain `[min, max]` gets
 * `ceil(log2(max - min + 1))` bits representing the offset from `min`; an explicit
 * `constantLeq` domain constraint is emitted when the domain size is not a power of two.
 *
 * Supported factor types: [Clause], [Cardinality] (only AtMostOne / AtLeastOne / ExactlyOne;
 * higher-k cardinality bounds raise), [Linear] (all four ops including NE),
 * [ReifiedIntCompare]. Out-of-domain `Linear` constants are short-circuited at compile time
 * to a true/false unit clause via [emitLinear].
 */
object BitBlaster {

    fun compile(problem: Problem): CnfProblem {
        val b = CnfBuilder()
        val boolMap = IntArray(problem.numBoolVars) { b.newVar() }
        val intBits = Array(problem.numIntVars) { i ->
            val d = problem.intDomains[i]
            val width = bitWidth(d.size - 1).coerceAtLeast(1)
            IntArray(width) { b.newVar() }
        }
        val intMin = IntArray(problem.numIntVars) { problem.intDomains[it].min }

        for (i in 0 until problem.numIntVars) {
            val bits = intBits[i]
            val d = problem.intDomains[i]
            val maxOffset = d.size - 1
            val capacity = if (bits.size >= 31) Int.MAX_VALUE else (1 shl bits.size) - 1
            if (maxOffset < capacity) {
                val ok = b.constantLeq(bitsToLits(bits), maxOffset)
                b.addClause(intArrayOf(ok))
            }
        }

        for (factor in problem.factors) {
            when (factor) {
                is Clause -> emitClause(b, factor.literals, boolMap)
                is Cardinality -> emitCardinality(b, factor, boolMap)
                is Linear -> emitLinear(b, factor, intBits, intMin, problem)
                is Product -> emitProduct(b, factor, intBits, intMin, problem)
                is PseudoBoolean -> emitPseudoBoolean(b, factor, boolMap)
                is ReifiedIntCompare -> emitReifiedIntCompare(b, factor, boolMap, intBits, intMin)
                is ReifiedLinear -> emitReifiedLinear(b, factor, boolMap, intBits, intMin)
                is ReifiedCardinality -> emitReifiedCardinality(b, factor, boolMap)
                is ReifiedPseudoBoolean -> emitReifiedPseudoBoolean(b, factor, boolMap)
                is Xor -> emitXor(b, factor, boolMap)
                is AllDifferentFactor -> emitAllDifferent(b, factor, intBits, intMin)
                else -> throw UnsupportedOperationException(
                    "BitBlaster cannot lower factor type ${factor::class.simpleName}"
                )
            }
        }

        return CnfProblem(
            numVars = b.numVars,
            clauses = b.clauses.toList(),
            boolVarToCnfVar = boolMap,
            intVarBits = intBits,
            intVarMin = intMin,
        )
    }

    private fun emitClause(b: CnfBuilder, literals: IntArray, boolMap: IntArray) {
        val out = IntArray(literals.size)
        for (i in literals.indices) {
            val lit = literals[i]
            val cnfVar = boolMap[Lit.variable(lit)]
            out[i] = Lit.make(cnfVar, Lit.isPositive(lit))
        }
        b.addClause(out)
    }

    private fun emitCardinality(b: CnfBuilder, c: Cardinality, boolMap: IntArray) {
        val remapped = IntArray(c.literals.size) {
            val lit = c.literals[it]
            Lit.make(boolMap[Lit.variable(lit)], Lit.isPositive(lit))
        }
        if (c.max < c.literals.size) emitAtMostK(b, remapped, c.max)
        if (c.min > 0) {
            // at-least-min over lits ⟺ at-most-(n-min) over negated lits.
            val negated = IntArray(remapped.size) { Lit.negate(remapped[it]) }
            emitAtMostK(b, negated, remapped.size - c.min)
        }
    }

    /** Sinz 2005 sequential-counter encoding of `at-most-k`. Falls back to specialised forms
     *  for the corner cases (k=0, k=n-1, small pairwise). */
    private fun emitAtMostK(b: CnfBuilder, lits: IntArray, k: Int) {
        val n = lits.size
        if (k >= n) return
        if (k == 0) {
            for (l in lits) b.addClause(intArrayOf(Lit.negate(l)))
            return
        }
        if (k == n - 1) {
            val cl = IntArray(n) { Lit.negate(lits[it]) }
            b.addClause(cl)
            return
        }
        if (k == 1 && n <= 4) {
            for (i in 0 until n) for (j in i + 1 until n) {
                b.addClause(intArrayOf(Lit.negate(lits[i]), Lit.negate(lits[j])))
            }
            return
        }
        val s = Array(n - 1) { IntArray(k) { Lit.make(b.newVar(), positive = true) } }
        b.addClause(intArrayOf(Lit.negate(lits[0]), s[0][0]))
        for (j in 1 until k) b.addClause(intArrayOf(Lit.negate(s[0][j])))
        for (i in 1 until n - 1) {
            b.addClause(intArrayOf(Lit.negate(lits[i]), s[i][0]))
            b.addClause(intArrayOf(Lit.negate(s[i - 1][0]), s[i][0]))
            for (j in 1 until k) {
                b.addClause(intArrayOf(Lit.negate(lits[i]), Lit.negate(s[i - 1][j - 1]), s[i][j]))
                b.addClause(intArrayOf(Lit.negate(s[i - 1][j]), s[i][j]))
            }
            b.addClause(intArrayOf(Lit.negate(lits[i]), Lit.negate(s[i - 1][k - 1])))
        }
        b.addClause(intArrayOf(Lit.negate(lits[n - 1]), Lit.negate(s[n - 2][k - 1])))
    }

    private fun emitAllDifferent(
        b: CnfBuilder,
        f: AllDifferentFactor,
        intBits: Array<IntArray>,
        intMin: IntArray,
    ) {
        // Pairwise NE via offset-shifted bit-vector equality. For two vars i, j with possibly
        // different domains, compare their actual values: i_actual = iMin + decode(iBits) and
        // similarly for j. NE on actuals ⟺ NE on (iBits + (iMin - jMin)) and jBits when both
        // shifted to a common reference. Simpler: rebuild both as canonical bit-vectors of equal
        // width via a small offset add, then assert unsignedEq is false.
        for (i in f.vars.indices) for (j in i + 1 until f.vars.size) {
            val a = f.vars[i]; val c = f.vars[j]
            val aMin = intMin[a]; val cMin = intMin[c]
            val aLits = bitsToLits(intBits[a])
            val cLits = bitsToLits(intBits[c])
            // Shift the smaller-min side up so both reference the same zero. Both shifts produce
            // non-negative values since aMin / cMin can be any integer; pick the lower as base.
            val base = minOf(aMin, cMin)
            val aShifted = if (aMin == base) aLits else b.rippleAdd(aLits, constantLits(b, (aMin - base).toLong()))
            val cShifted = if (cMin == base) cLits else b.rippleAdd(cLits, constantLits(b, (cMin - base).toLong()))
            b.addClause(intArrayOf(Lit.negate(b.unsignedEq(aShifted, cShifted))))
        }
    }

    private fun emitXor(b: CnfBuilder, f: Xor, boolMap: IntArray) {
        val remapped = IntArray(f.literals.size) {
            val lit = f.literals[it]
            Lit.make(boolMap[Lit.variable(lit)], Lit.isPositive(lit))
        }
        // Chain pairwise XOR to combine all literals into a single parity literal.
        var acc = remapped[0]
        for (i in 1 until remapped.size) acc = b.tseitinXor(acc, remapped[i])
        // Assert parity matches the target.
        b.addClause(intArrayOf(if (f.targetParity == 1) acc else Lit.negate(acc)))
    }

    private fun emitPseudoBoolean(b: CnfBuilder, f: PseudoBoolean, boolMap: IntArray) {
        b.addClause(intArrayOf(buildPbComparator(b, f.weights, f.literals, f.op, f.bound, boolMap)))
    }

    private fun emitReifiedPseudoBoolean(b: CnfBuilder, f: ReifiedPseudoBoolean, boolMap: IntArray) {
        val cnfAux = Lit.make(boolMap[f.auxBoolVar], positive = true)
        val cmp = buildPbComparator(b, f.weights, f.literals, f.op, f.bound, boolMap)
        b.addClause(intArrayOf(Lit.negate(cnfAux), cmp))
        b.addClause(intArrayOf(cnfAux, Lit.negate(cmp)))
    }

    private fun buildPbComparator(
        b: CnfBuilder,
        weights: IntArray,
        literals: IntArray,
        op: PbOp,
        bound: Int,
        boolMap: IntArray,
    ): Int {
        // Split positive- and negative-weight terms; bound shifts to absorb negative contributions.
        // Σ wᵢ * lᵢ = posSum − negSum, where posSum / negSum are non-negative.
        val posTerms = mutableListOf<IntArray>()
        val negTerms = mutableListOf<IntArray>()
        for (i in literals.indices) {
            val w = weights[i]
            if (w == 0) continue
            val lit = literals[i]
            val cnfLit = Lit.make(boolMap[Lit.variable(lit)], Lit.isPositive(lit))
            val term = b.multiplyByConstant(intArrayOf(cnfLit), kotlin.math.abs(w))
            if (w > 0) posTerms += term else negTerms += term
        }
        val pSum = sumAll(b, posTerms)
        val nSum = sumAll(b, negTerms)
        val lhs: IntArray
        val rhs: IntArray
        if (bound >= 0) {
            lhs = pSum
            rhs = if (bound > 0) b.rippleAdd(nSum, constantLits(b, bound.toLong())) else nSum
        } else {
            lhs = b.rippleAdd(pSum, constantLits(b, (-bound).toLong()))
            rhs = nSum
        }
        return when (op) {
            PbOp.LE -> b.unsignedLeq(lhs, rhs)
            PbOp.GE -> b.unsignedLeq(rhs, lhs)
            PbOp.EQ -> b.unsignedEq(lhs, rhs)
        }
    }

    private fun emitProduct(
        b: CnfBuilder,
        f: Product,
        intBits: Array<IntArray>,
        intMin: IntArray,
        problem: Problem,
    ) {
        val aMin = intMin[f.a]; val bMin = intMin[f.b]; val rMin = intMin[f.result]
        val aMax = problem.intDomains[f.a].max
        val bMax = problem.intDomains[f.b].max
        val rMax = problem.intDomains[f.result].max

        if (aMin >= 0 && bMin >= 0 && rMin >= 0) {
            // Unsigned fast path.
            val aActual = if (aMin == 0) bitsToLits(intBits[f.a])
                else b.rippleAdd(bitsToLits(intBits[f.a]), constantLits(b, aMin.toLong()))
            val bActual = if (bMin == 0) bitsToLits(intBits[f.b])
                else b.rippleAdd(bitsToLits(intBits[f.b]), constantLits(b, bMin.toLong()))
            val rActual = if (rMin == 0) bitsToLits(intBits[f.result])
                else b.rippleAdd(bitsToLits(intBits[f.result]), constantLits(b, rMin.toLong()))
            val product = b.multiply(aActual, bActual)
            b.addClause(intArrayOf(b.unsignedEq(product, rActual)))
            return
        }

        // Signed path: build two's-complement bit-vectors wide enough to hold each operand's
        // signed range, then use signedMultiply.
        val aWidth = signedWidth(aMin, aMax)
        val bWidth = signedWidth(bMin, bMax)
        val rWidth = signedWidth(rMin, rMax)
        val aSigned = toSignedBits(b, intBits[f.a], aMin, aWidth)
        val bSigned = toSignedBits(b, intBits[f.b], bMin, bWidth)
        val rSigned = toSignedBits(b, intBits[f.result], rMin, rWidth)
        val product = b.signedMultiply(aSigned, bSigned)
        // Sign-extend whichever side is shorter so unsignedEq can compare bit-for-bit.
        val targetWidth = maxOf(product.size, rSigned.size)
        val productExt = signExtend(b, product, targetWidth)
        val rExt = signExtend(b, rSigned, targetWidth)
        b.addClause(intArrayOf(b.unsignedEq(productExt, rExt)))
    }

    /** Bits needed for two's-complement representation of a value range `[min, max]`. */
    private fun signedWidth(min: Int, max: Int): Int {
        val maxAbs = maxOf(if (min < 0) -min - 1 else 0, if (max > 0) max else 0)
        // 1 sign bit + bits to represent maxAbs.
        val payload = if (maxAbs == 0) 1 else (32 - maxAbs.countLeadingZeroBits())
        return payload + 1
    }

    /**
     * Build a [width]-bit two's-complement representation of `min + decode(bits)`. The integer
     * variable is encoded as offset bits with offset `min`; this lifts that to a signed
     * bit-vector by zero-extending the offset bits and adding `min` modulo `2^width`.
     */
    private fun toSignedBits(b: CnfBuilder, bits: IntArray, min: Int, width: Int): IntArray {
        val lits = bitsToLits(bits)
        val zeroExtended = b.zeroExtend(lits, width)
        if (min == 0) return zeroExtended
        val constMod = if (min >= 0) min.toLong() else (1L shl width) + min.toLong()
        val sum = b.rippleAdd(zeroExtended, constantLits(b, constMod))
        // Truncate to `width`; overflow bits are discarded (intended in two's complement).
        return sum.copyOfRange(0, width)
    }

    /** Sign-extend a two's-complement bit-vector to [width]. */
    private fun signExtend(b: CnfBuilder, bits: IntArray, width: Int): IntArray {
        if (bits.size >= width) return bits.copyOfRange(0, width)
        val sign = bits.last()
        val out = IntArray(width)
        bits.copyInto(out, 0, 0, bits.size)
        for (i in bits.size until width) out[i] = sign
        return out
    }

    private fun emitLinear(b: CnfBuilder, f: Linear, intBits: Array<IntArray>, intMin: IntArray, problem: Problem) {
        // Short-circuit single-var equality / disequality against an out-of-domain constant,
        // matching the old IntEq / IntNeq behavior.
        if (f.coeffs.size == 1 && f.coeffs[0] == 1) {
            val v = f.vars[0]
            val d = problem.intDomains[v]
            val inDomain = f.bound in d
            when (f.op) {
                LinearOp.EQ -> if (!inDomain) { b.addClause(IntArray(0)); return }
                LinearOp.NE -> if (!inDomain) return  // trivially true
                else -> Unit
            }
        }
        b.addClause(intArrayOf(buildLinearComparator(b, f.coeffs, f.vars, f.op, f.bound, intBits, intMin)))
    }

    private fun buildLinearComparator(
        b: CnfBuilder,
        coeffs: IntArray,
        vars: IntArray,
        op: LinearOp,
        bound: Int,
        intBits: Array<IntArray>,
        intMin: IntArray,
    ): Int {
        // Substitute x_i = x_i' + min_i; new bound b' = bound - Σ c_i min_i.
        var bPrime: Long = bound.toLong()
        for (i in vars.indices) bPrime -= coeffs[i].toLong() * intMin[vars[i]].toLong()

        // Split positive and negative contributions, both as non-negative coefficient × x_i'.
        val posTerms = mutableListOf<IntArray>()
        val negTerms = mutableListOf<IntArray>()
        for (i in vars.indices) {
            val c = coeffs[i]
            if (c == 0) continue
            val term = b.multiplyByConstant(bitsToLits(intBits[vars[i]]), kotlin.math.abs(c))
            if (c > 0) posTerms += term else negTerms += term
        }
        val pSum = sumAll(b, posTerms)
        val nSum = sumAll(b, negTerms)

        // Rearrange so both sides are non-negative: lhs op rhs.
        val lhs: IntArray
        val rhs: IntArray
        if (bPrime >= 0) {
            lhs = pSum
            rhs = if (bPrime > 0) b.rippleAdd(nSum, constantLits(b, bPrime)) else nSum
        } else {
            lhs = b.rippleAdd(pSum, constantLits(b, -bPrime))
            rhs = nSum
        }

        return when (op) {
            LinearOp.LE -> b.unsignedLeq(lhs, rhs)
            LinearOp.GE -> b.unsignedLeq(rhs, lhs)
            LinearOp.EQ -> b.unsignedEq(lhs, rhs)
            LinearOp.NE -> Lit.negate(b.unsignedEq(lhs, rhs))
        }
    }

    private fun emitReifiedCardinality(b: CnfBuilder, f: ReifiedCardinality, boolMap: IntArray) {
        val cnfAux = Lit.make(boolMap[f.auxBoolVar], positive = true)
        val remapped = IntArray(f.literals.size) {
            val lit = f.literals[it]
            Lit.make(boolMap[Lit.variable(lit)], Lit.isPositive(lit))
        }
        // Sum each 0/1-valued literal into a count bitvector.
        var sum = if (remapped.isEmpty()) intArrayOf(b.falseLit()) else intArrayOf(remapped[0])
        for (i in 1 until remapped.size) sum = b.rippleAdd(sum, intArrayOf(remapped[i]))
        val parts = mutableListOf<Int>()
        if (f.min > 0) parts += b.constantGeq(sum, f.min)
        if (f.max < f.literals.size) parts += b.constantLeq(sum, f.max)
        val inRangeLit = if (parts.isEmpty()) b.trueLit()
        else if (parts.size == 1) parts[0]
        else b.tseitinAnd(parts.toIntArray())
        b.addClause(intArrayOf(Lit.negate(cnfAux), inRangeLit))
        b.addClause(intArrayOf(cnfAux, Lit.negate(inRangeLit)))
    }

    private fun emitReifiedLinear(
        b: CnfBuilder,
        f: ReifiedLinear,
        boolMap: IntArray,
        intBits: Array<IntArray>,
        intMin: IntArray,
    ) {
        val cnfAux = Lit.make(boolMap[f.auxBoolVar], positive = true)
        val cmp = buildLinearComparator(b, f.coeffs, f.vars, f.op, f.bound, intBits, intMin)
        b.addClause(intArrayOf(Lit.negate(cnfAux), cmp))
        b.addClause(intArrayOf(cnfAux, Lit.negate(cmp)))
    }

    private fun emitReifiedIntCompare(
        b: CnfBuilder,
        f: ReifiedIntCompare,
        boolMap: IntArray,
        intBits: Array<IntArray>,
        intMin: IntArray,
    ) {
        val cnfAux = Lit.make(boolMap[f.auxBoolVar], positive = true)
        val bits = bitsToLits(intBits[f.intVar])
        val offset = f.bound - intMin[f.intVar]
        val cmp = when (f.op) {
            IntCmpOp.LE -> b.constantLeq(bits, offset)
            IntCmpOp.LT -> b.constantLeq(bits, offset - 1)
            IntCmpOp.GE -> b.constantGeq(bits, offset)
            IntCmpOp.GT -> b.constantGeq(bits, offset + 1)
            IntCmpOp.EQ -> b.constantEq(bits, offset)
            IntCmpOp.NE -> b.constantNeq(bits, offset)
        }
        // aux ↔ cmp:  (¬aux ∨ cmp) ∧ (aux ∨ ¬cmp).
        b.addClause(intArrayOf(Lit.negate(cnfAux), cmp))
        b.addClause(intArrayOf(cnfAux, Lit.negate(cmp)))
    }

    private fun sumAll(b: CnfBuilder, terms: List<IntArray>): IntArray {
        if (terms.isEmpty()) return intArrayOf(b.falseLit())
        var acc = terms[0]
        for (i in 1 until terms.size) acc = b.rippleAdd(acc, terms[i])
        return acc
    }

    private fun constantLits(b: CnfBuilder, value: Long): IntArray {
        require(value >= 0) { "constantLits expects non-negative, got $value" }
        if (value == 0L) return intArrayOf(b.falseLit())
        val width = 64 - value.countLeadingZeroBits()
        val out = IntArray(width)
        val tL = b.trueLit()
        val fL = b.falseLit()
        for (i in 0 until width) out[i] = if ((value ushr i) and 1L == 1L) tL else fL
        return out
    }

    private fun bitsToLits(bits: IntArray): IntArray {
        val out = IntArray(bits.size)
        for (i in bits.indices) out[i] = Lit.make(bits[i], positive = true)
        return out
    }

    private fun bitWidth(value: Int): Int =
        if (value <= 0) 1 else 32 - value.countLeadingZeroBits()
}
