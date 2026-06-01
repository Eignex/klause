package com.eignex.klause.solver.decompose

import com.eignex.klause.ast.PbOp
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.factor.AllDifferentExcept
import com.eignex.klause.solver.factor.AllDifferentExceptZero
import com.eignex.klause.solver.factor.AllEqual
import com.eignex.klause.solver.factor.Among
import com.eignex.klause.solver.factor.ArgMinMax
import com.eignex.klause.solver.factor.ArgSort
import com.eignex.klause.solver.factor.ArrayMinMax
import com.eignex.klause.solver.factor.BinPacking
import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.factor.Circuit
import com.eignex.klause.solver.factor.Clause
import com.eignex.klause.solver.factor.Count
import com.eignex.klause.solver.factor.Cumulative
import com.eignex.klause.solver.factor.Diffn
import com.eignex.klause.solver.factor.Disjunctive
import com.eignex.klause.solver.factor.Geost
import com.eignex.klause.solver.factor.GlobalCardinality
import com.eignex.klause.solver.factor.Inverse
import com.eignex.klause.solver.factor.Knapsack
import com.eignex.klause.solver.factor.LexLess
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.factor.Mdd
import com.eignex.klause.solver.factor.Member
import com.eignex.klause.solver.factor.MinCostFlow
import com.eignex.klause.solver.factor.Monotone
import com.eignex.klause.solver.factor.NValue
import com.eignex.klause.solver.factor.Path
import com.eignex.klause.solver.factor.Product
import com.eignex.klause.solver.factor.PseudoBoolean
import com.eignex.klause.solver.factor.Regular
import com.eignex.klause.solver.factor.ReifiedCardinality
import com.eignex.klause.solver.factor.ReifiedLinear
import com.eignex.klause.solver.factor.ReifiedPseudoBoolean
import com.eignex.klause.solver.factor.Sequence
import com.eignex.klause.solver.factor.Sort
import com.eignex.klause.solver.factor.Subcircuit
import com.eignex.klause.solver.factor.SymmetricAllDifferent
import com.eignex.klause.solver.factor.Table
import com.eignex.klause.solver.factor.Tree
import com.eignex.klause.solver.factor.ValuePrecede

/**
 * Allocator hook for fresh aux variables used by [FactorDecomposer]. Backends that
 * route unsupported globals through decomposition implement this against their own
 * encoding state (e.g. the Z3 translator allocates a Z3 const declaration alongside
 * the klause-side var id).
 */
internal interface DecompositionContext {
    /** Allocate and return the id of a fresh Boolean variable. */
    fun freshBool(): Int

    /** Allocate and return the id of a fresh integer variable over [domain]. */
    fun freshInt(domain: IntDomain): Int

    /** Current domain of int var [id]. Used by decompositions that need to enumerate
     *  reified equalities across a variable's possible values (e.g. NValue,
     *  GlobalCardinality, set-membership). */
    fun intDomainOf(id: Int): IntDomain
}

/**
 * Lowers a complex global factor to a list of mid-level IR factors that arithmetic
 * backends consume natively. Mid-IR is the small set:
 *  - [Clause], [Linear], [Cardinality], [PseudoBoolean],
 *    [Product],
 *  - [ReifiedLinear], [ReifiedCardinality],
 *    [ReifiedPseudoBoolean].
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
internal object FactorDecomposer {

    /** Returns mid-IR replacement for [f], or `null` when no decomposition is known.
     *  Mid-IR factors map to themselves; callers should check via [isMidIR] before
     *  calling decompose to avoid pointless lookups. */
    fun decompose(f: Factor, ctx: DecompositionContext): List<Factor>? = when (f) {
        is AllEqual -> decomposeAllEqual(f)

        is AllDifferentExceptZero -> decomposeAllDifferentExceptZero(f, ctx)

        is AllDifferentExcept -> decomposeAllDifferentExcept(f, ctx)

        is Monotone -> decomposeMonotone(f)

        is ValuePrecede -> decomposeValuePrecede(f, ctx)

        is Member -> decomposeMember(f, ctx)

        is Among -> decomposeAmong(f, ctx)

        is Count -> decomposeCount(f, ctx)

        is NValue -> decomposeNValue(f, ctx)

        is SymmetricAllDifferent -> decomposeSymmetricAllDifferent(f, ctx)

        is GlobalCardinality -> decomposeGlobalCardinality(f, ctx)

        // Tier 3: arithmetic
        is Knapsack -> decomposeKnapsack(f)

        is ArrayMinMax -> decomposeArrayMinMax(f, ctx)

        is ArgMinMax -> decomposeArgMinMax(f, ctx)

        is BinPacking -> decomposeBinPacking(f, ctx)

        is MinCostFlow -> decomposeMinCostFlow(f)

        // Tier 4: comparison / connectivity / misc
        is Inverse -> decomposeInverse(f, ctx)

        is LexLess -> decomposeLexLess(f, ctx)

        is Sequence -> decomposeSequence(f, ctx)

        // Tier 4 (automaton + geometry)
        is Table -> decomposeTable(f, ctx)

        is Regular -> decomposeRegular(f, ctx)

        is Mdd -> decomposeMdd(f, ctx)

        is Disjunctive -> decomposeDisjunctive(f, ctx)

        is Diffn -> decomposeDiffn(f, ctx)

        is Geost -> decomposeGeost(f, ctx)

        is Cumulative -> decomposeCumulative(f, ctx)

        is Circuit -> decomposeCircuit(f, ctx)

        is Subcircuit -> decomposeSubcircuit(f, ctx)

        is Sort -> decomposeSort(f, ctx)

        is ArgSort -> decomposeArgSort(f, ctx)

        is Path -> decomposePath(f, ctx)

        is Tree -> decomposeTree(f, ctx)

        else -> null
    }

    /** True for factor types every arithmetic backend supports without further
     *  decomposition. Maintained alongside the per-backend native translators — adding
     *  a new mid-IR type means updating every backend's `accepts` table too. */
    fun isMidIR(f: Factor): Boolean = when (f) {
        is Clause,
        is Linear,
        is Cardinality,
        is PseudoBoolean,
        is Product,
        is ReifiedLinear,
        is ReifiedCardinality,
        is ReifiedPseudoBoolean,
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

    /** `all_different_except(xs, except)` → for each pair (i, j), require
     *  `(xᵢ ∈ except) ∨ (xⱼ ∈ except) ∨ (xᵢ ≠ xⱼ)`. We allocate per-var "in-except"
     *  aux and per-pair "ne" aux, then assert the disjunction as a Clause. */
    private fun decomposeAllDifferentExcept(f: AllDifferentExcept, ctx: DecompositionContext): List<Factor> {
        if (f.except.isEmpty()) return decomposePairwiseNE(f.xs)
        return decomposeGatedPairwiseNE(f.xs, f.except, ctx)
    }

    /** `all_different_except_zero(xs)` is the singleton-`{0}` exception case. */
    private fun decomposeAllDifferentExceptZero(f: AllDifferentExceptZero, ctx: DecompositionContext): List<Factor> =
        decomposeGatedPairwiseNE(f.xs, intArrayOf(0), ctx)

    /** Shared gated-pairwise-NE encoding used by AllDifferentExcept and
     *  AllDifferentExceptZero. For each var an aux "is in except" bool is reified
     *  via the disjunction of equality reifications across the exception set; for
     *  each pair an aux "ne" bool is reified as the negation of equality, then a
     *  Clause asserts that for each pair at least one of the three release
     *  conditions holds. */
    private fun decomposeGatedPairwiseNE(xs: IntArray, except: IntArray, ctx: DecompositionContext): List<Factor> {
        val out = ArrayList<Factor>()
        // inExcept[i] = OR over v in except of (xs[i] = v).
        val inExcept = IntArray(xs.size) { ctx.freshBool() }
        for (i in xs.indices) {
            val eqLits = IntArray(except.size) { v ->
                val eqV = ctx.freshBool()
                out.add(ReifiedLinear(eqV, intArrayOf(1), intArrayOf(xs[i]), LinearOp.EQ, except[v]))
                Lit.make(eqV, true)
            }
            // inExcept[i] ↔ (Σ eqLits ≥ 1).
            out.add(ReifiedCardinality(inExcept[i], eqLits, min = 1, max = except.size))
        }
        // For each pair, ne_ij ↔ xs[i] ≠ xs[j]; assert (inExcept[i] ∨ inExcept[j] ∨ ne_ij).
        for (i in 0 until xs.size - 1) {
            for (j in i + 1 until xs.size) {
                val neAux = ctx.freshBool()
                out.add(ReifiedLinear(neAux, intArrayOf(1, -1), intArrayOf(xs[i], xs[j]), LinearOp.NE, 0))
                out.add(
                    Clause(
                        intArrayOf(
                            Lit.make(inExcept[i], true),
                            Lit.make(inExcept[j], true),
                            Lit.make(neAux, true),
                        ),
                    ),
                )
            }
        }
        return out
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
            out.add(
                Linear(
                    coeffs = intArrayOf(1, -1),
                    vars = intArrayOf(a, b),
                    op = LinearOp.GE,
                    bound = if (strict) 1 else 0,
                ),
            )
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

    /** `among(xs, S) = n` — exactly `n` of `xs` take values in `S`. Per index, reified
     *  membership in `S` via OR of equalities; total is fixed via Cardinality. */
    private fun decomposeAmong(f: Among, ctx: DecompositionContext): List<Factor> {
        val out = ArrayList<Factor>()
        val inLits = IntArray(f.xs.size) { ctx.freshBool() }
        for (i in f.xs.indices) {
            val eqLits = IntArray(f.values.size) { vi ->
                val eqV = ctx.freshBool()
                out.add(ReifiedLinear(eqV, intArrayOf(1), intArrayOf(f.xs[i]), LinearOp.EQ, f.values[vi]))
                Lit.make(eqV, true)
            }
            out.add(ReifiedCardinality(inLits[i], eqLits, min = 1, max = f.values.size))
        }
        val inLitsPositive = IntArray(f.xs.size) { Lit.make(inLits[it], true) }
        out.add(Cardinality(inLitsPositive, min = f.n, max = f.n))
        return out
    }

    /** `count(xs, v, op, n)` — number of `xs[i] = v` ⟨op⟩ `n`. With `presents` non-empty,
     *  the contributing lits are gated by presence: `inCount_i ↔ present_i ∧ (xs[i] = v)`. */
    private fun decomposeCount(f: Count, ctx: DecompositionContext): List<Factor>? {
        val out = ArrayList<Factor>()
        val eqLits = IntArray(f.xs.size) { i ->
            val eq = ctx.freshBool()
            out.add(ReifiedLinear(eq, intArrayOf(1), intArrayOf(f.xs[i]), LinearOp.EQ, f.v))
            if (f.presents.isEmpty()) {
                Lit.make(eq, true)
            } else {
                val pres = f.presents[i]
                val gated = ctx.freshBool()
                out.add(Clause(intArrayOf(Lit.make(gated, false), Lit.make(eq, true))))
                out.add(Clause(intArrayOf(Lit.make(gated, false), pres)))
                out.add(Clause(intArrayOf(Lit.make(gated, true), Lit.make(eq, false), Lit.negate(pres))))
                Lit.make(gated, true)
            }
        }
        val xn = f.xs.size
        val (lo, hi) = when (f.op) {
            Count.Op.Eq -> f.n to f.n

            Count.Op.Le -> 0 to f.n

            Count.Op.Lt -> 0 to (f.n - 1)

            Count.Op.Ge -> f.n to xn

            Count.Op.Gt -> (f.n + 1) to xn

            Count.Op.Ne -> {
                // PbOp doesn't carry NE; split into the disjunction
                // `(Σ ≤ n−1) ∨ (Σ ≥ n+1)` via two reified cardinalities + a clause.
                val lo = ctx.freshBool()
                val hi = ctx.freshBool()
                if (f.n - 1 >= 0) {
                    out.add(ReifiedCardinality(lo, eqLits, min = 0, max = f.n - 1))
                } else {
                    // n - 1 < 0 → Σ ≤ n-1 is impossible (Σ ≥ 0). Pin lo = false.
                    out.add(Clause(intArrayOf(Lit.make(lo, false))))
                }
                if (f.n + 1 <= xn) {
                    out.add(ReifiedCardinality(hi, eqLits, min = f.n + 1, max = xn))
                } else {
                    // n + 1 > xn → Σ ≥ n+1 is impossible. Pin hi = false.
                    out.add(Clause(intArrayOf(Lit.make(hi, false))))
                }
                out.add(Clause(intArrayOf(Lit.make(lo, true), Lit.make(hi, true))))
                return out
            }
        }
        if (lo > hi || hi < 0 || lo > xn) {
            // Bound interval doesn't intersect [0, xn] → always unsat. Emit a unit
            // clause that's unsatisfiable (a fresh aux pinned both ways) to signal it.
            val sat = ctx.freshBool()
            out.add(Clause(intArrayOf(Lit.make(sat, true))))
            out.add(Clause(intArrayOf(Lit.make(sat, false))))
            return out
        }
        out.add(Cardinality(eqLits, min = lo.coerceAtLeast(0), max = hi.coerceAtMost(xn)))
        return out
    }

    /** `nvalue(n, xs, mode)` — number of distinct values in `xs` ⟨mode⟩ `n`. Iterates
     *  over the union of `xs` domains, allocates a "used_v" aux per candidate, and
     *  constrains the sum via Cardinality with mode-driven bounds. */
    private fun decomposeNValue(f: NValue, ctx: DecompositionContext): List<Factor>? {
        // Union of domain extremes.
        var lo = Int.MAX_VALUE
        var hi = Int.MIN_VALUE
        for (x in f.xs) {
            val d = ctx.intDomainOf(x)
            if (d.min < lo) lo = d.min
            if (d.max > hi) hi = d.max
        }
        if (lo > hi) return emptyList() // empty xs is impossible per factor invariant
        val out = ArrayList<Factor>()
        val usedLits = ArrayList<Int>()
        for (v in lo..hi) {
            // For each xs[i], reified eq_iv ↔ (xs[i] = v); gate by presence when opt-aware.
            val eqLits = IntArray(f.xs.size) { i ->
                val eq = ctx.freshBool()
                out.add(ReifiedLinear(eq, intArrayOf(1), intArrayOf(f.xs[i]), LinearOp.EQ, v))
                if (f.presents.isEmpty()) {
                    Lit.make(eq, true)
                } else {
                    val pres = f.presents[i]
                    val gated = ctx.freshBool()
                    out.add(Clause(intArrayOf(Lit.make(gated, false), Lit.make(eq, true))))
                    out.add(Clause(intArrayOf(Lit.make(gated, false), pres)))
                    out.add(Clause(intArrayOf(Lit.make(gated, true), Lit.make(eq, false), Lit.negate(pres))))
                    Lit.make(gated, true)
                }
            }
            // used_v ↔ (sum eq_iv ≥ 1).
            val usedV = ctx.freshBool()
            out.add(ReifiedCardinality(usedV, eqLits, min = 1, max = f.xs.size))
            usedLits.add(Lit.make(usedV, true))
        }
        val usedArr = usedLits.toIntArray()
        val n = f.n
        val card = when (f.mode) {
            NValue.Mode.Eq -> Cardinality(usedArr, min = n, max = n)
            NValue.Mode.AtLeast -> Cardinality(usedArr, min = n.coerceAtLeast(0), max = usedArr.size)
            NValue.Mode.AtMost -> Cardinality(usedArr, min = 0, max = n.coerceAtMost(usedArr.size))
        }
        out.add(card)
        return out
    }

    /** `symmetric_alldifferent(xs, offset)` — permutation with `xs[i] = j+offset ⟺
     *  xs[j] = i+offset`. We assert pairwise NE plus the involution biconditional via
     *  reified equalities. */
    private fun decomposeSymmetricAllDifferent(f: SymmetricAllDifferent, ctx: DecompositionContext): List<Factor> {
        val out = ArrayList<Factor>()
        // Pairwise NE
        for (i in 0 until f.xs.size - 1) {
            for (j in i + 1 until f.xs.size) {
                out.add(Linear(intArrayOf(1, -1), intArrayOf(f.xs[i], f.xs[j]), LinearOp.NE, 0))
            }
        }
        // For i < j: aux ij ↔ (xs[i] = j+offset); aux ji ↔ (xs[j] = i+offset); equate.
        for (i in f.xs.indices) {
            for (j in f.xs.indices) {
                if (i == j) continue
                val auxIJ = ctx.freshBool()
                val auxJI = ctx.freshBool()
                out.add(ReifiedLinear(auxIJ, intArrayOf(1), intArrayOf(f.xs[i]), LinearOp.EQ, j + f.indexOffset))
                out.add(ReifiedLinear(auxJI, intArrayOf(1), intArrayOf(f.xs[j]), LinearOp.EQ, i + f.indexOffset))
                // aux_ij ↔ aux_ji  via two clauses: (¬a ∨ b) ∧ (a ∨ ¬b).
                out.add(Clause(intArrayOf(Lit.make(auxIJ, false), Lit.make(auxJI, true))))
                out.add(Clause(intArrayOf(Lit.make(auxIJ, true), Lit.make(auxJI, false))))
            }
        }
        return out
    }

    // -------- Tier 3: arithmetic --------

    /** `knapsack(weights, profits, xs, w, p)` — `w = Σ weights·xs` and `p = Σ profits·xs`,
     *  where `w` and `p` are int var ids. Each side is one Linear EQ with the var
     *  pulled to the LHS via a `-1` coefficient. */
    private fun decomposeKnapsack(f: Knapsack): List<Factor> {
        val n = f.xs.size
        val wCoeffs = IntArray(n + 1).apply {
            for (i in 0 until n) this[i] = f.weights[i]
            this[n] = -1
        }
        val wVars = IntArray(n + 1).apply {
            for (i in 0 until n) this[i] = f.xs[i]
            this[n] = f.w
        }
        val pCoeffs = IntArray(n + 1).apply {
            for (i in 0 until n) this[i] = f.profits[i]
            this[n] = -1
        }
        val pVars = IntArray(n + 1).apply {
            for (i in 0 until n) this[i] = f.xs[i]
            this[n] = f.p
        }
        return listOf(
            Linear(wCoeffs, wVars, LinearOp.EQ, 0),
            Linear(pCoeffs, pVars, LinearOp.EQ, 0),
        )
    }

    /** `result = max(xs)` (when [max] true) or `result = min(xs)`. Encoded as:
     *  - `max`: `result ≥ xs[i]` ∀i, plus `result = xs[j]` for at least one j (cardinality
     *    of reified equalities ≥ 1).
     *  - `min`: symmetric with `≤`. */
    private fun decomposeArrayMinMax(f: ArrayMinMax, ctx: DecompositionContext): List<Factor> {
        val out = ArrayList<Factor>()
        val eqLits = IntArray(f.xs.size) { i ->
            val aux = ctx.freshBool()
            out.add(ReifiedLinear(aux, intArrayOf(1, -1), intArrayOf(f.result, f.xs[i]), LinearOp.EQ, 0))
            Lit.make(aux, true)
        }
        for (i in f.xs.indices) {
            val (lhs, rhs) = if (f.max) f.result to f.xs[i] else f.xs[i] to f.result
            out.add(Linear(intArrayOf(1, -1), intArrayOf(lhs, rhs), LinearOp.GE, 0))
        }
        out.add(Cardinality(eqLits, min = 1, max = f.xs.size))
        return out
    }

    /** `idx = argmin(xs)` / `argmax(xs)` with `idx ∈ [indexOffset, indexOffset+|xs|−1]`.
     *  Decompose via:
     *  - aux winner indicators `win_i ↔ (xs[i] is the extreme)` — i.e. `xs[i] ≥ xs[j]` ∀j
     *    for max (symmetric for min), encoded via `result_value` aux + ArrayMinMax-style
     *    inequalities;
     *  - aux `eqIdx_i ↔ (idx = i + indexOffset)`;
     *  - `eqIdx_i → win_i` plus exactly-one-of-eqIdx selected. */
    private fun decomposeArgMinMax(f: ArgMinMax, ctx: DecompositionContext): List<Factor> {
        val out = ArrayList<Factor>()
        // result_value bounds = union of xs domains.
        var lo = Int.MAX_VALUE
        var hi = Int.MIN_VALUE
        for (x in f.xs) {
            val d = ctx.intDomainOf(x)
            if (d.min < lo) lo = d.min
            if (d.max > hi) hi = d.max
        }
        val resultValue = ctx.freshInt(IntDomain(lo, hi))
        // result_value relates to xs[i] via ≥ or ≤ per max/min, plus an equality witness.
        val winLits = IntArray(f.xs.size) { i ->
            val aux = ctx.freshBool()
            out.add(ReifiedLinear(aux, intArrayOf(1, -1), intArrayOf(resultValue, f.xs[i]), LinearOp.EQ, 0))
            Lit.make(aux, true)
        }
        for (i in f.xs.indices) {
            val (a, b) = if (f.max) resultValue to f.xs[i] else f.xs[i] to resultValue
            out.add(Linear(intArrayOf(1, -1), intArrayOf(a, b), LinearOp.GE, 0))
        }
        out.add(Cardinality(winLits, min = 1, max = f.xs.size))
        // eqIdx_i ↔ (idx = i + indexOffset); exactly one chosen.
        val eqIdxLits = IntArray(f.xs.size) { i ->
            val aux = ctx.freshBool()
            out.add(ReifiedLinear(aux, intArrayOf(1), intArrayOf(f.idx), LinearOp.EQ, i + f.indexOffset))
            Lit.make(aux, true)
        }
        out.add(Cardinality(eqIdxLits, min = 1, max = 1))
        // eqIdx_i → win_i:  (¬eqIdx_i ∨ win_i)
        for (i in f.xs.indices) {
            out.add(Clause(intArrayOf(Lit.make(Lit.variable(eqIdxLits[i]), false), winLits[i])))
        }
        return out
    }

    /** `bin_packing(bins, weights, mode, ...)`. All three modes decompose to
     *  per-(item, bin) reified equalities plus per-bin sum constraints. UniformCapacity
     *  and PerBinCapacity become a `≤ cap` PB; LoadVars equates the per-bin PB sum to the
     *  load var by enumerating its domain and tying `(Σ = k) ↔ (load = k)` per value. */
    private fun decomposeBinPacking(f: BinPacking, ctx: DecompositionContext): List<Factor>? {
        val out = ArrayList<Factor>()
        val inBin = Array(f.numBins) { IntArray(f.bins.size) }
        for (i in f.bins.indices) {
            for (k in 0 until f.numBins) {
                val aux = ctx.freshBool()
                out.add(ReifiedLinear(aux, intArrayOf(1), intArrayOf(f.bins[i]), LinearOp.EQ, k + f.binOffset))
                inBin[k][i] = aux
            }
            val perItem = IntArray(f.numBins) { Lit.make(inBin[it][i], true) }
            out.add(Cardinality(perItem, min = 1, max = 1))
        }
        for (k in 0 until f.numBins) {
            val lits = IntArray(f.bins.size) { Lit.make(inBin[k][it], true) }
            when (f.mode) {
                BinPacking.Mode.UniformCapacity ->
                    out.add(PseudoBoolean(f.weights.copyOf(), lits, PbOp.LE, f.uniformCapacity))

                BinPacking.Mode.PerBinCapacity ->
                    out.add(PseudoBoolean(f.weights.copyOf(), lits, PbOp.LE, f.capacities!![k]))

                BinPacking.Mode.LoadVars -> {
                    val loadVar = f.loadVars!![k]
                    val dom = ctx.intDomainOf(loadVar)
                    for (v in dom.min..dom.max) {
                        val sumEqV = ctx.freshBool()
                        out.add(ReifiedPseudoBoolean(sumEqV, f.weights.copyOf(), lits, PbOp.EQ, v))
                        val loadEqV = ctx.freshBool()
                        out.add(ReifiedLinear(loadEqV, intArrayOf(1), intArrayOf(loadVar), LinearOp.EQ, v))
                        out.add(Clause(intArrayOf(Lit.make(sumEqV, false), Lit.make(loadEqV, true))))
                        out.add(Clause(intArrayOf(Lit.make(sumEqV, true), Lit.make(loadEqV, false))))
                    }
                }
            }
        }
        return out
    }

    /** `min_cost_flow(...)` — per-node Σ_in flow − Σ_out flow = balance, plus cost = Σ
     *  weight·flow when a cost variable is provided. Both equations are Linear EQ. */
    private fun decomposeMinCostFlow(f: MinCostFlow): List<Factor> {
        val out = ArrayList<Factor>()
        val inArcs = Array(f.numNodes) { ArrayList<Int>() }
        val outArcs = Array(f.numNodes) { ArrayList<Int>() }
        for (a in f.arcFrom.indices) {
            outArcs[f.arcFrom[a] - f.nodeOffset].add(a)
            inArcs[f.arcTo[a] - f.nodeOffset].add(a)
        }
        for (n in 0 until f.numNodes) {
            // Σ in − Σ out = balance[n].
            val ins = inArcs[n]
            val outs = outArcs[n]
            val sz = ins.size + outs.size
            if (sz == 0) continue
            val coeffs = IntArray(sz)
            val vars = IntArray(sz)
            var w = 0
            for (a in ins) {
                coeffs[w] = 1
                vars[w] = f.flow[a]
                w++
            }
            for (a in outs) {
                coeffs[w] = -1
                vars[w] = f.flow[a]
                w++
            }
            out.add(Linear(coeffs, vars, LinearOp.EQ, f.balance[n]))
        }
        if (f.cost >= 0 && f.weight != null) {
            val sz = f.flow.size + 1
            val coeffs = IntArray(sz)
            val vars = IntArray(sz)
            for (i in f.flow.indices) {
                coeffs[i] = f.weight[i]
                vars[i] = f.flow[i]
            }
            coeffs[f.flow.size] = -1
            vars[f.flow.size] = f.cost
            out.add(Linear(coeffs, vars, LinearOp.EQ, 0))
        }
        return out
    }

    // -------- Tier 4: comparison / connectivity / misc --------

    /** `inverse(f, g, fOffset, gOffset)` — `f[i] = j+fOffset ⟺ g[j] = i+gOffset`. Same
     *  shape as [SymmetricAllDifferent] but across two arrays. */
    private fun decomposeInverse(fac: Inverse, ctx: DecompositionContext): List<Factor> {
        val out = ArrayList<Factor>()
        val nf = fac.f.size
        val ng = fac.g.size
        for (i in 0 until nf) {
            for (j in 0 until ng) {
                val auxF = ctx.freshBool()
                val auxG = ctx.freshBool()
                out.add(ReifiedLinear(auxF, intArrayOf(1), intArrayOf(fac.f[i]), LinearOp.EQ, j + fac.fOffset))
                out.add(ReifiedLinear(auxG, intArrayOf(1), intArrayOf(fac.g[j]), LinearOp.EQ, i + fac.gOffset))
                out.add(Clause(intArrayOf(Lit.make(auxF, false), Lit.make(auxG, true))))
                out.add(Clause(intArrayOf(Lit.make(auxF, true), Lit.make(auxG, false))))
            }
        }
        return out
    }

    /** `lex_less(xs, ys, strict)` — xs is lexicographically ≤ ys (strict ⇒ <). Encoded
     *  via a "first-difference" aux: per position i, aux `diff_i ↔ (xs[i] ≠ ys[i])`,
     *  `first_i ↔ (∀ j < i: ¬diff_j) ∧ diff_i`. When first_i is true: xs[i] < ys[i].
     *  When all diff_i are false: xs is prefix-equal to ys (admissible iff non-strict
     *  OR xs is strictly shorter). For equal-length non-strict we require allow-equal;
     *  strict requires *some* position to differ. */
    private fun decomposeLexLess(fac: LexLess, ctx: DecompositionContext): List<Factor> {
        val out = ArrayList<Factor>()
        val n = minOf(fac.xs.size, fac.ys.size)
        if (n == 0) {
            // Empty vector pair: strict requires ys non-empty; non-strict trivially holds.
            if (fac.strict && fac.ys.size > fac.xs.size) return emptyList()
            if (fac.strict) {
                // Force-unsat unit clauses.
                val sat = ctx.freshBool()
                out.add(Clause(intArrayOf(Lit.make(sat, true))))
                out.add(Clause(intArrayOf(Lit.make(sat, false))))
            }
            return out
        }
        // diff_i ↔ (xs[i] ≠ ys[i]); lt_i ↔ (xs[i] < ys[i]); eq_i ↔ (xs[i] = ys[i]).
        val lt = IntArray(n) { ctx.freshBool() }
        val eq = IntArray(n) { ctx.freshBool() }
        for (i in 0 until n) {
            out.add(ReifiedLinear(lt[i], intArrayOf(1, -1), intArrayOf(fac.xs[i], fac.ys[i]), LinearOp.LE, -1))
            out.add(ReifiedLinear(eq[i], intArrayOf(1, -1), intArrayOf(fac.xs[i], fac.ys[i]), LinearOp.EQ, 0))
        }
        // chain: at position i, xs is "less" if eq holds for 0..i-1 and lt holds at i.
        // We require: ∃ i ∈ [0, n): (eq[0] ∧ … ∧ eq[i-1] ∧ lt[i]) OR (strict ⇒ false; non-strict ⇒ allowed
        //   to have all eq through n).
        // Sequential encoding: prefixEq[i] ↔ (eq[0] ∧ … ∧ eq[i-1]).  prefixEq[0] = true.
        val prefixEq = IntArray(n + 1) { ctx.freshBool() }
        // pin prefixEq[0] = true
        out.add(Clause(intArrayOf(Lit.make(prefixEq[0], true))))
        for (i in 0 until n) {
            // prefixEq[i+1] ↔ (prefixEq[i] ∧ eq[i])
            //   (¬prefixEq[i+1] ∨ prefixEq[i])
            //   (¬prefixEq[i+1] ∨ eq[i])
            //   (prefixEq[i+1] ∨ ¬prefixEq[i] ∨ ¬eq[i])
            out.add(Clause(intArrayOf(Lit.make(prefixEq[i + 1], false), Lit.make(prefixEq[i], true))))
            out.add(Clause(intArrayOf(Lit.make(prefixEq[i + 1], false), Lit.make(eq[i], true))))
            out.add(
                Clause(
                    intArrayOf(Lit.make(prefixEq[i + 1], true), Lit.make(prefixEq[i], false), Lit.make(eq[i], false)),
                ),
            )
        }
        // win_i ↔ prefixEq[i] ∧ lt[i].
        val win = IntArray(n) { ctx.freshBool() }
        for (i in 0 until n) {
            out.add(Clause(intArrayOf(Lit.make(win[i], false), Lit.make(prefixEq[i], true))))
            out.add(Clause(intArrayOf(Lit.make(win[i], false), Lit.make(lt[i], true))))
            out.add(Clause(intArrayOf(Lit.make(win[i], true), Lit.make(prefixEq[i], false), Lit.make(lt[i], false))))
        }
        // Goal: OR(win_i for i) OR (non-strict ∧ prefixEq[n]).
        val winLits = ArrayList<Int>()
        for (i in 0 until n) winLits.add(Lit.make(win[i], true))
        if (!fac.strict) winLits.add(Lit.make(prefixEq[n], true))
        // Plus length asymmetry: if non-strict and xs.size < ys.size and prefixEq[n] is true,
        // xs is admissibly shorter — already covered. If strict and xs.size < ys.size, that's
        // also a win position (xs is a strict prefix). Handle by adding prefixEq[n] for strict
        // when xs is strictly shorter than ys.
        if (fac.strict && fac.xs.size < fac.ys.size) winLits.add(Lit.make(prefixEq[n], true))
        out.add(Clause(winLits.toIntArray()))
        return out
    }

    /** `sequence(low, high, k, xs, values)` — every length-k window of xs has between
     *  `low` and `high` entries in `values`. Decompose via reified membership per index
     *  and per-window Cardinality. */
    private fun decomposeSequence(fac: Sequence, ctx: DecompositionContext): List<Factor>? {
        if (fac.xs.size < fac.k) return null
        val out = ArrayList<Factor>()
        val values = fac.values
        // For each index i, in_i ↔ (xs[i] ∈ values).
        val inLits = IntArray(fac.xs.size) { ctx.freshBool() }
        for (i in fac.xs.indices) {
            val eqLits = IntArray(values.size) { vi ->
                val aux = ctx.freshBool()
                out.add(ReifiedLinear(aux, intArrayOf(1), intArrayOf(fac.xs[i]), LinearOp.EQ, values[vi]))
                Lit.make(aux, true)
            }
            out.add(ReifiedCardinality(inLits[i], eqLits, min = 1, max = values.size))
        }
        // For each window [start, start+k): low ≤ Σ in_{start..start+k-1} ≤ high.
        for (start in 0..fac.xs.size - fac.k) {
            val windowLits = IntArray(fac.k) { Lit.make(inLits[start + it], true) }
            out.add(Cardinality(windowLits, min = fac.low, max = fac.high))
        }
        return out
    }

    // -------- Tier 4 (automaton / geometry) --------

    /** `table(xs, tuples)` — xs takes one of the allowed tuples. Tuple rows live in
     *  [Table.tuples] row-major. Decompose: per tuple t, aux `match_t ↔ Λ (xs[i] = t[i])`;
     *  Σ match_t ≥ 1. */
    private fun decomposeTable(f: Table, ctx: DecompositionContext): List<Factor> {
        val out = ArrayList<Factor>()
        val arity = f.xs.size
        require(f.tuples.size % arity == 0) { "Table: tuples size not a multiple of xs size" }
        val numTuples = f.tuples.size / arity
        val matchLits = IntArray(numTuples)
        for (t in 0 until numTuples) {
            // Per (i, tuple value): aux eq_ti ↔ (xs[i] = tuple[t, i]).
            val eqAux = IntArray(arity) { i ->
                val aux = ctx.freshBool()
                out.add(ReifiedLinear(aux, intArrayOf(1), intArrayOf(f.xs[i]), LinearOp.EQ, f.tuples[t * arity + i]))
                aux
            }
            // match_t ↔ Λ eq_ti — i.e. card(eq_ti) = arity. Use ReifiedCardinality with
            // min = arity, max = arity.
            val matchT = ctx.freshBool()
            val lits = IntArray(arity) { Lit.make(eqAux[it], true) }
            out.add(ReifiedCardinality(matchT, lits, min = arity, max = arity))
            matchLits[t] = Lit.make(matchT, true)
        }
        out.add(Cardinality(matchLits, min = 1, max = numTuples))
        return out
    }

    /** `regular(seq, Q, S, d, q0, F)` — DFA acceptance. Allocate per-position state
     *  vars `q[0..n]` ∈ `[0, numStates]` (0 is the dead state). Pin `q[0] = q0`.
     *  For each transition step, for each (qCurr, sym) pair, aux fires both eqs and a
     *  clause `aux → q[i+1] = nextState(qCurr, sym)`. Final: `q[n] ∈ accepting`. */
    private fun decomposeRegular(f: Regular, ctx: DecompositionContext): List<Factor> {
        val out = ArrayList<Factor>()
        val n = f.seq.size
        val numQ = f.numStates
        val numSymbols = f.alphabetSize
        // q[0..n] aux ints. States are 1-based (0 = dead).
        val q = IntArray(n + 1) {
            ctx.freshInt(IntDomain(0, numQ))
        }
        // q[0] = q0.
        out.add(Linear(intArrayOf(1), intArrayOf(q[0]), LinearOp.EQ, f.q0))
        for (i in 0 until n) {
            for (qCurr in 1..numQ) {
                for (sym in 1..numSymbols) {
                    val nextQ = f.transitions[(qCurr - 1) * numSymbols + (sym - 1)]
                    // aux ↔ (q[i] = qCurr ∧ seq[i] = sym)
                    val eqQ = ctx.freshBool()
                    val eqS = ctx.freshBool()
                    out.add(ReifiedLinear(eqQ, intArrayOf(1), intArrayOf(q[i]), LinearOp.EQ, qCurr))
                    out.add(ReifiedLinear(eqS, intArrayOf(1), intArrayOf(f.seq[i]), LinearOp.EQ, sym))
                    val both = ctx.freshBool()
                    // both ↔ eqQ ∧ eqS
                    out.add(Clause(intArrayOf(Lit.make(both, false), Lit.make(eqQ, true))))
                    out.add(Clause(intArrayOf(Lit.make(both, false), Lit.make(eqS, true))))
                    out.add(Clause(intArrayOf(Lit.make(both, true), Lit.make(eqQ, false), Lit.make(eqS, false))))
                    // both → q[i+1] = nextQ
                    val nextEq = ctx.freshBool()
                    out.add(ReifiedLinear(nextEq, intArrayOf(1), intArrayOf(q[i + 1]), LinearOp.EQ, nextQ))
                    out.add(Clause(intArrayOf(Lit.make(both, false), Lit.make(nextEq, true))))
                }
            }
        }
        // q[n] ∈ accepting — disjunction of equalities (Cardinality of reifications ≥ 1).
        val acceptLits = IntArray(f.accepting.size) { ai ->
            val aux = ctx.freshBool()
            out.add(ReifiedLinear(aux, intArrayOf(1), intArrayOf(q[n]), LinearOp.EQ, f.accepting[ai]))
            Lit.make(aux, true)
        }
        out.add(Cardinality(acceptLits, min = 1, max = f.accepting.size))
        return out
    }

    /** `mdd(seq, …, transitions, initial, accepting)` — layered MDD. transitions is a
     *  flat row-major sequence of `(srcState, value, dstState[, weight])`; `layerStarts`
     *  bounds each layer's rows. Per layer, an aux per transition fires when (state_i =
     *  src ∧ seq[i] = value ∧ state_{i+1} = dst); exactly one transition fires per layer. */
    private fun decomposeMdd(f: Mdd, ctx: DecompositionContext): List<Factor>? {
        val out = ArrayList<Factor>()
        val n = f.seq.size
        // Per-layer state aux: state[i] ∈ [0, numStatesPerLayer[i] - 1].
        val state = IntArray(n + 1) { i ->
            ctx.freshInt(IntDomain(0, f.numStatesPerLayer[i] - 1))
        }
        out.add(Linear(intArrayOf(1), intArrayOf(state[0]), LinearOp.EQ, f.initial))
        val allFires = ArrayList<Int>()
        val allWeights = ArrayList<Int>()
        for (i in 0 until n) {
            val from = f.layerStarts[i]
            val to = f.layerStarts[i + 1]
            val numTx = (to - from) / f.recordStride
            val txAux = IntArray(numTx) { tIdx ->
                val base = from + tIdx * f.recordStride
                val src = f.transitions[base]
                val value = f.transitions[base + 1]
                val dst = f.transitions[base + 2]
                val a1 = ctx.freshBool()
                val a2 = ctx.freshBool()
                val a3 = ctx.freshBool()
                out.add(ReifiedLinear(a1, intArrayOf(1), intArrayOf(state[i]), LinearOp.EQ, src))
                out.add(ReifiedLinear(a2, intArrayOf(1), intArrayOf(f.seq[i]), LinearOp.EQ, value))
                out.add(ReifiedLinear(a3, intArrayOf(1), intArrayOf(state[i + 1]), LinearOp.EQ, dst))
                val fires = ctx.freshBool()
                out.add(Clause(intArrayOf(Lit.make(fires, false), Lit.make(a1, true))))
                out.add(Clause(intArrayOf(Lit.make(fires, false), Lit.make(a2, true))))
                out.add(Clause(intArrayOf(Lit.make(fires, false), Lit.make(a3, true))))
                out.add(
                    Clause(
                        intArrayOf(
                            Lit.make(fires, true),
                            Lit.make(a1, false),
                            Lit.make(a2, false),
                            Lit.make(a3, false),
                        ),
                    ),
                )
                if (f.recordStride == 4) {
                    allFires.add(Lit.make(fires, true))
                    allWeights.add(f.transitions[base + 3])
                }
                fires
            }
            val lits = IntArray(numTx) { Lit.make(txAux[it], true) }
            out.add(Cardinality(lits, min = 1, max = 1))
        }
        // state[n] ∈ accepting.
        val acceptLits = IntArray(f.accepting.size) { ai ->
            val aux = ctx.freshBool()
            out.add(ReifiedLinear(aux, intArrayOf(1), intArrayOf(state[n]), LinearOp.EQ, f.accepting[ai]))
            Lit.make(aux, true)
        }
        out.add(Cardinality(acceptLits, min = 1, max = f.accepting.size))
        // Cost-MDD: cost = Σ weight · fires. Exactly one fires bool per layer, so this
        // sums one weight per layer. Enumerate cost domain and tie `(Σ = k) ↔ (cost = k)`.
        if (f.recordStride == 4) {
            val firesArr = allFires.toIntArray()
            val weightsArr = allWeights.toIntArray()
            val cdom = ctx.intDomainOf(f.cost)
            for (k in cdom.min..cdom.max) {
                val sumEqK = ctx.freshBool()
                out.add(ReifiedPseudoBoolean(sumEqK, weightsArr, firesArr, PbOp.EQ, k))
                val cEqK = ctx.freshBool()
                out.add(ReifiedLinear(cEqK, intArrayOf(1), intArrayOf(f.cost), LinearOp.EQ, k))
                out.add(Clause(intArrayOf(Lit.make(sumEqK, false), Lit.make(cEqK, true))))
                out.add(Clause(intArrayOf(Lit.make(sumEqK, true), Lit.make(cEqK, false))))
            }
        }
        return out
    }

    /** `disjunctive(starts, durations)` — pairwise non-overlap. For each task pair (i,j):
     *  `(start_i + dur_i ≤ start_j) ∨ (start_j + dur_j ≤ start_i)`. */
    private fun decomposeDisjunctive(f: Disjunctive, ctx: DecompositionContext): List<Factor>? {
        val out = ArrayList<Factor>()
        for (i in 0 until f.starts.size - 1) {
            for (j in i + 1 until f.starts.size) {
                val aij = ctx.freshBool()
                out.add(
                    ReifiedLinear(
                        aij,
                        intArrayOf(1, -1),
                        intArrayOf(f.starts[i], f.starts[j]),
                        LinearOp.LE,
                        -f.durations[i],
                    ),
                )
                val aji = ctx.freshBool()
                out.add(
                    ReifiedLinear(
                        aji,
                        intArrayOf(1, -1),
                        intArrayOf(f.starts[j], f.starts[i]),
                        LinearOp.LE,
                        -f.durations[j],
                    ),
                )
                // Non-opt: at least one separation holds. Opt-aware: if either is absent
                // the pair is vacuous, so add ¬present_i ∨ ¬present_j to the disjunction.
                if (f.presents.isEmpty()) {
                    out.add(Clause(intArrayOf(Lit.make(aij, true), Lit.make(aji, true))))
                } else {
                    out.add(
                        Clause(
                            intArrayOf(
                                Lit.make(aij, true),
                                Lit.make(aji, true),
                                Lit.negate(f.presents[i]),
                                Lit.negate(f.presents[j]),
                            ),
                        ),
                    )
                }
            }
        }
        return out
    }

    /** `diffn(xs, ys, widths, heights, nonStrict)` — pairwise 2D-rectangle non-overlap.
     *  For each pair (i, j): at least one of the 4 axis separations holds. With
     *  `nonStrict = true`, touching rectangles are allowed (the inequality is `≤` rather
     *  than `<`). */
    private fun decomposeDiffn(f: Diffn, ctx: DecompositionContext): List<Factor> {
        val out = ArrayList<Factor>()
        val n = f.xs.size
        for (i in 0 until n - 1) {
            for (j in i + 1 until n) {
                // ax_ij ↔ xs[i] + widths[i] (≤|<) xs[j]  (i is left of j)
                val ax = ctx.freshBool()
                val ay = ctx.freshBool()
                val bx = ctx.freshBool()
                val by = ctx.freshBool()
                // Strict `<` is not natively expressible, so both cases use LE; strictness is
                // folded into the bound via the `+1` adjustments below.
                val opX = LinearOp.LE
                val xAdj = if (f.nonStrict) -f.widths[i] else -f.widths[i] + 1
                val xAdjRev = if (f.nonStrict) -f.widths[j] else -f.widths[j] + 1
                val yAdj = if (f.nonStrict) -f.heights[i] else -f.heights[i] + 1
                val yAdjRev = if (f.nonStrict) -f.heights[j] else -f.heights[j] + 1
                // Diffn convention: rectangles overlap iff *both* x-projections and
                // y-projections overlap. So non-overlap is "separated in x OR
                // separated in y" — but each direction has two sub-cases (i before j
                // OR j before i). Encode each:
                //   ax: xs[i] + w_i ≤ xs[j]   ⇒  xs[i] − xs[j] ≤ −w_i
                //   bx: xs[j] + w_j ≤ xs[i]   ⇒  xs[j] − xs[i] ≤ −w_j
                //   ay / by analogous on ys.
                out.add(ReifiedLinear(ax, intArrayOf(1, -1), intArrayOf(f.xs[i], f.xs[j]), opX, xAdj))
                out.add(ReifiedLinear(bx, intArrayOf(1, -1), intArrayOf(f.xs[j], f.xs[i]), opX, xAdjRev))
                out.add(ReifiedLinear(ay, intArrayOf(1, -1), intArrayOf(f.ys[i], f.ys[j]), opX, yAdj))
                out.add(ReifiedLinear(by, intArrayOf(1, -1), intArrayOf(f.ys[j], f.ys[i]), opX, yAdjRev))
                out.add(
                    Clause(intArrayOf(Lit.make(ax, true), Lit.make(bx, true), Lit.make(ay, true), Lit.make(by, true))),
                )
            }
        }
        return out
    }

    /** `geost(numDims, numObjects, origin, length)` — N-dimensional non-overlap. For
     *  each pair of objects, in at least one dimension one is to the left of the other. */
    private fun decomposeGeost(f: Geost, ctx: DecompositionContext): List<Factor> {
        val out = ArrayList<Factor>()
        val nObj = f.numObjects
        val nDim = f.numDims
        for (i in 0 until nObj - 1) {
            for (j in i + 1 until nObj) {
                // 2 * nDim disjuncts: in dim d, i before j or j before i.
                val auxLits = IntArray(2 * nDim)
                for (d in 0 until nDim) {
                    val originI = f.origin[i * nDim + d]
                    val originJ = f.origin[j * nDim + d]
                    val lenI = f.length[i * nDim + d]
                    val lenJ = f.length[j * nDim + d]
                    val aij = ctx.freshBool()
                    val aji = ctx.freshBool()
                    out.add(ReifiedLinear(aij, intArrayOf(1, -1), intArrayOf(originI, originJ), LinearOp.LE, -lenI))
                    out.add(ReifiedLinear(aji, intArrayOf(1, -1), intArrayOf(originJ, originI), LinearOp.LE, -lenJ))
                    auxLits[2 * d] = Lit.make(aij, true)
                    auxLits[2 * d + 1] = Lit.make(aji, true)
                }
                out.add(Clause(auxLits))
            }
        }
        return out
    }

    /** `cumulative(starts, durations, resources, capacity)` — time-indexed encoding.
     *  Horizon = max possible end time across tasks; for each integer time t ∈
     *  `[0, horizon)`, each task contributes `resources[i]` to load(t) iff its start
     *  spans t. Constraint: load(t) ≤ capacity. */
    private fun decomposeCumulative(f: Cumulative, ctx: DecompositionContext): List<Factor>? {
        if (f.presents.isNotEmpty()) return null
        // Horizon: max (start_domain.max + duration). Bail if any duration is non-
        // constant (we only know constants statically) — durations is IntArray, so
        // constants are fine.
        var horizon = 0
        for (i in f.starts.indices) {
            val d = ctx.intDomainOf(f.starts[i])
            val end = d.max + f.durations[i]
            if (end > horizon) horizon = end
        }
        // Long horizons: fall back to the pairwise-overlap "task-as-time" encoding.
        // Resource peaks can only occur at task start times, so capacity feasibility at
        // every start time is necessary and sufficient.
        if (horizon > 1024) return decomposeCumulativePairwise(f, ctx)
        val out = ArrayList<Factor>()
        val nTasks = f.starts.size
        // active_i_t ↔ (starts[i] ≤ t ∧ starts[i] + durations[i] > t)
        //          ↔ (starts[i] ≤ t) ∧ (starts[i] ≥ t − durations[i] + 1)
        val active = Array(nTasks) { i ->
            IntArray(horizon) { t ->
                val lo = ctx.freshBool() // starts[i] ≤ t
                val hi = ctx.freshBool() // starts[i] ≥ t − dur[i] + 1
                out.add(ReifiedLinear(lo, intArrayOf(1), intArrayOf(f.starts[i]), LinearOp.LE, t))
                out.add(ReifiedLinear(hi, intArrayOf(1), intArrayOf(f.starts[i]), LinearOp.GE, t - f.durations[i] + 1))
                val act = ctx.freshBool()
                // act ↔ lo ∧ hi
                out.add(Clause(intArrayOf(Lit.make(act, false), Lit.make(lo, true))))
                out.add(Clause(intArrayOf(Lit.make(act, false), Lit.make(hi, true))))
                out.add(Clause(intArrayOf(Lit.make(act, true), Lit.make(lo, false), Lit.make(hi, false))))
                act
            }
        }
        // For each time t: Σ resources[i] · active_i_t ≤ capacity.
        for (t in 0 until horizon) {
            val lits = IntArray(nTasks) { Lit.make(active[it][t], true) }
            out.add(PseudoBoolean(f.resources.copyOf(), lits, PbOp.LE, f.capacity))
        }
        return out
    }

    /** Pairwise-overlap "task-as-time" encoding for cumulative. Bool `contains_ij ↔
     *  (start_j ≤ start_i ≤ start_j + dur_j − 1)` — task j is active at task i's start.
     *  For each i: `Σⱼ≠ᵢ resources[j] · contains_ij ≤ capacity − resources[i]`.
     *  Sound and complete: resource peaks can only occur at start events, so checking
     *  every start time covers the schedule's maximum load. */
    private fun decomposeCumulativePairwise(f: Cumulative, ctx: DecompositionContext): List<Factor> {
        val out = ArrayList<Factor>()
        val n = f.starts.size
        val contains = Array(n) { IntArray(n) }
        for (i in 0 until n) {
            for (j in 0 until n) {
                if (i == j) continue
                // start_i − start_j ≥ 0
                val ge = ctx.freshBool()
                out.add(ReifiedLinear(ge, intArrayOf(1, -1), intArrayOf(f.starts[i], f.starts[j]), LinearOp.GE, 0))
                // start_i − start_j ≤ dur_j − 1
                val le = ctx.freshBool()
                out.add(
                    ReifiedLinear(
                        le,
                        intArrayOf(1, -1),
                        intArrayOf(f.starts[i], f.starts[j]),
                        LinearOp.LE,
                        f.durations[j] - 1,
                    ),
                )
                val both = ctx.freshBool()
                out.add(Clause(intArrayOf(Lit.make(both, false), Lit.make(ge, true))))
                out.add(Clause(intArrayOf(Lit.make(both, false), Lit.make(le, true))))
                out.add(Clause(intArrayOf(Lit.make(both, true), Lit.make(ge, false), Lit.make(le, false))))
                contains[i][j] = both
            }
        }
        for (i in 0 until n) {
            val lits = ArrayList<Int>()
            val ws = ArrayList<Int>()
            for (j in 0 until n) {
                if (i == j) continue
                lits.add(Lit.make(contains[i][j], true))
                ws.add(f.resources[j])
            }
            out.add(PseudoBoolean(ws.toIntArray(), lits.toIntArray(), PbOp.LE, f.capacity - f.resources[i]))
        }
        return out
    }

    /** `circuit(succ)` — succ forms a Hamiltonian cycle over `[0, n-1]`. Encoded via
     *  pairwise NE + a "level" aux per node ensuring there's exactly one cycle of
     *  length n. Level convention: level[0] = 0; for i ≠ 0, level[i] ∈ [1, n-1]; for
     *  any j ≠ 0, `succ[i] = j ⇒ level[j] = level[i] + 1`; node 0's predecessor has
     *  level n-1. */
    private fun decomposeCircuit(f: Circuit, ctx: DecompositionContext): List<Factor> {
        val out = ArrayList<Factor>()
        val n = f.succ.size
        // Pairwise NE on succ (no two predecessors point to same target).
        for (i in 0 until n - 1) {
            for (j in i + 1 until n) {
                out.add(Linear(intArrayOf(1, -1), intArrayOf(f.succ[i], f.succ[j]), LinearOp.NE, 0))
            }
        }
        if (n == 1) return out // trivial 1-node "cycle" — succ[0] = 0 enforced by domain
        // Level vars: level[0] pinned to 0; level[i] ∈ [1, n-1] for i ≠ 0.
        val level = IntArray(n) { i ->
            if (i == 0) {
                ctx.freshInt(IntDomain(0, 0))
            } else {
                ctx.freshInt(IntDomain(1, n - 1))
            }
        }
        // For each i, for each j ∈ [1, n-1]: succ[i] = j ⇒ level[j] = level[i] + 1.
        for (i in 0 until n) {
            for (j in 1 until n) {
                val eq = ctx.freshBool()
                out.add(ReifiedLinear(eq, intArrayOf(1), intArrayOf(f.succ[i]), LinearOp.EQ, j))
                // eq ⇒ level[j] - level[i] = 1
                val diff = ctx.freshBool()
                out.add(ReifiedLinear(diff, intArrayOf(1, -1), intArrayOf(level[j], level[i]), LinearOp.EQ, 1))
                out.add(Clause(intArrayOf(Lit.make(eq, false), Lit.make(diff, true))))
            }
        }
        return out
    }

    /** `subcircuit(succ)` — same as Circuit but tolerates self-loops (`succ[i] = i`
     *  means node i isn't in the cycle). The "in-cycle" subset must form a single
     *  cycle; the rest are fixed points. */
    private fun decomposeSubcircuit(f: Subcircuit, ctx: DecompositionContext): List<Factor> {
        val out = ArrayList<Factor>()
        val n = f.succ.size
        // For pairwise NE we exclude pairs where both are self-loops (auto-different
        // because succ[i] = i ≠ j = succ[j] when i ≠ j). So we can use the same NE
        // constraint as Circuit.
        for (i in 0 until n - 1) {
            for (j in i + 1 until n) {
                out.add(Linear(intArrayOf(1, -1), intArrayOf(f.succ[i], f.succ[j]), LinearOp.NE, 0))
            }
        }
        // For subcircuit, the in-cycle level chain only constrains non-self-loops.
        // For each i, "in_cycle_i" ↔ (succ[i] ≠ i). Encoded via reified-NE.
        val inCycle = IntArray(n) { i ->
            val aux = ctx.freshBool()
            // succ[i] ≠ i  →  Linear NE: 1*succ[i] - 0 ≠ i, i.e., bound = i, op = NE.
            out.add(ReifiedLinear(aux, intArrayOf(1), intArrayOf(f.succ[i]), LinearOp.NE, i))
            aux
        }
        // level[i] ∈ [0, n-1]; for the in-cycle subset, level forms a cycle of length
        // = number of in-cycle nodes. We adopt the same level convention as Circuit,
        // gated by `in_cycle_i ∧ in_cycle_j`.
        val level = IntArray(n) { ctx.freshInt(IntDomain(0, n - 1)) }
        for (i in 0 until n) {
            for (j in 0 until n) {
                if (i == j) continue
                val eq = ctx.freshBool()
                out.add(ReifiedLinear(eq, intArrayOf(1), intArrayOf(f.succ[i]), LinearOp.EQ, j))
                val diff = ctx.freshBool()
                out.add(ReifiedLinear(diff, intArrayOf(1, -1), intArrayOf(level[j], level[i]), LinearOp.EQ, 1))
                // (eq ∧ in_cycle_i ∧ in_cycle_j) ⇒ diff
                out.add(
                    Clause(
                        intArrayOf(
                            Lit.make(eq, false),
                            Lit.make(inCycle[i], false),
                            Lit.make(inCycle[j], false),
                            Lit.make(diff, true),
                        ),
                    ),
                )
            }
        }
        return out
    }

    /** `sort(xs, ys)` — ys is the sorted (non-decreasing) permutation of xs. Encoded
     *  as: ys is non-decreasing (Linear LE chain) plus multiset equality (per-value
     *  count parity across xs and ys). */
    private fun decomposeSort(f: Sort, ctx: DecompositionContext): List<Factor>? {
        if (f.xs.size != f.ys.size) return null
        val out = ArrayList<Factor>()
        // ys[i] ≤ ys[i+1]
        for (i in 0 until f.ys.size - 1) {
            out.add(Linear(intArrayOf(1, -1), intArrayOf(f.ys[i], f.ys[i + 1]), LinearOp.LE, 0))
        }
        // Multiset equality. Determine domain union.
        var lo = Int.MAX_VALUE
        var hi = Int.MIN_VALUE
        for (x in f.xs + f.ys) {
            val d = ctx.intDomainOf(x)
            if (d.min < lo) lo = d.min
            if (d.max > hi) hi = d.max
        }
        for (v in lo..hi) {
            // Per xs[i] and ys[i]: aux eq_xs_i ↔ (xs[i] = v); similarly for ys.
            val xsEq = IntArray(f.xs.size) { i ->
                val aux = ctx.freshBool()
                out.add(ReifiedLinear(aux, intArrayOf(1), intArrayOf(f.xs[i]), LinearOp.EQ, v))
                Lit.make(aux, true)
            }
            val ysEq = IntArray(f.ys.size) { i ->
                val aux = ctx.freshBool()
                out.add(ReifiedLinear(aux, intArrayOf(1), intArrayOf(f.ys[i]), LinearOp.EQ, v))
                Lit.make(aux, true)
            }
            // Σ xsEq = Σ ysEq  →  Σ xsEq − Σ ysEq = 0; encode via PseudoBoolean with
            // weights [+1]*nx + [-1]*ny over (xsEq ++ ysEq) lits, op=EQ, bound=0.
            val allLits = IntArray(xsEq.size + ysEq.size)
            val allWeights = IntArray(xsEq.size + ysEq.size)
            for (i in xsEq.indices) {
                allLits[i] = xsEq[i]
                allWeights[i] = 1
            }
            for (i in ysEq.indices) {
                allLits[xsEq.size + i] = ysEq[i]
                allWeights[xsEq.size + i] = -1
            }
            out.add(PseudoBoolean(allWeights, allLits, PbOp.EQ, 0))
        }
        return out
    }

    /** `arg_sort(values, perm, permOffset)` — `perm` is a permutation of
     *  `[permOffset, permOffset + n − 1]` such that `values[perm[i] − permOffset]` is
     *  non-decreasing with ties broken by smaller pre-image index. */
    private fun decomposeArgSort(f: ArgSort, ctx: DecompositionContext): List<Factor> {
        val out = ArrayList<Factor>()
        val n = f.perm.size
        // Permutation: pairwise NE + range constraints on perm.
        for (i in 0 until n - 1) {
            for (j in i + 1 until n) {
                out.add(Linear(intArrayOf(1, -1), intArrayOf(f.perm[i], f.perm[j]), LinearOp.NE, 0))
            }
        }
        // For each i, allocate an "indexed-value" aux: valAt_i ∈ values domain.
        val unionDom = run {
            var lo = Int.MAX_VALUE
            var hi = Int.MIN_VALUE
            for (v in f.values) {
                val d = ctx.intDomainOf(v)
                if (d.min < lo) lo = d.min
                if (d.max > hi) hi = d.max
            }
            IntDomain(lo, hi)
        }
        val valAt = IntArray(n) { ctx.freshInt(unionDom) }
        // Element constraint: valAt[i] = values[perm[i] − permOffset]. For each i, for
        // each j ∈ [0, n-1]: aux eq_ij ↔ (perm[i] = j + permOffset); eq_ij ⇒ valAt[i]
        // = values[j].
        for (i in 0 until n) {
            for (j in 0 until n) {
                val eq = ctx.freshBool()
                out.add(ReifiedLinear(eq, intArrayOf(1), intArrayOf(f.perm[i]), LinearOp.EQ, j + f.permOffset))
                val veq = ctx.freshBool()
                out.add(ReifiedLinear(veq, intArrayOf(1, -1), intArrayOf(valAt[i], f.values[j]), LinearOp.EQ, 0))
                out.add(Clause(intArrayOf(Lit.make(eq, false), Lit.make(veq, true))))
            }
        }
        // valAt non-decreasing.
        for (i in 0 until n - 1) {
            out.add(Linear(intArrayOf(1, -1), intArrayOf(valAt[i], valAt[i + 1]), LinearOp.LE, 0))
        }
        // Tie-break: if valAt[i] = valAt[i+1], then perm[i] < perm[i+1].
        for (i in 0 until n - 1) {
            val tieAux = ctx.freshBool()
            out.add(ReifiedLinear(tieAux, intArrayOf(1, -1), intArrayOf(valAt[i], valAt[i + 1]), LinearOp.EQ, 0))
            val lessAux = ctx.freshBool()
            out.add(ReifiedLinear(lessAux, intArrayOf(1, -1), intArrayOf(f.perm[i], f.perm[i + 1]), LinearOp.LE, -1))
            // tie ⇒ less
            out.add(Clause(intArrayOf(Lit.make(tieAux, false), Lit.make(lessAux, true))))
        }
        return out
    }

    /** `path(numNodes, from, to, source, sink, nodePresent, edgePresent)` — degree-
     *  based decomposition. Allocates per-node in/out aux ints summing the bool
     *  presence of incident edges, then per-node degree constraints driven by the
     *  source/sink role and presence. Doesn't enforce *simple-path* connectivity (no
     *  disconnected cycle elimination) — that's the propagator's job; the
     *  decomposition gives a sound lower-bound encoding sufficient for many
     *  practical workloads. */
    private fun decomposePath(f: Path, ctx: DecompositionContext): List<Factor> {
        val out = ArrayList<Factor>()
        val n = f.numNodes
        val m = f.from.size
        val off = f.nodeOffset
        val inArcs = Array(n) { ArrayList<Int>() }
        val outArcs = Array(n) { ArrayList<Int>() }
        for (e in 0 until m) {
            outArcs[f.from[e] - off].add(e)
            inArcs[f.to[e] - off].add(e)
        }
        // inDeg / outDeg ints in [0, max-incident].
        val inDeg = IntArray(n) { v ->
            val k = inArcs[v].size
            ctx.freshInt(IntDomain(0, k))
        }
        val outDeg = IntArray(n) { v ->
            val k = outArcs[v].size
            ctx.freshInt(IntDomain(0, k))
        }
        // Linear: inDeg[v] − Σ edgePresent[e for e ∈ inArcs[v]] = 0; sim for out.
        for (v in 0 until n) {
            val inSize = inArcs[v].size
            if (inSize > 0) {
                val vars = IntArray(inSize + 1)
                val coeffs = IntArray(inSize + 1)
                for ((idx, e) in inArcs[v].withIndex()) {
                    // bool-as-int via reified aux  bAux ↔ (edgePresent[e] = 1); fold via
                    // PseudoBoolean instead: Σ bool − inDeg = 0 ⇒ PB with lits = edge
                    // present positives weight 1, plus an aux equality for inDeg.
                    vars[idx] = 0 // placeholder; rewrite via PB below
                    coeffs[idx] = 0
                }
            }
        }
        // Simpler: for each v, PB(weights = +1 × edgePresent lits) − inDeg[v] = 0.
        // Since PseudoBoolean only constrains lits against a constant, we instead use
        // per-value reified equalities: for each k ∈ [0, |inArcs|], aux deg_v_k ↔ (PB
        // = k) AND inDeg[v] = k → tie them via reified-eq on inDeg.
        for (v in 0 until n) {
            val inSize = inArcs[v].size
            if (inSize == 0) {
                // inDeg[v] must be 0.
                out.add(Linear(intArrayOf(1), intArrayOf(inDeg[v]), LinearOp.EQ, 0))
            } else {
                val lits = IntArray(inSize) { idx -> Lit.make(f.edgePresent[inArcs[v][idx]], true) }
                for (k in 0..inSize) {
                    val deg = ctx.freshBool()
                    out.add(ReifiedCardinality(deg, lits, min = k, max = k))
                    val eq = ctx.freshBool()
                    out.add(ReifiedLinear(eq, intArrayOf(1), intArrayOf(inDeg[v]), LinearOp.EQ, k))
                    // deg ↔ eq
                    out.add(Clause(intArrayOf(Lit.make(deg, false), Lit.make(eq, true))))
                    out.add(Clause(intArrayOf(Lit.make(deg, true), Lit.make(eq, false))))
                }
            }
            val outSize = outArcs[v].size
            if (outSize == 0) {
                out.add(Linear(intArrayOf(1), intArrayOf(outDeg[v]), LinearOp.EQ, 0))
            } else {
                val lits = IntArray(outSize) { idx -> Lit.make(f.edgePresent[outArcs[v][idx]], true) }
                for (k in 0..outSize) {
                    val deg = ctx.freshBool()
                    out.add(ReifiedCardinality(deg, lits, min = k, max = k))
                    val eq = ctx.freshBool()
                    out.add(ReifiedLinear(eq, intArrayOf(1), intArrayOf(outDeg[v]), LinearOp.EQ, k))
                    out.add(Clause(intArrayOf(Lit.make(deg, false), Lit.make(eq, true))))
                    out.add(Clause(intArrayOf(Lit.make(deg, true), Lit.make(eq, false))))
                }
            }
        }
        // Per-node role constraints.
        for (v in 0 until n) {
            val present = f.nodePresent[v]
            val isSource = ctx.freshBool()
            val isSink = ctx.freshBool()
            out.add(ReifiedLinear(isSource, intArrayOf(1), intArrayOf(f.source), LinearOp.EQ, v + off))
            out.add(ReifiedLinear(isSink, intArrayOf(1), intArrayOf(f.sink), LinearOp.EQ, v + off))
            // ¬present → inDeg = 0 ∧ outDeg = 0
            val inEq0 = ctx.freshBool()
            val outEq0 = ctx.freshBool()
            out.add(ReifiedLinear(inEq0, intArrayOf(1), intArrayOf(inDeg[v]), LinearOp.EQ, 0))
            out.add(ReifiedLinear(outEq0, intArrayOf(1), intArrayOf(outDeg[v]), LinearOp.EQ, 0))
            out.add(Clause(intArrayOf(Lit.make(present, true), Lit.make(inEq0, true))))
            out.add(Clause(intArrayOf(Lit.make(present, true), Lit.make(outEq0, true))))
            // present ∧ source → inDeg = 0 ∧ outDeg = 1
            val outEq1 = ctx.freshBool()
            out.add(ReifiedLinear(outEq1, intArrayOf(1), intArrayOf(outDeg[v]), LinearOp.EQ, 1))
            out.add(Clause(intArrayOf(Lit.make(present, false), Lit.make(isSource, false), Lit.make(inEq0, true))))
            out.add(Clause(intArrayOf(Lit.make(present, false), Lit.make(isSource, false), Lit.make(outEq1, true))))
            // present ∧ sink → inDeg = 1 ∧ outDeg = 0
            val inEq1 = ctx.freshBool()
            out.add(ReifiedLinear(inEq1, intArrayOf(1), intArrayOf(inDeg[v]), LinearOp.EQ, 1))
            out.add(Clause(intArrayOf(Lit.make(present, false), Lit.make(isSink, false), Lit.make(inEq1, true))))
            out.add(Clause(intArrayOf(Lit.make(present, false), Lit.make(isSink, false), Lit.make(outEq0, true))))
            // present ∧ ¬source ∧ ¬sink → inDeg = 1 ∧ outDeg = 1
            out.add(
                Clause(
                    intArrayOf(
                        Lit.make(present, false),
                        Lit.make(isSource, true),
                        Lit.make(isSink, true),
                        Lit.make(inEq1, true),
                    ),
                ),
            )
            out.add(
                Clause(
                    intArrayOf(
                        Lit.make(present, false),
                        Lit.make(isSource, true),
                        Lit.make(isSink, true),
                        Lit.make(outEq1, true),
                    ),
                ),
            )
            // source → present; sink → present.
            out.add(Clause(intArrayOf(Lit.make(isSource, false), Lit.make(present, true))))
            out.add(Clause(intArrayOf(Lit.make(isSink, false), Lit.make(present, true))))
        }
        // Edge present → both endpoints present.
        for (e in 0 until m) {
            val ep = f.edgePresent[e]
            val fp = f.nodePresent[f.from[e] - off]
            val tp = f.nodePresent[f.to[e] - off]
            out.add(Clause(intArrayOf(Lit.make(ep, false), Lit.make(fp, true))))
            out.add(Clause(intArrayOf(Lit.make(ep, false), Lit.make(tp, true))))
        }
        // Cycle elimination via per-node level (MTZ-style): `level[v] ∈ [0, n-1]`,
        // `level[source] = 0`, and `edgePresent[e] = (u→v) ⇒ level[v] = level[u] + 1`.
        // Around any cycle this would require level[u]+k = level[u], so all simple cycles
        // are excluded; the only feasible subgraph is a source-to-sink path.
        val level = IntArray(n) { ctx.freshInt(IntDomain(0, n - 1)) }
        for (v in 0 until n) {
            val isSourceL = ctx.freshBool()
            out.add(ReifiedLinear(isSourceL, intArrayOf(1), intArrayOf(f.source), LinearOp.EQ, v + off))
            val lvlEq0 = ctx.freshBool()
            out.add(ReifiedLinear(lvlEq0, intArrayOf(1), intArrayOf(level[v]), LinearOp.EQ, 0))
            out.add(Clause(intArrayOf(Lit.make(isSourceL, false), Lit.make(lvlEq0, true))))
        }
        for (e in 0 until m) {
            val u = f.from[e] - off
            val v = f.to[e] - off
            val step = ctx.freshBool()
            out.add(ReifiedLinear(step, intArrayOf(1, -1), intArrayOf(level[v], level[u]), LinearOp.EQ, 1))
            out.add(Clause(intArrayOf(Lit.make(f.edgePresent[e], false), Lit.make(step, true))))
        }
        return out
    }

    /** `tree(numNodes, from, to, root, nodePresent, edgePresent)` — in-tree rooted at
     *  `root`. Decomposition: root has in-degree 0, every other present node has
     *  in-degree 1; plus level-based cycle elimination so a present edge `u→v`
     *  enforces `level[v] = level[u] + 1`. Around any cycle this is infeasible, ruling
     *  out disconnected components that the degree constraints alone permit. */
    private fun decomposeTree(f: Tree, ctx: DecompositionContext): List<Factor> {
        val out = ArrayList<Factor>()
        val n = f.numNodes
        val m = f.from.size
        val off = f.nodeOffset
        val inArcs = Array(n) { ArrayList<Int>() }
        for (e in 0 until m) inArcs[f.to[e] - off].add(e)
        val inDeg = IntArray(n) { v -> ctx.freshInt(IntDomain(0, inArcs[v].size)) }
        for (v in 0 until n) {
            val k = inArcs[v].size
            if (k == 0) {
                out.add(Linear(intArrayOf(1), intArrayOf(inDeg[v]), LinearOp.EQ, 0))
                continue
            }
            val lits = IntArray(k) { idx -> Lit.make(f.edgePresent[inArcs[v][idx]], true) }
            for (j in 0..k) {
                val deg = ctx.freshBool()
                out.add(ReifiedCardinality(deg, lits, min = j, max = j))
                val eq = ctx.freshBool()
                out.add(ReifiedLinear(eq, intArrayOf(1), intArrayOf(inDeg[v]), LinearOp.EQ, j))
                out.add(Clause(intArrayOf(Lit.make(deg, false), Lit.make(eq, true))))
                out.add(Clause(intArrayOf(Lit.make(deg, true), Lit.make(eq, false))))
            }
        }
        for (v in 0 until n) {
            val present = f.nodePresent[v]
            val isRoot = ctx.freshBool()
            out.add(ReifiedLinear(isRoot, intArrayOf(1), intArrayOf(f.root), LinearOp.EQ, v + off))
            val inEq0 = ctx.freshBool()
            val inEq1 = ctx.freshBool()
            out.add(ReifiedLinear(inEq0, intArrayOf(1), intArrayOf(inDeg[v]), LinearOp.EQ, 0))
            out.add(ReifiedLinear(inEq1, intArrayOf(1), intArrayOf(inDeg[v]), LinearOp.EQ, 1))
            // ¬present → inDeg = 0
            out.add(Clause(intArrayOf(Lit.make(present, true), Lit.make(inEq0, true))))
            // present ∧ root → inDeg = 0
            out.add(Clause(intArrayOf(Lit.make(present, false), Lit.make(isRoot, false), Lit.make(inEq0, true))))
            // present ∧ ¬root → inDeg = 1
            out.add(Clause(intArrayOf(Lit.make(present, false), Lit.make(isRoot, true), Lit.make(inEq1, true))))
            // root → present
            out.add(Clause(intArrayOf(Lit.make(isRoot, false), Lit.make(present, true))))
        }
        for (e in 0 until m) {
            val ep = f.edgePresent[e]
            val fp = f.nodePresent[f.from[e] - off]
            val tp = f.nodePresent[f.to[e] - off]
            out.add(Clause(intArrayOf(Lit.make(ep, false), Lit.make(fp, true))))
            out.add(Clause(intArrayOf(Lit.make(ep, false), Lit.make(tp, true))))
        }
        // Cycle elimination: `level[v] ∈ [0, n-1]`, `level[root] = 0`, and per edge
        // `(u→v)`: `edgePresent[e] ⇒ level[v] = level[u] + 1`. This eliminates cycles
        // (level monotonically increases along present edges) and any non-root present
        // node is forced to descend from the root.
        val level = IntArray(n) { ctx.freshInt(IntDomain(0, n - 1)) }
        for (v in 0 until n) {
            val isRootL = ctx.freshBool()
            out.add(ReifiedLinear(isRootL, intArrayOf(1), intArrayOf(f.root), LinearOp.EQ, v + off))
            val lvlEq0 = ctx.freshBool()
            out.add(ReifiedLinear(lvlEq0, intArrayOf(1), intArrayOf(level[v]), LinearOp.EQ, 0))
            out.add(Clause(intArrayOf(Lit.make(isRootL, false), Lit.make(lvlEq0, true))))
        }
        for (e in 0 until m) {
            val u = f.from[e] - off
            val v = f.to[e] - off
            val step = ctx.freshBool()
            out.add(ReifiedLinear(step, intArrayOf(1, -1), intArrayOf(level[v], level[u]), LinearOp.EQ, 1))
            out.add(Clause(intArrayOf(Lit.make(f.edgePresent[e], false), Lit.make(step, true))))
        }
        return out
    }

    /** `global_cardinality(xs, cover, ...)` — per-cover-value count constraints. The
     *  full factor supports count vars, low/high bounds, closed mode, and per-xs
     *  presence; we cover the count-var (open) and count-low/high (open) cases. The
     *  closed-mode and per-xs-presence variants return null. */
    private fun decomposeGlobalCardinality(f: GlobalCardinality, ctx: DecompositionContext): List<Factor>? {
        val out = ArrayList<Factor>()
        // Per xs[i], collect a "membership_iv ↔ present_i ∧ (xs[i] = v)" lit per cover
        // value. Closed mode additionally requires every present xs[i] to be ∈ cover —
        // we materialize a per-i ∈-cover indicator and force it true (or true-when-present).
        val coverIndicator = if (f.closed) IntArray(f.xs.size) else IntArray(0)
        val coverLitsPerIndex = if (f.closed) Array(f.xs.size) { ArrayList<Int>() } else emptyArray()
        for (ci in f.cover.indices) {
            val v = f.cover[ci]
            val eqLits = IntArray(f.xs.size) { i ->
                val eq = ctx.freshBool()
                out.add(ReifiedLinear(eq, intArrayOf(1), intArrayOf(f.xs[i]), LinearOp.EQ, v))
                if (f.closed) coverLitsPerIndex[i].add(Lit.make(eq, true))
                if (f.presents.isEmpty()) {
                    Lit.make(eq, true)
                } else {
                    val pres = f.presents[i]
                    val gated = ctx.freshBool()
                    out.add(Clause(intArrayOf(Lit.make(gated, false), Lit.make(eq, true))))
                    out.add(Clause(intArrayOf(Lit.make(gated, false), pres)))
                    out.add(Clause(intArrayOf(Lit.make(gated, true), Lit.make(eq, false), Lit.negate(pres))))
                    Lit.make(gated, true)
                }
            }
            when {
                f.countVars != null -> {
                    // sum eq_iv = countVars[ci]. Use PB with int var on the other side:
                    // since Linear can't mix bool lits and int vars, introduce an aux
                    // int "count_v" with the same domain as countVars[ci], constrain
                    // PB(eq_iv) = count_v, then assert count_v = countVars[ci].
                    val cvar = f.countVars[ci]
                    val cdom = ctx.intDomainOf(cvar)
                    val auxCount = ctx.freshInt(cdom)
                    // PseudoBoolean only constrains a weighted-lit sum against a constant;
                    // for variable bounds we instead use ReifiedLinear on each value
                    // step. Simpler: for each target value k in cdom, aux_k ↔ (cvar = k);
                    // then enforce sum eq_iv = k when aux_k is true.
                    // Cleanest direct encoding: build pairwise (sum = k) ⇒ (cvar = k).
                    for (k in cdom.min..cdom.max) {
                        val sumEqK = ctx.freshBool()
                        out.add(ReifiedCardinality(sumEqK, eqLits, min = k, max = k))
                        val cEqK = ctx.freshBool()
                        out.add(ReifiedLinear(cEqK, intArrayOf(1), intArrayOf(cvar), LinearOp.EQ, k))
                        // sumEqK ↔ cEqK so both representations agree.
                        out.add(Clause(intArrayOf(Lit.make(sumEqK, false), Lit.make(cEqK, true))))
                        out.add(Clause(intArrayOf(Lit.make(sumEqK, true), Lit.make(cEqK, false))))
                    }
                    // Touch aux so the unused-var warning is silenced; it's the placeholder
                    // counter we added above.
                    @Suppress("UNUSED_VARIABLE")
                    val t = auxCount
                }

                f.countLow != null || f.countHigh != null -> {
                    val low = f.countLow?.get(ci) ?: 0
                    val high = f.countHigh?.get(ci) ?: f.xs.size
                    out.add(Cardinality(eqLits, min = low, max = high))
                }

                else -> {
                    // No count constraint expressed for this cover value — skip.
                }
            }
        }
        if (f.closed) {
            // Each present xs[i] must equal some cover value.
            for (i in f.xs.indices) {
                val inCover = ctx.freshBool()
                coverIndicator[i] = inCover
                // inCover ↔ OR(coverLitsPerIndex[i]).
                val lits = coverLitsPerIndex[i].toIntArray()
                out.add(ReifiedCardinality(inCover, lits, min = 1, max = lits.size))
                if (f.presents.isEmpty()) {
                    out.add(Clause(intArrayOf(Lit.make(inCover, true))))
                } else {
                    // present_i → inCover
                    out.add(Clause(intArrayOf(Lit.negate(f.presents[i]), Lit.make(inCover, true))))
                }
            }
        }
        return out
    }
}
