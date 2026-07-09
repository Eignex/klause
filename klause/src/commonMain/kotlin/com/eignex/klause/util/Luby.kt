package com.eignex.klause.util

// The Luby–Sinclair–Zuckerman restart sequence 1, 1, 2, 1, 1, 2, 4, 1, 1, 2, 1, 1, 2, 4, 8, …
// (Luby-Sinclair-Zuckerman 1993) — universally optimal in expectation for Las Vegas algorithms
// with an unknown runtime distribution. Both solver engines drive their restart cadence from it:
// the backtrack search reads the indexed lubyN against a monotone run counter, while local search
// steps a LubyIterator. The two access forms produce the identical sequence (lubyN(i) equals the
// i-th value the iterator yields), pinned by a unit test. Per-engine scaling (a decision budget, a
// flip cadence) stays at the call site.

/**
 * The `index`-th term of the Luby sequence (1-based): `lubyN(i) = 2^(k-1)` when `i = 2^k − 1`,
 * otherwise `lubyN(i − 2^(k-1) + 1)` where `k = ⌊log₂(i)⌋ + 1`. The textbook recurrence, unwound
 * iteratively so an arbitrary index costs O(log i) without recursion or per-step state.
 */
internal fun lubyN(index: Long): Long {
    var i = index
    var k = 1
    // Find smallest k such that 2^k > i.
    while ((1L shl k) <= i) k++
    while (true) {
        val pow = 1L shl (k - 1)
        if (i == (pow shl 1) - 1) return pow
        // Otherwise i < (pow << 1) - 1; recurse on (i - pow + 1).
        i = i - pow + 1
        k = 1
        while ((1L shl k) <= i) k++
    }
}

/**
 * Stateful O(1)-per-step producer of the Luby sequence — Knuth's reluctant-doubling form
 * (TAOCP): [value] is the current term, [advance] moves to the next in constant time with no
 * index recompute. Start position yields `1` (the first term); each [advance] emits the next.
 */
internal class LubyIterator {
    private var u = 1
    private var v = 1

    /** The current term of the sequence. */
    val value: Int get() = v

    /** Move to the next term in O(1). */
    fun advance() {
        if ((u and -u) == v) {
            u += 1
            v = 1
        } else {
            v *= 2
        }
    }

    /** Return to the first term of the sequence. */
    fun reset() {
        u = 1
        v = 1
    }
}
