package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.util.LongArrayList
import kotlin.math.abs

/**
 * Per-variable modular (Diophantine) domain tightening for integer equalities. In `Σ aᵢ·xᵢ = b`, every
 * other term is a multiple of `m = gcd(aⱼ : j ≠ i)`, so `aᵢ·xᵢ ≡ b (mod m)` — which confines `xᵢ` to a
 * single residue class `xᵢ ≡ r (mod m')`. Its bounds then move inward to the nearest in-class value; an
 * empty range is a contradiction. This is the reasoning [CoefficientStrengthening] does not do (it only
 * divides the whole constraint by its coefficient gcd and rejects `g ∤ b`); the residue is per-variable.
 *
 * Only bounds are tightened, never interior holes — a residue class over a wide domain would be an
 * O(span) carve. Everything is gated to `|value| < 2³¹` so the modular arithmetic cannot overflow.
 */
internal object DiophantineReduction {

    /** Domain-size ceiling for the interior carve: above it a domain is left alone rather than iterated,
     *  so a wide domain never triggers an O(size) sweep. */
    private const val SIZE_CAP = 4096

    /** `true` when `|v| < 2³¹`, so a product of two such values stays below 2⁶² — the overflow gate. */
    private fun fitsHalfLong(v: Long): Boolean = v > -(1L shl 31) && v < (1L shl 31)

    private fun gcd(a: Long, b: Long): Long {
        var x = abs(a)
        var y = abs(b)
        while (y != 0L) {
            val t = x % y
            x = y
            y = t
        }
        return x
    }

    /**
     * The solution set of `a·x ≡ b (mod m)` (with `m > 0`) as `x ≡ r (mod mod)` with `0 ≤ r < mod`, or
     * `null` when there is no solution. Assumes `|a|, |b|, m` all fit [fitsHalfLong].
     */
    private fun solveCongruence(a: Long, b: Long, m: Long): Pair<Long, Long>? {
        val aMod = ((a % m) + m) % m
        // Extended Euclid on (aMod, m): find s with aMod·s ≡ g (mod m), g = gcd(aMod, m).
        var oldR = aMod
        var r = m
        var oldS = 1L
        var s = 0L
        while (r != 0L) {
            val q = oldR / r
            val tR = oldR - q * r
            oldR = r
            r = tR
            val tS = oldS - q * s
            oldS = s
            s = tS
        }
        val g = oldR
        val bMod = ((b % m) + m) % m
        if (g == 0L || bMod % g != 0L) return null
        val mod = m / g
        val sMod = ((oldS % mod) + mod) % mod
        val root = (sMod * ((bMod / g) % mod)) % mod
        return root to mod
    }

    fun reduce(problem: Problem): PassDelta {
        var out: Array<IntDomain>? = null
        for (f in problem.factors) {
            if (f !is Linear || !f.isIntegerCore || f.op != LinearOp.EQ) continue
            if (f.vars.size < 2 || !fitsHalfLong(f.bound)) continue
            if (f.coeffs.any { !fitsHalfLong(it) }) continue
            val n = f.vars.size
            // Prefix / suffix gcd of |coeffs| so `gcd(aⱼ : j ≠ i)` is O(1) per variable.
            val pre = LongArray(n + 1)
            val suf = LongArray(n + 1)
            for (i in 0 until n) pre[i + 1] = gcd(pre[i], f.coeff(i))
            for (i in n - 1 downTo 0) suf[i] = gcd(suf[i + 1], f.coeff(i))
            for (j in 0 until n) {
                val m = gcd(pre[j], suf[j + 1])
                if (m <= 1L) continue
                val sol = solveCongruence(f.coeff(j), f.bound, m) ?: return contradiction(problem, f.vars[j])
                val (root, mod) = sol
                if (mod <= 1L) continue
                val v = f.vars[j]
                val dom = out?.get(v) ?: problem.intDomains[v]
                // Carve interior off-residue values — the reduction bound propagation cannot make (it
                // keeps only intervals). Iterate the domain's live values (O(size), never O(span)) and
                // gate on [SIZE_CAP] so a wide contiguous domain is skipped rather than enumerated.
                if (dom.size > SIZE_CAP || !fitsHalfLong(dom.min) || !fitsHalfLong(dom.max)) continue
                val remove = LongArrayList()
                for (k in 0 until dom.size) {
                    val x = dom.valueAt(k)
                    if (((x - root) % mod + mod) % mod != 0L) remove.add(x)
                }
                if (remove.isEmpty()) continue
                if (out == null) out = problem.intDomains.copyOf()
                out[v] = out[v].excludeValues(remove.toLongArray()) ?: return contradiction(problem, v)
            }
        }
        return if (out == null) PassDelta() else PassDelta(domains = out)
    }

    /** Two contradictory unit equalities on [v] — jointly unsatisfiable, so the bake reports `Unsat`
     *  (mirrors [CoefficientStrengthening]'s handling of a `g ∤ b` equality). */
    private fun contradiction(problem: Problem, v: Int): PassDelta {
        val c = problem.intDomains[v].min
        return PassDelta(
            addedFactors = listOf<Factor>(
                Linear(longArrayOf(1L), intArrayOf(v), LinearOp.EQ, c),
                Linear(longArrayOf(1L), intArrayOf(v), LinearOp.EQ, c + 1),
            ),
        )
    }
}
