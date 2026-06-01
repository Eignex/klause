package com.eignex.klause.cnf

import com.eignex.klause.solver.Lit

/**
 * Accumulates clauses while building Tseitin CNF expansions and bit-vector circuits. All
 * variable ids and literals use the same MiniSAT encoding as the rest of klause:
 * `lit = (variable shl 1) or 1` for negated.
 *
 * Bit vectors are passed as [IntArray]s of literals, LSB at index 0.
 */
class CnfBuilder {

    private val _clauses = mutableListOf<IntArray>()
    private var _numVars = 0
    private var cachedFalseLit: Int = -1
    private var cachedTrueLit: Int = -1

    /** The accumulated clauses. */
    val clauses: List<IntArray> get() = _clauses

    /** Number of allocated CNF variables. */
    val numVars: Int get() = _numVars

    /** Allocate a fresh CNF variable id. */
    fun newVar(): Int = _numVars++

    /** Add a clause (disjunction of literals). */
    fun addClause(lits: IntArray) {
        _clauses.add(lits)
    }

    // ---- Gate hash-consing -------------------------------------------------------------
    // Structurally-identical Tseitin gates share one aux var + definition. Bit-blasted
    // arithmetic re-derives the same sub-gates constantly (every comparator XNORs the same
    // bit pairs, every AllDifferent pair compares the same vars), so consing the primitive
    // gates collapses the duplication — and because equality/adder results are themselves
    // built from these gates over identical inputs, the sharing propagates all the way up
    // (`unsignedEq(a, b)` called twice returns the same literal). Keys are canonical: AND/OR
    // dedup + sort their inputs; XOR strips operand polarity (tracking output parity) and
    // sorts; MAJ3 sorts. The aux↔gate definition is global, so reusing a cached literal in a
    // new context is always sound.

    private class GateKey(val op: Int, val operands: IntArray) {
        override fun equals(other: Any?): Boolean =
            other is GateKey && other.op == op && other.operands.contentEquals(operands)
        override fun hashCode(): Int = op * 31 + operands.contentHashCode()
    }

    private val gateCache = HashMap<GateKey, Int>()

    private companion object {
        const val OP_AND = 0
        const val OP_OR = 1
        const val OP_XOR = 2
        const val OP_MAJ = 3
    }

    /** Sort + drop exact-duplicate literals. */
    private fun sortDedup(a: IntArray): IntArray {
        if (a.size <= 1) return a
        val s = a.copyOf()
        s.sort()
        var w = 0
        for (i in s.indices) if (w == 0 || s[i] != s[w - 1]) s[w++] = s[i]
        return if (w == s.size) s else s.copyOf(w)
    }

    /** True if a sorted literal array contains some `l` and `¬l` (they're adjacent — a
     *  positive literal `v shl 1` and its negation `(v shl 1) or 1` differ only in bit 0). */
    private fun hasComplementaryPair(sorted: IntArray): Boolean {
        for (i in 0 until sorted.size - 1) if (sorted[i] xor sorted[i + 1] == 1) return true
        return false
    }

    /** A literal that is always false (allocated lazily). */
    fun falseLit(): Int {
        if (cachedFalseLit == -1) {
            val v = newVar()
            // Unit clause `¬v` forces v=false; positive literal of v evaluates to false.
            addClause(intArrayOf(Lit.make(v, positive = false)))
            cachedFalseLit = Lit.make(v, positive = true)
            cachedTrueLit = Lit.make(v, positive = false)
        }
        return cachedFalseLit
    }

    /** A literal that is always true (allocated lazily). */
    fun trueLit(): Int {
        if (cachedTrueLit == -1) falseLit()
        return cachedTrueLit
    }

    /** Literal `aux` such that `aux ↔ AND(inputs)`. Hash-consed. */
    fun tseitinAnd(inputs: IntArray): Int {
        if (inputs.size == 1) return inputs[0]
        if (inputs.isEmpty()) return trueLit()
        val canon = sortDedup(inputs)
        if (canon.size == 1) return canon[0]
        if (hasComplementaryPair(canon)) return falseLit()
        val key = GateKey(OP_AND, canon)
        gateCache[key]?.let { return it }
        val aux = Lit.make(newVar(), positive = true)
        for (l in canon) addClause(intArrayOf(Lit.negate(aux), l))
        val big = IntArray(canon.size + 1)
        big[0] = aux
        for (i in canon.indices) big[i + 1] = Lit.negate(canon[i])
        addClause(big)
        gateCache[key] = aux
        return aux
    }

    /** Literal `aux` such that `aux ↔ OR(inputs)`. Hash-consed. */
    fun tseitinOr(inputs: IntArray): Int {
        if (inputs.size == 1) return inputs[0]
        if (inputs.isEmpty()) return falseLit()
        val canon = sortDedup(inputs)
        if (canon.size == 1) return canon[0]
        if (hasComplementaryPair(canon)) return trueLit()
        val key = GateKey(OP_OR, canon)
        gateCache[key]?.let { return it }
        val aux = Lit.make(newVar(), positive = true)
        for (l in canon) addClause(intArrayOf(Lit.negate(l), aux))
        val big = IntArray(canon.size + 1)
        big[0] = Lit.negate(aux)
        for (i in canon.indices) big[i + 1] = canon[i]
        addClause(big)
        gateCache[key] = aux
        return aux
    }

    /** Literal `aux` such that `aux ↔ a XOR b`. Hash-consed: operand polarities are stripped
     *  (XOR is invariant under flipping both, and `¬a ⊕ b = ¬(a ⊕ b)`), so the cache keys on
     *  the underlying variables and the output is re-negated for an odd number of negated
     *  operands. Four clauses on a miss. */
    fun tseitinXor(a: Int, b: Int): Int {
        var parity = 0
        var x = a
        var y = b
        if (!Lit.isPositive(x)) {
            parity = parity xor 1
            x = Lit.negate(x)
        }
        if (!Lit.isPositive(y)) {
            parity = parity xor 1
            y = Lit.negate(y)
        }
        if (x == y) return if (parity == 1) trueLit() else falseLit() // a⊕a=0, ¬a⊕a=1
        val lo = if (x <= y) x else y
        val hi = if (x <= y) y else x
        val key = GateKey(OP_XOR, intArrayOf(lo, hi))
        val base = gateCache[key] ?: run {
            val aux = Lit.make(newVar(), positive = true)
            addClause(intArrayOf(Lit.negate(aux), lo, hi))
            addClause(intArrayOf(Lit.negate(aux), Lit.negate(lo), Lit.negate(hi)))
            addClause(intArrayOf(aux, Lit.negate(lo), hi))
            addClause(intArrayOf(aux, lo, Lit.negate(hi)))
            gateCache[key] = aux
            aux
        }
        return if (parity == 1) Lit.negate(base) else base
    }

    /** `aux ↔ a XOR b XOR c`. Used as the sum bit of a full adder. */
    fun tseitinXor3(a: Int, b: Int, c: Int): Int = tseitinXor(tseitinXor(a, b), c)

    /** `aux ↔ majority(a, b, c)`. Carry bit of a full adder. Hash-consed (sorted operands). */
    fun tseitinMaj3(a: Int, b: Int, c: Int): Int {
        val ops = intArrayOf(a, b, c)
        ops.sort()
        val key = GateKey(OP_MAJ, ops)
        gateCache[key]?.let { return it }
        val aux = Lit.make(newVar(), positive = true)
        addClause(intArrayOf(Lit.negate(aux), a, b))
        addClause(intArrayOf(Lit.negate(aux), a, c))
        addClause(intArrayOf(Lit.negate(aux), b, c))
        addClause(intArrayOf(aux, Lit.negate(a), Lit.negate(b)))
        addClause(intArrayOf(aux, Lit.negate(a), Lit.negate(c)))
        addClause(intArrayOf(aux, Lit.negate(b), Lit.negate(c)))
        gateCache[key] = aux
        return aux
    }

    /** Pad [bits] with constant-false literals up to [width]. No-op if already wide enough. */
    fun zeroExtend(bits: IntArray, width: Int): IntArray {
        if (bits.size >= width) return bits
        val out = IntArray(width)
        bits.copyInto(out, 0, 0, bits.size)
        for (i in bits.size until width) out[i] = falseLit()
        return out
    }

    /** Logical left-shift by [k]: new low bits filled with constant-false literals. */
    fun shiftLeft(bits: IntArray, k: Int): IntArray {
        if (k == 0) return bits
        val out = IntArray(bits.size + k)
        val falseL = falseLit()
        for (i in 0 until k) out[i] = falseL
        bits.copyInto(out, k, 0, bits.size)
        return out
    }

    /** Unsigned ripple-carry add. Output width is `max(a.size, b.size) + 1` (carry-out). */
    fun rippleAdd(a: IntArray, b: IntArray): IntArray {
        val n = maxOf(a.size, b.size)
        val ax = zeroExtend(a, n)
        val bx = zeroExtend(b, n)
        val out = IntArray(n + 1)
        var carry = falseLit()
        for (i in 0 until n) {
            out[i] = tseitinXor3(ax[i], bx[i], carry)
            carry = tseitinMaj3(ax[i], bx[i], carry)
        }
        out[n] = carry
        return out
    }

    /** Bit-wise mux: each output bit is `sel ? a[i] : b[i]`. Both inputs must have equal width. */
    fun mux(sel: Int, a: IntArray, b: IntArray): IntArray {
        require(a.size == b.size) { "mux: arms must have equal width" }
        val out = IntArray(a.size)
        for (i in a.indices) {
            // sel ? a : b  =  (sel ∧ a) ∨ (¬sel ∧ b)
            val left = tseitinAnd(intArrayOf(sel, a[i]))
            val right = tseitinAnd(intArrayOf(Lit.negate(sel), b[i]))
            out[i] = tseitinOr(intArrayOf(left, right))
        }
        return out
    }

    /**
     * Two's-complement negation of [bits]: invert every bit and add 1. Output width matches
     * input. Note that negating the most-negative value overflows; callers should size their
     * inputs to leave room.
     */
    fun negateBv(bits: IntArray): IntArray {
        val inverted = IntArray(bits.size) { Lit.negate(bits[it]) }
        // Add 1 by feeding a true literal as the initial carry into a half-adder chain.
        val out = IntArray(bits.size)
        var carry = trueLit()
        for (i in bits.indices) {
            out[i] = tseitinXor(inverted[i], carry)
            carry = tseitinAnd(intArrayOf(inverted[i], carry))
        }
        return out
    }

    /**
     * Signed multiplier via sign-magnitude: `out = a * b` where both inputs are interpreted as
     * two's-complement values. Sign-extends each operand by one bit before extracting the sign
     * bit and forming the absolute value, so that the most-negative input (e.g. `1000` in 4
     * bits = -8) does not overflow `negateBv`. Output width is `a.size + b.size + 3`, signed.
     */
    fun signedMultiply(a: IntArray, b: IntArray): IntArray {
        require(a.isNotEmpty() && b.isNotEmpty()) { "signedMultiply: empty operand" }
        val aExt = signExtendOne(a)
        val bExt = signExtendOne(b)
        val signA = aExt.last()
        val signB = bExt.last()
        val absA = mux(signA, negateBv(aExt), aExt)
        val absB = mux(signB, negateBv(bExt), bExt)
        val product = multiply(absA, absB)
        // Sign of result is signA XOR signB.
        val resultSign = tseitinXor(signA, signB)
        // Pad product by one bit so the negation can express the most-negative value.
        val padded = zeroExtend(product, product.size + 1)
        return mux(resultSign, negateBv(padded), padded)
    }

    /** Sign-extend a two's-complement bit-vector by one bit; new MSB replicates the sign. */
    private fun signExtendOne(bits: IntArray): IntArray {
        val out = IntArray(bits.size + 1)
        bits.copyInto(out, 0, 0, bits.size)
        out[bits.size] = bits.last()
        return out
    }

    /**
     * Unsigned shift-and-add multiplier: `out = a * b`. Output width is `a.size + b.size`.
     * For each bit `b[i]`, conditionally adds `a << i` to the running total: `(b[i] AND a[j])`
     * forms each partial-product bit via `tseitinAnd`.
     */
    fun multiply(a: IntArray, b: IntArray): IntArray {
        if (a.isEmpty() || b.isEmpty()) return intArrayOf(falseLit())
        val width = a.size + b.size
        var acc = intArrayOf(falseLit())
        for (i in b.indices) {
            val partial = IntArray(a.size) { j -> tseitinAnd(intArrayOf(b[i], a[j])) }
            val shifted = shiftLeft(partial, i)
            acc = rippleAdd(acc, shifted)
        }
        // Truncate / extend to the canonical product width.
        return if (acc.size >= width) acc.copyOfRange(0, width) else zeroExtend(acc, width)
    }

    /** Multiply [bits] by a non-negative integer constant via shift-and-add. */
    fun multiplyByConstant(bits: IntArray, k: Int): IntArray {
        require(k >= 0) { "multiplyByConstant: k must be non-negative, got $k" }
        if (k == 0) return intArrayOf(falseLit())
        if (k == 1) return bits
        var result = intArrayOf(falseLit())
        var shift = 0
        var rem = k
        while (rem > 0) {
            if (rem and 1 != 0) {
                result = rippleAdd(result, shiftLeft(bits, shift))
            }
            shift++
            rem = rem ushr 1
        }
        return result
    }

    /** `aux ↔ (bits == constant)` where bits are unsigned LSB-first. */
    fun constantEq(bits: IntArray, constant: Int): Int {
        if (constant < 0) return falseLit()
        // If the constant has bits set beyond bits.size, equality is impossible.
        if (bits.size < 31 && constant ushr bits.size != 0) return falseLit()
        val needed = IntArray(bits.size)
        for (i in bits.indices) {
            val target = (constant ushr i) and 1
            needed[i] = if (target == 1) bits[i] else Lit.negate(bits[i])
        }
        return tseitinAnd(needed)
    }

    /** `aux ↔ (bits != constant)`. */
    fun constantNeq(bits: IntArray, constant: Int): Int = Lit.negate(constantEq(bits, constant))

    /** `aux ↔ (bits ≤ constant)` where bits are unsigned LSB-first. */
    fun constantLeq(bits: IntArray, constant: Int): Int {
        if (constant < 0) return falseLit()
        val maxRepresentable = if (bits.size >= 31) Int.MAX_VALUE else (1 shl bits.size) - 1
        if (constant >= maxRepresentable) return trueLit()
        var equalSoFar = trueLit()
        var lessSoFar = falseLit()
        for (i in bits.indices.reversed()) {
            val cBit = (constant ushr i) and 1
            if (cBit == 1) {
                val cur = tseitinAnd(intArrayOf(equalSoFar, Lit.negate(bits[i])))
                lessSoFar = tseitinOr(intArrayOf(lessSoFar, cur))
                equalSoFar = tseitinAnd(intArrayOf(equalSoFar, bits[i]))
            } else {
                equalSoFar = tseitinAnd(intArrayOf(equalSoFar, Lit.negate(bits[i])))
            }
        }
        return tseitinOr(intArrayOf(lessSoFar, equalSoFar))
    }

    /** `aux ↔ (bits ≥ constant)`. */
    fun constantGeq(bits: IntArray, constant: Int): Int {
        if (constant <= 0) return trueLit()
        // bits ≥ constant ⟺ NOT (bits ≤ constant - 1)
        return Lit.negate(constantLeq(bits, constant - 1))
    }

    /** `aux ↔ (a ≤ b)`, both unsigned LSB-first. Bit widths may differ. */
    fun unsignedLeq(a: IntArray, b: IntArray): Int {
        val n = maxOf(a.size, b.size)
        val ax = zeroExtend(a, n)
        val bx = zeroExtend(b, n)
        var equalSoFar = trueLit()
        var lessSoFar = falseLit()
        for (i in (n - 1) downTo 0) {
            // less-at-i: a[i]=0 and b[i]=1
            val lessAtI = tseitinAnd(intArrayOf(Lit.negate(ax[i]), bx[i]))
            val cur = tseitinAnd(intArrayOf(equalSoFar, lessAtI))
            lessSoFar = tseitinOr(intArrayOf(lessSoFar, cur))
            // equal-at-i: a[i]=b[i] which is NOT (a[i] XOR b[i])
            val eqAtI = Lit.negate(tseitinXor(ax[i], bx[i]))
            equalSoFar = tseitinAnd(intArrayOf(equalSoFar, eqAtI))
        }
        return tseitinOr(intArrayOf(lessSoFar, equalSoFar))
    }

    /** `aux ↔ (a == b)`, both unsigned LSB-first. */
    fun unsignedEq(a: IntArray, b: IntArray): Int {
        val n = maxOf(a.size, b.size)
        val ax = zeroExtend(a, n)
        val bx = zeroExtend(b, n)
        val matches = IntArray(n)
        for (i in 0 until n) matches[i] = Lit.negate(tseitinXor(ax[i], bx[i]))
        return tseitinAnd(matches)
    }
}
