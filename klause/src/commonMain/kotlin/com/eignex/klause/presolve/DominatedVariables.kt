package com.eignex.klause.presolve

import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.lp.Term
import com.eignex.klause.model.PbOp
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.util.IntHashSet

internal object DominatedVariables {

    /**
     * Dual fixing / dominated-variable reductions. A minimize objective `min Σ cⱼxⱼ` plus the
     * constraint structure can pin a variable to a bound without changing the optimum:
     *  - **down-safe**: lowering `xⱼ` never violates any constraint — it occurs only in `≤` rows with a
     *    positive coefficient or `≥` rows with a negative one; if also `cⱼ ≥ 0` (lowering never raises
     *    the objective), an optimum exists with `xⱼ` at its lower bound, so pin it there.
     *  - **up-safe**: the mirror (`≤`/negative or `≥`/positive, and `cⱼ ≤ 0`) → pin to the upper bound.
     *
     * Integers: a variable whose every occurrence is a monotone `≤`/`≥` row exposed by
     * `Factor.linearRows` (an `=`/`≠` row, or a factor with no exact linear form, makes the safety
     * undecidable, so it is excluded) is pinned by tightening its domain to a singleton.
     * Booleans: the pure-literal mirror, extended past [Clause] to every
     * *monotone* pseudo-Boolean row — a [Cardinality] `min ≤ Σ ≤ max` (each active side fixes a safe
     * direction per literal) and a [PseudoBoolean] `≤`/`≥`. In all of these, flipping a literal moves
     * the row's sum one known way, so one value of the variable is safe; an `=` pseudo-Boolean, a
     * reified row, or any other bool factor couples both directions and excludes the variable. A
     * safe-direction bool is pinned with a unit clause (a bool already unit-pinned is skipped, keeping
     * the pass idempotent). Coefficients come from [objectiveIntCoeffs] / [objectiveBoolCoeffs]
     * (minimize sense, absent ⇒ 0).
     *
     * The integer side reasons only over monotone `≤`/`≥` rows from `Factor.linearRows` — plain linear
     * comparators and the increasing chain both qualify; reified rows are full biconditionals (their
     * inner vars affect feasibility both ways) and other globals expose no exact linear rows, so they
     * exclude their vars.
     *
     * No elimination, identity reconstruction. Solution-set altering (discards optimum-equivalent and
     * feasible-but-suboptimal assignments), so the engine runs it only for non-solution-set-sensitive
     * queries.
     */
    fun fixDominatedVariables(
        problem: Problem,
        objectiveIntCoeffs: Map<Int, Long>,
        objectiveBoolCoeffs: Map<Int, Long> = emptyMap(),
    ): PassDelta {
        val n = problem.numIntVars
        val downSafe = BooleanArray(n) { true }
        val upSafe = BooleanArray(n) { true }
        val intEligible = BooleanArray(n) { true }
        val nb = problem.numBoolVars
        val trueSafe = BooleanArray(nb) { true } // b = true never violates a constraint
        val falseSafe = BooleanArray(nb) { true } // b = false never violates a constraint
        val boolEligible = BooleanArray(nb) { true }
        // A pin has to be earned by an occurrence the safety scan actually read. A variable no surviving
        // factor mentions is not thereby free: an earlier pass may have folded its defining factor away —
        // [ComparisonClauseFold] consumes a sole-use indicator's reified definition together with the
        // clause using it, leaving the indicator referenced nowhere — while its value stays tied to an
        // integer column through the bake. Pinning one of those asserts something the model never stated,
        // and buys nothing: a variable nothing references prunes nothing.
        val boolSeen = BooleanArray(nb)
        val alreadyPinned = IntHashSet() // bool vars already forced by a unit clause
        for (f in problem.factors) {
            for (v in f.boolVars) boolSeen[v] = true
            val rows = f.linearRows
            // The integer monotonicity analysis reads the integer side of a row; a row carrying Boolean
            // literals is left to the bool-side [markBoolSafety] (unifying the two is a follow-up).
            val monotoneIntRows = rows.isNotEmpty() &&
                rows.all { (it.relation == LinearOp.LE || it.relation == LinearOp.GE) && it.isIntegerOnly }
            if (monotoneIntRows) {
                // Every exact row is monotone ≤/≥, so each variable has one safe direction per row.
                for (row in rows) {
                    for (i in 0 until row.size) {
                        val a = row.coeff(i)
                        if (a == 0L) continue
                        val v = Term.intVar(row.ref(i))
                        // Lowering is safe iff (LE ∧ a>0) ∨ (GE ∧ a<0); raising is the complement.
                        val loweringSafe = if (row.relation == LinearOp.LE) a > 0 else a < 0
                        if (loweringSafe) upSafe[v] = false else downSafe[v] = false
                    }
                }
            } else {
                // An =/≠ row, a factor with no exact integer-linear form, or any other global makes the
                // single-variable safety undecidable.
                for (v in f.intVars) intEligible[v] = false
            }
            markBoolSafety(f, trueSafe, falseSafe, boolEligible, alreadyPinned)
        }
        var domainsNarrowed = false
        val domains = problem.requireFiniteIntDomains().copyOf()
        for (v in 0 until n) {
            if (!intEligible[v]) continue
            val d = problem.requireFiniteIntDomains()[v]
            if (d.min == d.max) continue // already fixed
            val c = objectiveIntCoeffs[v] ?: 0L
            when {
                downSafe[v] && c >= 0L -> {
                    domains[v] = IntDomain(d.min, d.min)
                    domainsNarrowed = true
                }

                upSafe[v] && c <= 0L -> {
                    domains[v] = IntDomain(d.max, d.max)
                    domainsNarrowed = true
                }
            }
        }
        val extra = ArrayList<Factor>()
        for (b in 0 until nb) {
            if (!boolEligible[b] || !boolSeen[b] || b in alreadyPinned) continue
            val c = objectiveBoolCoeffs[b] ?: 0L
            when {
                trueSafe[b] && c <= 0L -> extra.add(Clause(intArrayOf(Lit.make(b, true))))
                falseSafe[b] && c >= 0L -> extra.add(Clause(intArrayOf(Lit.make(b, false))))
                else -> continue
            }
        }
        if (!domainsNarrowed && extra.isEmpty()) return PassDelta()
        // Carry the pinned domains only when a pin actually narrowed one, so a bool-only fixing yields a
        // pure-add delta the fixpoint check reads correctly.
        return PassDelta(addedFactors = extra, domains = if (domainsNarrowed) domains else null)
    }

    /** Fold [f]'s contribution to the Boolean pure-literal safety analysis. A bool var is
     *  pinnable only if it occurs solely in *monotone* rows — [Clause] (an at-least-one lower bound),
     *  [Cardinality] (its active lower/upper sides), and [PseudoBoolean] `≤`/`≥` — where flipping a
     *  literal moves the row's sum in one known direction. Any other bool factor (a reified row, a
     *  `=` pseudo-Boolean, …) couples the two directions, so it excludes its bool vars outright. */
    private fun markBoolSafety(
        f: Factor,
        trueSafe: BooleanArray,
        falseSafe: BooleanArray,
        boolEligible: BooleanArray,
        alreadyPinned: IntHashSet,
    ) {
        when {
            f is Clause -> {
                if (f.literals.size == 1) alreadyPinned.add(Lit.variable(f.literals[0]))
                // A clause is `Σ lit ≥ 1`: unsatisfying a literal lowers the count toward violation.
                for (lit in f.literals) markBoolMonotoneLiteral(lit, 1, false, fallUnsafe = true, trueSafe, falseSafe)
            }

            f is Cardinality -> {
                // `min ≤ Σ lit ≤ max`: the lower side (min > 0) makes unsatisfying risky, the upper
                // side (max < #lits) makes satisfying risky. A two-sided row clears both directions.
                val fallUnsafe = f.min > 0
                val riseUnsafe = f.max < f.literals.size
                for (lit in f.literals) markBoolMonotoneLiteral(lit, 1, riseUnsafe, fallUnsafe, trueSafe, falseSafe)
            }

            f is PseudoBoolean && (f.op == PbOp.LE || f.op == PbOp.GE) -> {
                // `Σ w·lit ≤ b` (rising sum violates) / `≥ b` (falling sum violates).
                val riseUnsafe = f.op == PbOp.LE
                for (i in f.literals.indices) {
                    markBoolMonotoneLiteral(f.literals[i], f.weights[i], riseUnsafe, !riseUnsafe, trueSafe, falseSafe)
                }
            }

            else -> for (v in f.boolVars) boolEligible[v] = false
        }
    }

    /** Clear the unsafe pin direction(s) for the variable behind [lit] in a monotone row. [weight] is
     *  the literal's coefficient (1 for clause/cardinality); the signed weight `w·polarity` is how the
     *  row's sum changes when the variable flips false→true. [riseUnsafe] / [fallUnsafe] say whether a
     *  rising / falling sum can violate the row, so the value that moves the sum that way is unsafe. */
    private fun markBoolMonotoneLiteral(
        lit: Int,
        weight: Long,
        riseUnsafe: Boolean,
        fallUnsafe: Boolean,
        trueSafe: BooleanArray,
        falseSafe: BooleanArray,
    ) {
        val v = Lit.variable(lit)
        val signedW = if (Lit.isPositive(lit)) weight else -weight
        if (signedW == 0L) return
        // The value that raises the sum: true if signedW > 0, else false. Mirror for lowering.
        if (riseUnsafe) (if (signedW > 0) trueSafe else falseSafe)[v] = false
        if (fallUnsafe) (if (signedW > 0) falseSafe else trueSafe)[v] = false
    }
}
