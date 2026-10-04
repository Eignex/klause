package com.eignex.klause.presolve.linear

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.PassDelta
import com.eignex.klause.presolve.SourceDelta
import com.eignex.klause.presolve.presolveLinearRows
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.LongArrayList
import kotlin.math.abs

/**
 * Per-variable modular (Diophantine) domain tightening for integer equalities. In `Σ aᵢ·xᵢ = b`, every
 * other term is a multiple of `m = gcd(aⱼ : j ≠ i)`, so `aᵢ·xᵢ ≡ b (mod m)` — which confines `xᵢ` to a
 * single residue class `xᵢ ≡ r (mod m')`. Its bounds then move inward to the nearest in-class value; an
 * empty range is a contradiction. This is the reasoning [CoefficientStrengthening] does not do (it only
 * divides the whole constraint by its coefficient gcd and rejects `g ∤ b`); the residue is per-variable.
 *
 * The finite form carves the off-class values out of a domain of at most [SIZE_CAP] values and leaves a
 * wider one alone, so a residue class never costs an O(span) sweep. The source form has only ranges to
 * state, so it moves each closed side inward to the nearest in-class value and leaves an open side open.
 * Everything is gated to `|value| < 2³¹` so the modular arithmetic cannot overflow.
 */
internal object DiophantineReduction {

    /** Domain-size ceiling for the interior carve: above it a domain is left alone rather than iterated,
     *  so a wide domain never triggers an O(size) sweep. */
    private const val SIZE_CAP = 4096

    /** `true` when `|v| < 2³¹`, so a product of two such values stays below 2⁶² — the overflow gate. */

    // Work charged per equality scanned, scaled by its length: the residue scan is linear in the row.
    private const val DIOPHANTINE_WORK_WEIGHT = 10L

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

    /** One column confined to `x ≡ root (mod mod)` by an equality, with `mod > 1`. */
    private class Residue(val column: Int, val root: Long, val mod: Long)

    /**
     * Every residue class the integer equalities in [factors] confine a column to, in row order, or the
     * column whose congruence has no solution at all. Reads coefficients alone, so both forms share it.
     */
    private fun residues(factors: List<Factor>, cancellation: Cancellation): Pair<List<Residue>, Int> {
        val out = ArrayList<Residue>()
        for (f in presolveLinearRows(factors)) {
            if (f.op != LinearOp.EQ) continue
            cancellation.charge(DIOPHANTINE_WORK_WEIGHT * (1L + f.vars.size))
            val row = f.integerConstants ?: continue
            if (f.vars.size < 2 || !fitsHalfLong(row.bound)) continue
            if (f.vars.indices.any { !fitsHalfLong(row.coeff(it)) }) continue
            val n = f.vars.size
            // Prefix / suffix gcd of |coeffs| so `gcd(aⱼ : j ≠ i)` is O(1) per variable.
            val pre = LongArray(n + 1)
            val suf = LongArray(n + 1)
            for (i in 0 until n) pre[i + 1] = gcd(pre[i], row.coeff(i))
            for (i in n - 1 downTo 0) suf[i] = gcd(suf[i + 1], row.coeff(i))
            for (j in 0 until n) {
                val m = gcd(pre[j], suf[j + 1])
                if (m <= 1L) continue
                val (root, mod) = solveCongruence(row.coeff(j), row.bound, m) ?: return out to f.vars[j]
                if (mod > 1L) out.add(Residue(f.vars[j], root, mod))
            }
        }
        return out to -1
    }

    /**
     * The source form: each closed side of a confined column moves inward to the nearest in-class value.
     *
     * A congruence with no solution refutes. Moving both sides past each other also refutes, which the
     * caller reads off the crossed range rather than this pass checking it.
     */
    fun reduceSource(problem: Problem, cancellation: Cancellation = Cancellation.Never): SourceDelta {
        val (found, unsolvable) = residues(problem.factors.asList(), cancellation)
        if (unsolvable >= 0) return SourceDelta(infeasible = true)
        if (found.isEmpty()) return SourceDelta()
        val bounds = problem.intBounds
        // The tightening has no getters, so the narrowed sides are tracked here as each residue applies.
        val lo = HashMap<Int, Long>()
        val hi = HashMap<Int, Long>()
        val tightening = bounds.tightening()
        for (r in found) {
            val v = r.column
            if (bounds.hasLower(v)) {
                val current = lo[v] ?: bounds.lower(v)
                if (fitsHalfLong(current)) {
                    val next = current + floorMod(r.root - current, r.mod)
                    if (next > current) {
                        lo[v] = next
                        tightening.atLeast(v, next)
                    }
                }
            }
            if (bounds.hasUpper(v)) {
                val current = hi[v] ?: bounds.upper(v)
                if (fitsHalfLong(current)) {
                    val next = current - floorMod(current - r.root, r.mod)
                    if (next < current) {
                        hi[v] = next
                        tightening.atMost(v, next)
                    }
                }
            }
        }
        return SourceDelta(bounds = tightening.build())
    }

    private fun floorMod(x: Long, m: Long): Long = ((x % m) + m) % m

    fun reduce(problem: BakedProblem, cancellation: Cancellation = Cancellation.Never): PassDelta {
        var out: Array<IntDomain>? = null
        val (found, unsolvable) = residues(problem.factors.asList(), cancellation)
        if (unsolvable >= 0) return contradiction(problem, unsolvable)
        for (r in found) {
            val root = r.root
            val mod = r.mod
            val v = r.column
            val dom = out?.get(v) ?: problem.rootIntDomain(v)
            // Carve interior off-residue values — the reduction bound propagation cannot make (it
            // keeps only intervals). Iterate the domain's live values (O(size), never O(span)) and
            // gate on [SIZE_CAP] so a wide contiguous domain is skipped rather than enumerated.
            val live = dom.spanOrNull(SIZE_CAP.toLong()) ?: continue
            if (!fitsHalfLong(dom.min) || !fitsHalfLong(dom.max)) continue
            val remove = LongArrayList()
            for (k in 0 until live.size) {
                val x = live.valueAt(k)
                if (floorMod(x - root, mod) != 0L) remove.add(x)
            }
            if (remove.isEmpty()) continue
            if (out == null) out = problem.rootIntDomains()
            out[v] = out[v].excludeValues(remove.toLongArray()) ?: return contradiction(problem, v)
        }
        return if (out == null) PassDelta() else PassDelta(domains = out)
    }

    /** Two contradictory unit equalities on [v] — jointly unsatisfiable, so the bake reports `Unsat`
     *  (mirrors [CoefficientStrengthening]'s handling of a `g ∤ b` equality). */
    private fun contradiction(problem: BakedProblem, v: Int): PassDelta {
        val c = problem.rootIntDomain(v).min
        return PassDelta(
            addedFactors = listOf<Factor>(
                Linear(longArrayOf(1L), intArrayOf(v), LinearOp.EQ, c),
                Linear(longArrayOf(1L), intArrayOf(v), LinearOp.EQ, c + 1),
            ),
        )
    }
}
