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
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.collections.LongArrayList
import kotlin.math.abs

// In `Σ aᵢ·xᵢ = b` every other term is a multiple of `m = gcd(aⱼ : j ≠ i)`, so `aᵢ·xᵢ ≡ b (mod m)` confines `xᵢ`
// to one residue class. The finite form carves off-class values from small domains; the source form moves each
// closed side inward to the nearest in-class value. Values are gated to `|v| < 2³¹` so the arithmetic cannot
// overflow.
internal object DiophantineReduction {

    private const val SIZE_CAP = 4096

    private const val DIOPHANTINE_WORK_WEIGHT = 10L

    private const val POLL_INTERVAL = 64

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

    // `a·x ≡ b (mod m)` as `x ≡ r (mod mod)`, or null when unsolvable. Requires `|a|, |b|, m` to fit [fitsHalfLong].
    private fun solveCongruence(a: Long, b: Long, m: Long): Pair<Long, Long>? {
        val aMod = ((a % m) + m) % m
        // Extended Euclid: `aMod·s ≡ gcd(aMod, m) (mod m)`.
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

    private class Residue(val column: Int, val root: Long, val mod: Long)

    // The residue classes in row order, plus the first column whose congruence is unsolvable (or -1).
    private fun residues(factors: List<Factor>, cancellation: ExecutionControl): Pair<List<Residue>, Int> {
        val out = ArrayList<Residue>()
        if (cancellation()) return out to -1
        for (f in presolveLinearRows(factors, cancellation = cancellation)) {
            if (cancellation()) return out to -1
            if (f.op != LinearOp.EQ) continue
            cancellation.charge(DIOPHANTINE_WORK_WEIGHT * (1L + f.vars.size))
            if (cancellation()) return out to -1
            val row = f.integerConstants ?: continue
            if (f.vars.size < 2 || !fitsHalfLong(row.bound)) continue
            var fits = true
            for (i in f.vars.indices) {
                if (i % POLL_INTERVAL == 0 && cancellation()) return out to -1
                if (!fitsHalfLong(row.coeff(i))) {
                    fits = false
                    break
                }
            }
            if (!fits) continue
            val n = f.vars.size
            // Prefix / suffix gcd of |coeffs| so `gcd(aⱼ : j ≠ i)` is O(1) per variable.
            val pre = LongArray(n + 1)
            val suf = LongArray(n + 1)
            for (i in 0 until n) {
                if (i % POLL_INTERVAL == 0 && cancellation()) return out to -1
                pre[i + 1] = gcd(pre[i], row.coeff(i))
            }
            for (i in n - 1 downTo 0) {
                if (i % POLL_INTERVAL == 0 && cancellation()) return out to -1
                suf[i] = gcd(suf[i + 1], row.coeff(i))
            }
            for (j in 0 until n) {
                if (j % POLL_INTERVAL == 0 && cancellation()) return out to -1
                val m = gcd(pre[j], suf[j + 1])
                if (m <= 1L) continue
                val (root, mod) = solveCongruence(row.coeff(j), row.bound, m) ?: return out to f.vars[j]
                if (mod > 1L) out.add(Residue(f.vars[j], root, mod))
            }
        }
        return out to -1
    }

    // Sides that cross refute; the caller reads that off the crossed range.
    fun reduceSource(problem: Problem, cancellation: ExecutionControl = ExecutionControl.Never): SourceDelta {
        val (found, unsolvable) = residues(problem.factors.asList(), cancellation)
        if (unsolvable >= 0) return SourceDelta(infeasible = true)
        if (found.isEmpty()) return SourceDelta()
        val bounds = problem.intBounds
        // The tightening has no getters, so the narrowed sides are tracked here as each residue applies.
        val lo = HashMap<Int, Long>()
        val hi = HashMap<Int, Long>()
        val tightening = bounds.tightening()
        for (r in found) {
            if (cancellation()) break
            cancellation.charge(1)
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

    fun reduce(problem: BakedProblem, cancellation: ExecutionControl = ExecutionControl.Never): PassDelta {
        var out: Array<IntDomain>? = null
        val (found, unsolvable) = residues(problem.factors.asList(), cancellation)
        if (unsolvable >= 0) return contradiction(problem, unsolvable)
        for (r in found) {
            if (cancellation()) break
            val root = r.root
            val mod = r.mod
            val v = r.column
            val dom = out?.get(v) ?: problem.rootIntDomain(v)
            // Interior holes are what bound propagation cannot make. Iterating live values keeps this O(size).
            val live = dom.spanOrNull(SIZE_CAP.toLong()) ?: continue
            if (!fitsHalfLong(dom.min) || !fitsHalfLong(dom.max)) continue
            val remove = LongArrayList()
            var scanned = 0
            while (scanned < live.size) {
                val end = minOf(scanned + POLL_INTERVAL, live.size)
                for (k in scanned until end) {
                    val x = live.valueAt(k)
                    if (floorMod(x - root, mod) != 0L) remove.add(x)
                }
                cancellation.charge((end - scanned).toLong())
                if (cancellation()) return if (out == null) PassDelta() else PassDelta(domains = out)
                scanned = end
            }
            if (remove.isEmpty()) continue
            if (out == null) out = problem.rootIntDomains()
            out[v] = out[v].excludeValues(remove.toLongArray()) ?: return contradiction(problem, v)
        }
        return if (out == null) PassDelta() else PassDelta(domains = out)
    }

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
