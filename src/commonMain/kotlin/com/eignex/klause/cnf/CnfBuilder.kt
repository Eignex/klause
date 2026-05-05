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
    private var _falseLit: Int = -1
    private var _trueLit: Int = -1

    val clauses: List<IntArray> get() = _clauses
    val numVars: Int get() = _numVars

    fun newVar(): Int = _numVars++

    fun addClause(lits: IntArray) { _clauses.add(lits) }

    fun falseLit(): Int {
        if (_falseLit == -1) {
            val v = newVar()
            // Unit clause `¬v` forces v=false; positive literal of v evaluates to false.
            addClause(intArrayOf(Lit.make(v, positive = false)))
            _falseLit = Lit.make(v, positive = true)
            _trueLit = Lit.make(v, positive = false)
        }
        return _falseLit
    }

    fun trueLit(): Int {
        if (_trueLit == -1) falseLit()
        return _trueLit
    }

    /** Fresh literal `aux` such that `aux ↔ AND(inputs)`. */
    fun tseitinAnd(inputs: IntArray): Int {
        if (inputs.size == 1) return inputs[0]
        if (inputs.isEmpty()) return trueLit()
        val aux = Lit.make(newVar(), positive = true)
        for (l in inputs) addClause(intArrayOf(Lit.negate(aux), l))
        val big = IntArray(inputs.size + 1)
        big[0] = aux
        for (i in inputs.indices) big[i + 1] = Lit.negate(inputs[i])
        addClause(big)
        return aux
    }

    /** Fresh literal `aux` such that `aux ↔ OR(inputs)`. */
    fun tseitinOr(inputs: IntArray): Int {
        if (inputs.size == 1) return inputs[0]
        if (inputs.isEmpty()) return falseLit()
        val aux = Lit.make(newVar(), positive = true)
        for (l in inputs) addClause(intArrayOf(Lit.negate(l), aux))
        val big = IntArray(inputs.size + 1)
        big[0] = Lit.negate(aux)
        for (i in inputs.indices) big[i + 1] = inputs[i]
        addClause(big)
        return aux
    }

    /** Fresh literal `aux` such that `aux ↔ a XOR b`. Four clauses. */
    fun tseitinXor(a: Int, b: Int): Int {
        val aux = Lit.make(newVar(), positive = true)
        addClause(intArrayOf(Lit.negate(aux), a, b))
        addClause(intArrayOf(Lit.negate(aux), Lit.negate(a), Lit.negate(b)))
        addClause(intArrayOf(aux, Lit.negate(a), b))
        addClause(intArrayOf(aux, a, Lit.negate(b)))
        return aux
    }

    /** `aux ↔ a XOR b XOR c`. Used as the sum bit of a full adder. */
    fun tseitinXor3(a: Int, b: Int, c: Int): Int = tseitinXor(tseitinXor(a, b), c)

    /** `aux ↔ majority(a, b, c)`. Used as the carry bit of a full adder. Six clauses. */
    fun tseitinMaj3(a: Int, b: Int, c: Int): Int {
        val aux = Lit.make(newVar(), positive = true)
        addClause(intArrayOf(Lit.negate(aux), a, b))
        addClause(intArrayOf(Lit.negate(aux), a, c))
        addClause(intArrayOf(Lit.negate(aux), b, c))
        addClause(intArrayOf(aux, Lit.negate(a), Lit.negate(b)))
        addClause(intArrayOf(aux, Lit.negate(a), Lit.negate(c)))
        addClause(intArrayOf(aux, Lit.negate(b), Lit.negate(c)))
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
