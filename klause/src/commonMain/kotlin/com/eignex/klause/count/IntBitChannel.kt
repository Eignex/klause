package com.eignex.klause.count

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.bake
import com.eignex.util.arrays.emptyIntArray
import com.eignex.util.collections.Bits

// Channels integer variables to fresh Boolean bits so XOR-hash counting and sampling range over integer values
// without bit-blasting the problem. For `x` in `[min, max]`, width `w = ceil(log2(max - min + 1))` adds 0/1
// integers `c(i)` with a Linear `x = min + Σ 2^i·c(i)`, and bits `b(i) ↔ (c(i) == 1)` by ReifiedLinear; the hashes
// range over the `b(i)`. The binary encoding is a bijection on in-domain values, since x's own domain rules out
// patterns outside it, so distinct bit patterns count distinct values while every original factor stays native.
internal object IntBitChannel {

    // A wider projection has more than 2^31 values, which is not countable.
    private const val MAX_WIDTH = 31

    class Result(
        val problem: BakedProblem,
        // Per requested int var, in input order, its bit ids least-significant first.
        val bitsPerVar: List<IntArray>,
    ) {
        fun allBits(): IntArray {
            val total = bitsPerVar.sumOf { it.size }
            val out = IntArray(total)
            var w = 0
            for (bits in bitsPerVar) for (b in bits) out[w++] = b
            return out
        }
    }

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
                bitsPerVar.add(emptyIntArray())
                continue
            }

            // Linear: 1·x + Σ (-2^i)·c(i) == min  ⇔  x = min + Σ 2^i·c(i). Long bit weights: a wide
            // domain needs a channel past bit 30, where `1 shl i` overflows Int.
            val coeffs = LongArray(width + 1)
            val vars = IntArray(width + 1)
            coeffs[0] = 1
            vars[0] = x
            val bits = IntArray(width)
            for (i in 0 until width) {
                val bit = nextBool++
                val channelInt = nextInt++
                extraDomains.add(IntDomain(0, 1))
                // bit ↔ (channelInt == 1), the bool2int bridge.
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

        // Every requested column was a singleton: rebuilding would pay a second root bake for the same model.
        if (extraFactors.isEmpty()) return Result(base, bitsPerVar)

        // The channel restates each base column's box with its invented-endpoint marks, so a side the source left
        // open stays open and no consumer reads the box as a declaration.
        val problem = Problem(
            settings = base.settings,
            semantics = base.semantics,
            numBoolVars = nextBool,
            numIntVars = nextInt,
            intDomains = base.rootIntDomains() + extraDomains.toTypedArray(),
            factors = base.factors + extraFactors.toTypedArray(),
            impliedFactorMask = base.impliedFactorMask?.let { it + BooleanArray(extraFactors.size) },
            hasSymmetryBreaking = base.hasSymmetryBreaking,
            numRealVars = base.numRealVars,
            realLower = base.realLower,
            realUpper = base.realUpper,
            packedOpenIntLo = widened(base.intBounds.openLowerBits, nextInt),
            packedOpenIntHi = widened(base.intBounds.openUpperBits, nextInt),
        )
        return Result(problem.bake(), bitsPerVar)
    }

    // The channel's appended columns stay closed.
    private fun widened(source: Bits?, size: Int): Bits? {
        if (source == null) return null
        val out = Bits(size)
        source.forEachSet { out.set(it) }
        return out
    }

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
