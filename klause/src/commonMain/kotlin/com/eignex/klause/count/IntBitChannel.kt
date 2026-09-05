package com.eignex.klause.count

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.bake
import com.eignex.klause.util.Bits
import com.eignex.klause.util.EmptyIntArray

/**
 * Channels integer variables to fresh Boolean bits so XOR-hash counting / sampling can range over
 * integer *values* without bit-blasting the whole problem to CNF.
 *
 * For an integer variable `x` with domain `[min, max]`, a binary encoding of width
 * `w = ceil(log2(max - min + 1))` is added: `w` fresh 0/1 integer vars `c(0..w-1)` plus a [Linear]
 * `x = min + Σ 2^i·c(i)`, and `w` fresh Boolean bits `b(i)` each tied to its `c(i)` by a
 * [ReifiedLinear] `b(i) ↔ (c(i) == 1)` — the same bool↔0/1-int bridge the FlatZinc front end uses
 * for `bool2int`. The hashes then range over the `b(i)`.
 *
 * The map value → bits is a bijection on the in-domain values (binary representation is unique, and
 * `x`'s own domain rules out any pattern that lands outside `[min, max]` or on a hole). So counting
 * distinct feasible bit patterns equals counting distinct integer values — the exactness argument
 * that bit-blasting relied on, kept while every original constraint stays native.
 *
 * Only the requested `intVars` are channelled; all other variables and every factor are carried
 * through unchanged, so globals keep their own propagators.
 */
internal object IntBitChannel {

    /** Maximum encodable domain width. A wider projection has > 2^31 values — not countable. */
    private const val MAX_WIDTH = 31

    class Result(
        /** The augmented problem the solver enumerates: `base` plus the channel vars and factors. */
        val problem: BakedProblem,
        /** For each requested int var (in input order), its fresh bit ids, least-significant first. */
        val bitsPerVar: List<IntArray>,
    ) {
        /** All channel bits flattened — the Boolean hash domain over the channelled integers. */
        fun allBits(): IntArray {
            val total = bitsPerVar.sumOf { it.size }
            val out = IntArray(total)
            var w = 0
            for (bits in bitsPerVar) for (b in bits) out[w++] = b
            return out
        }
    }

    /** Build the channel for `intVars` of `base`. With no int vars the base problem is returned as-is. */
    fun channel(base: BakedProblem, intVars: IntArray): Result {
        if (intVars.isEmpty()) return Result(base, emptyList())

        var nextBool = base.numBoolVars
        var nextInt = base.numIntVars
        val extraDomains = ArrayList<IntDomain>()
        val extraFactors = ArrayList<Factor>()
        val bitsPerVar = ArrayList<IntArray>(intVars.size)

        for (x in intVars) {
            val dom = base.rootIntDomain(x)
            val min = dom.min
            // `max - min` wraps past `Long` on a near-full-range box, and a wrapped span reads as a
            // singleton the encoding would drop silently instead of rejecting.
            val span = dom.max - dom.min
            require(span >= 0L) {
                "IntBitChannel: int var $x spans more than 2^63 values; too wide to hash (max 2^$MAX_WIDTH)"
            }
            val width = bitWidth(span)
            require(width <= MAX_WIDTH) {
                "IntBitChannel: int var $x spans ${span + 1} values; too wide to hash (max 2^$MAX_WIDTH)"
            }
            if (width == 0) {
                // Singleton domain: the value is constant, so it contributes no bits to hash over.
                bitsPerVar.add(EmptyIntArray)
                continue
            }

            // Linear: 1·x + Σ (-2^i)·c(i) == min  ⇔  x = min + Σ 2^i·c(i). Long bit weights: `1 shl i`
            // overflows Int past bit 30, and a wide domain needs a ~50-bit channel.
            val coeffs = LongArray(width + 1)
            val vars = IntArray(width + 1)
            coeffs[0] = 1
            vars[0] = x
            val bits = IntArray(width)
            for (i in 0 until width) {
                val bit = nextBool++
                val channelInt = nextInt++
                extraDomains.add(IntDomain(0, 1))
                // bit ↔ (channelInt == 1): the bool2int bridge (FlatZinc channelBoolsToInts).
                extraFactors.add(
                    ReifiedLinear(
                        auxBoolVar = bit,
                        coeffs = intArrayOf(1),
                        vars = intArrayOf(channelInt),
                        op = LinearOp.EQ,
                        bound = 1,
                    ),
                )
                bits[i] = bit
                coeffs[i + 1] = -(1L shl i)
                vars[i + 1] = channelInt
            }
            extraFactors.add(Linear(coeffs, vars, LinearOp.EQ, min))
            bitsPerVar.add(bits)
        }

        // Every requested column was a singleton, so the encoding added nothing: the base projection already
        // is the augmented one, and rebuilding it would pay a second root bake for the same model.
        if (extraFactors.isEmpty()) return Result(base, bitsPerVar)

        // A base column is restated as the projection held it: the same box, and the same marks saying
        // which of its endpoints were invented. The channel re-encodes that box rather than replacing it,
        // so a side the source left open is still open here and no consumer reads the box as a declaration.
        val problem = Problem(
            numBoolVars = nextBool,
            numIntVars = nextInt,
            intDomains = base.rootIntDomains() + extraDomains.toTypedArray(),
            factors = base.factors + extraFactors.toTypedArray(),
            packedOpenIntLo = widened(base.intBounds.openLowerBits, nextInt),
            packedOpenIntHi = widened(base.intBounds.openUpperBits, nextInt),
        )
        return Result(problem.bake(), bitsPerVar)
    }

    /** [source] over [size] columns, the channel's appended ones left closed. */
    private fun widened(source: Bits?, size: Int): Bits? {
        if (source == null) return null
        val out = Bits(size)
        source.forEachSet { out.set(it) }
        return out
    }

    /** Bits needed to represent values `0..span`; `0` for a singleton (`span == 0`). */
    private fun bitWidth(span: Long): Int {
        var width = 0
        var v = span
        while (v > 0) {
            width++
            v = v shr 1
        }
        return width
    }
}
