package com.eignex.klause.propagation

import com.eignex.klause.solver.Lit
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.MutableIntLongMap

/**
 * A pseudo-Boolean constraint in signed-per-variable cutting-planes form, used as the accumulating
 * nogood of [PbConflictResolvent]. The constraint is
 *
 *     Σ_v coef(v) · x_v  ≥  rhs        (x_v ∈ {0, 1}, coef(v) may be negative)
 *
 * A clause `ℓ₁ ∨ … ∨ ℓₖ` is the degenerate case `Σ ℓᵢ ≥ 1`; a pseudo-Boolean `Σ wᵢℓᵢ ≥ b` maps in by
 * folding each literal onto its variable (a negative literal `¬x` contributes `w·(1−x)`, i.e. `−w` on
 * `x` and `+w` to the right-hand side after moving the constant).
 *
 * Cutting-planes conflict analysis derives new constraints by *generalized resolution*: scale two `≥`
 * constraints by positive multipliers so a pivot variable's coefficients cancel, add them, and
 * saturate. Every such step is a non-negative linear combination plus Chvátal-Gomory rounding, so the
 * result is implied by the inputs — the learned constraint is always sound. All arithmetic is
 * overflow-guarded; an operation that would exceed [Long] range returns false so the caller can fall
 * back to clause resolution instead of producing a corrupt constraint.
 *
 * The "positive-literal degree" — the bound once every term is rewritten over a true-going literal —
 * is `rhs + Σ_{coef(v)<0} |coef(v)|`; the positive-literal coefficient of `v` is `|coef(v)|` and its
 * literal is `x_v` when `coef(v) > 0`, else `¬x_v`. That form is what [materialize] emits and what the
 * slack / saturation reason about.
 */
internal class PbAccumulator {
    /** var → signed coefficient on `x_v`. Zero entries are pruned. */
    private val coef = MutableIntLongMap()

    /** Invoke [action] for each stored variable. Order is unspecified. */
    inline fun forEachVar(action: (Int) -> Unit) {
        coefficients.forEach { variable, _ -> action(variable) }
    }

    /** Exposed for the inline [forEachVar]; not part of the contract. */
    @PublishedApi internal val coefficients: MutableIntLongMap get() = coef
    var rhs: Long = 0L

    fun clear() {
        coef.clear()
        rhs = 0L
    }

    fun isEmpty(): Boolean = coef.isEmpty()

    private fun put(v: Int, c: Long) {
        if (c == 0L) coef.remove(v) else coef.put(v, c)
    }

    /** The all-positive-literal degree; the constraint is infeasible iff this exceeds the total
     *  available positive weight. */
    fun positiveDegree(): Long {
        var d = rhs
        coef.forEach { _, c -> if (c < 0L) d -= c } // subtract negative ⇒ add magnitude
        return d
    }

    /** Signed coefficient on `x_v` (0 if absent). */
    fun coefOf(v: Int): Long = coef.getOrDefault(v, 0L)

    /**
     * Load this accumulator from a pseudo-Boolean constraint already in `≥` form: `Σ weightsᵢ·literalsᵢ ≥
     * geBound`. The caller normalizes any `LE` constraint first (flip every literal, set the bound to
     * `Σweights − bound`). Weights must be positive (a normalized PB or a clause). Returns false on overflow.
     */
    fun loadPb(weights: LongArray, literals: IntArray, geBound: Long): Boolean {
        clear()
        rhs = geBound
        for (i in literals.indices) {
            val lit = literals[i]
            val w = weights[i]
            val v = Lit.variable(lit)
            if (Lit.isPositive(lit)) {
                if (!addCoef(v, w)) return false
            } else {
                // w·¬x = w − w·x  ⇒ −w on x, and +w moves to the rhs constant (degree drops by w).
                if (!addCoef(v, -w)) return false
                rhs = subExact(rhs, w) ?: return false
            }
        }
        return true
    }

    /** Load a clause `ℓ₁ ∨ … ∨ ℓₖ` as `Σ ℓᵢ ≥ 1`. Returns false on overflow (unreachable for clauses). */
    fun loadClause(literals: IntArray): Boolean {
        val ones = LongArray(literals.size) { 1L }
        return loadPb(ones, literals, geBound = 1L)
    }

    private fun addCoef(v: Int, delta: Long): Boolean {
        val cur = coef.getOrDefault(v, 0L)
        val next = addExact(cur, delta) ?: return false
        put(v, next)
        return true
    }

    /**
     * Generalized resolution: `this := mulSelf·this + mulOther·other`, both multipliers > 0, chosen by
     * the caller so the pivot variable's coefficients cancel. Returns false on overflow (leaving this
     * accumulator in a partially-updated state the caller must discard).
     */
    fun addScaled(other: PbAccumulator, mulSelf: Long, mulOther: Long): Boolean {
        // Scale existing terms.
        if (mulSelf != 1L) {
            var overflowed = false
            // Rewriting a stored value never rehashes, so this updates in place rather than over a
            // snapshot of the keys.
            coef.forEach { v, c ->
                val scaled = mulExact(c, mulSelf)
                if (scaled == null) overflowed = true else coef.put(v, scaled)
            }
            if (overflowed) return false
        }
        val scaledRhsSelf = mulExact(rhs, mulSelf) ?: return false
        val scaledRhsOther = mulExact(other.rhs, mulOther) ?: return false
        rhs = addExact(scaledRhsSelf, scaledRhsOther) ?: return false
        var failed = false
        other.coef.forEach { v, c ->
            if (!failed) {
                val add = mulExact(c, mulOther)
                if (add == null || !addCoef(v, add)) failed = true
            }
        }
        return !failed
    }

    /**
     * Saturate: clamp every positive-literal coefficient to the positive degree (a coefficient larger
     * than the degree can never be the deciding term, so capping it is a sound weakening that also keeps
     * magnitudes bounded). No-op when the degree is non-positive.
     */
    fun saturate() {
        val d = positiveDegree()
        if (d <= 0L) return
        coef.forEach { v, c ->
            if (c > d) coef.put(v, d) else if (c < -d) {
                coef.put(v, -d)
            }
        }
    }

    /** Divide through by the gcd of all coefficients and round the degree up (Chvátal-Gomory) — sound,
     *  and keeps coefficients from growing across resolution steps. No-op when the gcd is 0 or 1. */
    fun normalizeByGcd() {
        var g = 0L
        coef.forEach { _, c -> if (g != 1L) g = gcd(g, c) }
        if (g <= 1L) return
        coef.forEach { v, c -> coef.put(v, c / g) }
        // Positive-literal degree divides with ceil; recover rhs from the divided coefficients.
        val newPosDegree = ceilDiv(positiveDegree(), g)
        var negSum = 0L
        coef.forEach { _, c -> if (c < 0L) negSum -= c }
        rhs = newPosDegree - negSum
    }

    /**
     * Selective reduction before dividing by [divisor]: weaken away (drop, reducing the degree
     * by its weight) every literal — except [keepVar] — that is **not currently false** and whose
     * coefficient is **not divisible** by [divisor]. Those are exactly the literals a subsequent
     * [divideRoundUp] would round up into spurious slack, so dropping them first keeps the divided
     * constraint tight (the resolvent stays conflicting). Falsified literals contribute no slack and are
     * kept; divisible ones divide exactly and are kept — so a unit-weight (`divisor == 1`) reason is left
     * untouched. Sound: dropping a literal from `Σ aᵢℓᵢ ≥ d` yields `Σ_{i≠j} aᵢℓᵢ ≥ d − aⱼ`.
     */
    fun weakenForDivision(keepVar: Int, divisor: Long, isFalse: (Int) -> Boolean) {
        if (divisor <= 1L) return // everything divides by 1 — nothing to weaken
        // Removal shifts entries, so the candidates are collected before any of them is dropped.
        val candidates = IntArrayList(coef.size)
        coef.forEach { v, _ -> if (v != keepVar) candidates.add(v) }
        candidates.forEach { v ->
            val c = coef.getOrDefault(v, 0L)
            val mag = if (c < 0L) -c else c
            if (mag % divisor != 0L && !isFalse(Lit.make(v, c > 0L))) {
                if (c > 0L) rhs -= c // drop the literal, reducing the degree by its weight
                coef.remove(v)
            }
        }
    }

    /**
     * Divide by [d] > 1 and round up (Chvátal-Gomory): every positive-literal coefficient and the degree
     * are replaced by their ceil-division. Sound (a valid cutting plane) and the core strengthening step
     * of cutting-planes learning. [d] is typically the pivot's reason coefficient.
     */
    fun divideRoundUp(d: Long) {
        if (d <= 1L) return
        coef.forEach { v, c -> coef.put(v, if (c >= 0L) ceilDiv(c, d) else -ceilDiv(-c, d)) }
        val newPosDegree = ceilDiv(positiveDegree(), d)
        var negSum = 0L
        coef.forEach { _, c -> if (c < 0L) negSum -= c }
        rhs = newPosDegree - negSum
    }

    /**
     * Materialize to a normalized pseudo-Boolean `Σ wᵢ·literalsᵢ ≥ degree` with all `wᵢ > 0`, or null
     * when the constraint is trivially true (degree ≤ 0) — nothing to learn. Coefficients are capped at
     * the degree (saturated) so the emitted constraint is canonical.
     */
    fun materialize(): PbLearned? {
        saturate()
        val degree = positiveDegree()
        if (degree <= 0L) return null
        val n = coef.size
        val weights = LongArray(n)
        val literals = IntArray(n)
        var i = 0
        coef.forEach { v, c ->
            if (c != 0L) {
                weights[i] = if (c > 0L) c else -c
                literals[i] = Lit.make(v, c > 0L)
                i++
            }
        }
        if (i == 0) return null
        return PbLearned(weights.copyOf(i), literals.copyOf(i), degree)
    }

    private companion object {
        fun gcd(a: Long, b: Long): Long {
            var x = if (a < 0) -a else a
            var y = if (b < 0) -b else b
            while (y != 0L) {
                val t = x % y
                x = y
                y = t
            }
            return x
        }

        fun ceilDiv(a: Long, b: Long): Long {
            val q = a / b
            return if (a % b != 0L && (a xor b) >= 0L) q + 1 else q
        }

        fun addExact(a: Long, b: Long): Long? {
            val r = a + b
            // Overflow iff a and b share a sign that differs from the result's.
            return if (((a xor r) and (b xor r)) < 0L) null else r
        }

        fun subExact(a: Long, b: Long): Long? {
            val r = a - b
            return if (((a xor b) and (a xor r)) < 0L) null else r
        }

        fun mulExact(a: Long, b: Long): Long? {
            if (a == 0L || b == 0L) return 0L
            val r = a * b
            // Verify by reversing; also guard the MIN_VALUE/-1 corner.
            if (a == Long.MIN_VALUE && b == -1L) return null
            if (b == Long.MIN_VALUE && a == -1L) return null
            return if (r / b != a) null else r
        }
    }
}

/** A materialized learned pseudo-Boolean constraint `Σ weightsᵢ·literalsᵢ ≥ degree`, all weights > 0. */
internal class PbLearned(val weights: LongArray, val literals: IntArray, val degree: Long)
