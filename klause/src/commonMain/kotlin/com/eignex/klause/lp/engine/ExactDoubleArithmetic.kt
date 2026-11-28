package com.eignex.klause.lp.engine

// Records whether any binary64 sum or product rounded, through error-free transformations: Knuth's TwoSum
// (The Art of Computer Programming, Vol. 2, §4.2.2) and Dekker's TwoProduct over a Veltkamp split (T. J. Dekker,
// A floating-point technique for extending the available precision, Numer. Math. 18, 1971,
// https://doi.org/10.1007/BF01397083), since common code has no fused multiply-add. A split that would overflow
// counts as rounded, which only sends a value to the exact rational recomputation.
internal class ExactDoubleArithmetic {
    var rounded = false
        private set

    fun reset() {
        rounded = false
    }

    fun sum(a: Double, b: Double): Double {
        val s = a + b
        if (!rounded) {
            val bb = s - a
            if (!s.isFinite() || (a - (s - bb)) + (b - bb) != 0.0) rounded = true
        }
        return s
    }

    fun product(a: Double, b: Double): Double {
        val p = a * b
        if (!rounded && a != 0.0 && b != 0.0) {
            val (ah, al) = split(a) ?: return p.also { rounded = true }
            val (bh, bl) = split(b) ?: return p.also { rounded = true }
            // Near the bottom of the range the error terms themselves underflow, so a tiny product is not trusted.
            if (!p.isFinite() || kotlin.math.abs(p) < TINY ||
                ((ah * bh - p) + ah * bl + al * bh) + al * bl != 0.0
            ) {
                rounded = true
            }
        }
        return p
    }

    private fun split(a: Double): Pair<Double, Double>? {
        val c = VELTKAMP * a
        if (!c.isFinite()) return null
        val high = c - (c - a)
        return high to (a - high)
    }

    private companion object {
        const val VELTKAMP = 134_217_729.0 // 2^27 + 1
        const val TINY = 1e-250
    }
}
