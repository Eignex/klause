package com.eignex.klause.solver.decompose

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.factor.AllEqual
import com.eignex.klause.solver.factor.AllDifferentExcept
import com.eignex.klause.solver.factor.AllDifferentExceptZero
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.factor.Monotone
import com.eignex.klause.solver.factor.ValuePrecede
import com.eignex.klause.solver.factor.Member
import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.factor.ReifiedLinear
import com.eignex.klause.solver.factor.Clause
import com.eignex.klause.solver.Lit

/**
 * Allocator hook for fresh aux variables used by [FactorDecomposer]. Backends that
 * route unsupported globals through decomposition implement this against their own
 * encoding state (e.g. the Z3 translator allocates a Z3 const declaration alongside
 * the klause-side var id).
 */
interface DecompositionContext {
    /** Allocate and return the id of a fresh Boolean variable. */
    fun freshBool(): Int
    /** Allocate and return the id of a fresh integer variable over [domain]. */
    fun freshInt(domain: IntDomain): Int
}

/**
 * Lowers a complex global factor to a list of mid-level IR factors that arithmetic
 * backends consume natively. Mid-IR is the small set:
 *  - [Clause], [Linear], [Cardinality], [com.eignex.klause.solver.factor.PseudoBoolean],
 *    [com.eignex.klause.solver.factor.Product],
 *  - [ReifiedLinear], [com.eignex.klause.solver.factor.ReifiedCardinality],
 *    [com.eignex.klause.solver.factor.ReifiedPseudoBoolean].
 *
 * Backends call [decompose] with their own [DecompositionContext]. The returned list is
 * a semantically-equivalent encoding using only mid-IR factor types; the caller is
 * responsible for translating each element. Returning `null` means "no decomposition
 * registered" — the caller may either error out or fall back to bit-blasting.
 *
 * Bit-blast / SAT backends and arithmetic / SMT backends share this entry point: the
 * mid-IR is what each can natively consume (SAT via per-factor CNF emitters; SMT via
 * direct LIA/NIA translation).
 */
object FactorDecomposer {

    /** Returns mid-IR replacement for [f], or `null` when no decomposition is known.
     *  Mid-IR factors map to themselves; callers should check via [isMidIR] before
     *  calling decompose to avoid pointless lookups. */
    fun decompose(f: Factor, ctx: DecompositionContext): List<Factor>? = when (f) {
        is AllEqual -> decomposeAllEqual(f)
        is AllDifferentExceptZero -> decomposeAllDifferentExceptZero(f)
        is AllDifferentExcept -> decomposeAllDifferentExcept(f)
        is Monotone -> decomposeMonotone(f)
        is ValuePrecede -> decomposeValuePrecede(f, ctx)
        is Member -> decomposeMember(f, ctx)
        else -> null
    }

    /** True for factor types every arithmetic backend supports without further
     *  decomposition. Maintained alongside the per-backend native translators — adding
     *  a new mid-IR type means updating every backend's `accepts` table too. */
    fun isMidIR(f: Factor): Boolean = when (f) {
        is Clause,
        is Linear,
        is Cardinality,
        is com.eignex.klause.solver.factor.PseudoBoolean,
        is com.eignex.klause.solver.factor.Product,
        is ReifiedLinear,
        is com.eignex.klause.solver.factor.ReifiedCardinality,
        is com.eignex.klause.solver.factor.ReifiedPseudoBoolean,
        -> true
        else -> false
    }

    // ---------------- per-factor decompositions ----------------

    /** `all_equal(xs)` → `xs[i] = xs[0]` for `i = 1..n-1` as a chain of Linear EQ. */
    private fun decomposeAllEqual(f: AllEqual): List<Factor> {
        if (f.xs.size < 2) return emptyList()
        val out = ArrayList<Factor>(f.xs.size - 1)
        val x0 = f.xs[0]
        for (i in 1 until f.xs.size) {
            out.add(Linear(intArrayOf(1, -1), intArrayOf(f.xs[i], x0), LinearOp.EQ, 0))
        }
        return out
    }

    /** `all_different_except(xs, except)` → pairwise NE except when either operand
     *  is in `except`. For arbitrary `except` sets we can't express the conditional
     *  inline in a single Linear factor, so each pair gets a reified `(xᵢ = xⱼ)` aux
     *  plus a clause asserting `¬(xᵢ = xⱼ) ∨ xᵢ ∈ except ∨ xⱼ ∈ except`. We don't yet
     *  reify "x ∈ except" as a factor, so this decomposition is conservative: it
     *  treats `except` empty (pure pairwise NE), and falls back to leaving the
     *  factor untranslated when `except` is non-empty. The Z3 backend handles the
     *  except set natively via additional reified-eq aux. */
    private fun decomposeAllDifferentExcept(f: AllDifferentExcept): List<Factor>? {
        if (f.except.isEmpty()) return decomposePairwiseNE(f.xs)
        // With a non-empty exception set, we need a reified "x ∈ except" gate per
        // operand. That's expressible as a disjunction of EQ-reifications, but the
        // factor surface for "OR of reified linears" doesn't exist as a primitive —
        // a backend that wants this should either (a) special-case via a Cardinality
        // over the reified-eq aux array, or (b) translate the global directly. Until
        // we have (a) as a clean encoding, return null and let the backend fall
        // through to its own handling.
        return null
    }

    /** `all_different_except_zero(xs)` → same as the except-set form with `{0}`.
     *  Decomposable when xs domains all admit zero; otherwise behaves like pure
     *  AllDifferent. Conservative for now: not decomposed — backends should keep
     *  the native translator until the gated-NE encoding lands. */
    private fun decomposeAllDifferentExceptZero(f: AllDifferentExceptZero): List<Factor>? {
        return null
    }

    /** `monotone(xs, direction, strict)` → chain of Linear comparisons between
     *  adjacent elements. */
    private fun decomposeMonotone(f: Monotone): List<Factor> {
        if (f.xs.size < 2) return emptyList()
        val out = ArrayList<Factor>(f.xs.size - 1)
        val ascending = f.direction == Monotone.Direction.Increasing
        val strict = f.strict
        for (i in 0 until f.xs.size - 1) {
            // ascending non-strict:  xs[i+1] - xs[i] >= 0
            // ascending strict:      xs[i+1] - xs[i] >= 1
            // descending non-strict: xs[i] - xs[i+1] >= 0
            // descending strict:     xs[i] - xs[i+1] >= 1
            val (a, b) = if (ascending) f.xs[i + 1] to f.xs[i] else f.xs[i] to f.xs[i + 1]
            out.add(Linear(
                coeffs = intArrayOf(1, -1),
                vars = intArrayOf(a, b),
                op = LinearOp.GE,
                bound = if (strict) 1 else 0,
            ))
        }
        return out
    }

    /** `value_precede(s, t, xs)`: t may only appear in xs after s has appeared.
     *  Decompose to: for each position i, if xs[i] = t then ∃ j < i with xs[j] = s.
     *  Equivalent reformulation via aux "seen_s_by_i" cumulative bool:
     *  - seen_s_i ↔ ∃ j ≤ i : xs[j] = s   (computed via reified-eq chain)
     *  - constraint: xs[i] = t  ⇒  seen_s_{i-1} = true
     *  We emit reified linears for the seen-s indicators and clauses for the chain. */
    private fun decomposeValuePrecede(f: ValuePrecede, ctx: DecompositionContext): List<Factor> {
        val out = ArrayList<Factor>()
        val n = f.xs.size
        // eqS[i] ↔ (xs[i] = s); eqT[i] ↔ (xs[i] = t).
        val eqS = IntArray(n) { ctx.freshBool() }
        val eqT = IntArray(n) { ctx.freshBool() }
        for (i in 0 until n) {
            out.add(ReifiedLinear(eqS[i], intArrayOf(1), intArrayOf(f.xs[i]), LinearOp.EQ, f.s))
            out.add(ReifiedLinear(eqT[i], intArrayOf(1), intArrayOf(f.xs[i]), LinearOp.EQ, f.t))
        }
        // seenS[i] ↔ (∃ j ≤ i : eqS[j]). Chain: seenS[0] ↔ eqS[0]; seenS[i] ↔ seenS[i-1] ∨ eqS[i].
        val seenS = IntArray(n) { ctx.freshBool() }
        // seenS[0] = eqS[0]:  (seenS[0] ∨ ¬eqS[0]) ∧ (¬seenS[0] ∨ eqS[0])
        out.add(Clause(intArrayOf(Lit.make(seenS[0], true), Lit.make(eqS[0], false))))
        out.add(Clause(intArrayOf(Lit.make(seenS[0], false), Lit.make(eqS[0], true))))
        for (i in 1 until n) {
            // seenS[i] = seenS[i-1] ∨ eqS[i]:
            //   (¬seenS[i] ∨ seenS[i-1] ∨ eqS[i])
            //   (seenS[i] ∨ ¬seenS[i-1])
            //   (seenS[i] ∨ ¬eqS[i])
            out.add(Clause(intArrayOf(Lit.make(seenS[i], false), Lit.make(seenS[i - 1], true), Lit.make(eqS[i], true))))
            out.add(Clause(intArrayOf(Lit.make(seenS[i], true), Lit.make(seenS[i - 1], false))))
            out.add(Clause(intArrayOf(Lit.make(seenS[i], true), Lit.make(eqS[i], false))))
        }
        // Constraint: eqT[i] ⇒ seenS[i-1] for i ≥ 1; eqT[0] must be false (no prior s).
        out.add(Clause(intArrayOf(Lit.make(eqT[0], false))))
        for (i in 1 until n) {
            out.add(Clause(intArrayOf(Lit.make(eqT[i], false), Lit.make(seenS[i - 1], true))))
        }
        return out
    }

    /** `member(xs, y)` — y must equal at least one of xs. Decompose via reified-eq
     *  aux: `eq[i] ↔ (xs[i] = y)`, then `Σ eq[i] ≥ 1` (encoded as a Cardinality with
     *  min=1, max=|xs|). */
    private fun decomposeMember(f: Member, ctx: DecompositionContext): List<Factor> {
        val out = ArrayList<Factor>(f.xs.size + 1)
        val eqLits = IntArray(f.xs.size)
        for (i in f.xs.indices) {
            val aux = ctx.freshBool()
            eqLits[i] = Lit.make(aux, true)
            // eq[i] ↔ (xs[i] - y = 0). Use ReifiedLinear with coeffs [1, -1].
            out.add(ReifiedLinear(aux, intArrayOf(1, -1), intArrayOf(f.xs[i], f.y), LinearOp.EQ, 0))
        }
        out.add(Cardinality(eqLits, min = 1, max = f.xs.size))
        return out
    }

    /** Pairwise NE over [xs]: one Linear NE factor per pair. */
    private fun decomposePairwiseNE(xs: IntArray): List<Factor> {
        val out = ArrayList<Factor>(xs.size * (xs.size - 1) / 2)
        for (i in 0 until xs.size - 1) {
            for (j in i + 1 until xs.size) {
                out.add(Linear(intArrayOf(1, -1), intArrayOf(xs[i], xs[j]), LinearOp.NE, 0))
            }
        }
        return out
    }
}
