package com.eignex.klause.presolve

import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.impliedLinearRows
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.util.CheckedLongOverflowException
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.addExact

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
     * the row's sum one known way, so one value of the variable is safe; an equality, a
     * reified row, or a factor with no exact monotone declaration couples both directions and excludes the variable. A
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
     *
     * This is the finite form: it pins into root-propagated domains, which are narrower than the model
     * states, so it reaches columns the declaration alone leaves too wide to fix.
     * [fixDominatedSourceVariables] is the same reduction over a source model.
     */
    fun fixDominatedVariables(
        problem: BakedProblem,
        objectiveIntCoeffs: Map<Int, Long>,
        objectiveBoolCoeffs: Map<Int, Long> = emptyMap(),
    ): PassDelta {
        val safety = scanSafety(problem)
        var domainsNarrowed = false
        val domains = problem.rootIntDomains()
        for (v in 0 until problem.numIntVars) {
            if (!safety.pinnable(v)) continue
            val d = problem.rootIntDomain(v)
            if (d.min == d.max) continue // already fixed
            val c = objectiveIntCoeffs[v] ?: 0L
            when {
                safety.downSafe[v] && c >= 0L -> {
                    domains[v] = IntDomain(d.min, d.min)
                    domainsNarrowed = true
                }

                safety.upSafe[v] && c <= 0L -> {
                    domains[v] = IntDomain(d.max, d.max)
                    domainsNarrowed = true
                }
            }
        }
        val extra = safety.boolPins(objectiveBoolCoeffs)
        if (!domainsNarrowed && extra.isEmpty()) return PassDelta()
        // Carry the pinned domains only when a pin actually narrowed one, so a bool-only fixing yields a
        // pure-add delta the fixpoint check reads correctly.
        return PassDelta(addedFactors = extra, domains = if (domainsNarrowed) domains else null)
    }

    /**
     * [fixDominatedVariables] over a canonical source model, before any finite projection exists.
     *
     * The safety argument is the same one and reads no domain at all — it is the rows a factor declares
     * that decide a direction. Only the pin needs a value, and it needs exactly one: the bound it pins
     * to. So a column open on the *other* side still pins, which is what makes this reduction worth
     * having on a model the finite lane cannot represent.
     *
     * A column open on the side its safe direction points at is skipped. Pinning needs a bound to sit
     * at, and the reduction proves only that moving that way never costs — not where the movement stops.
     */
    fun fixDominatedSourceVariables(
        problem: Problem,
        objectiveIntCoeffs: Map<Int, Long>,
        objectiveBoolCoeffs: Map<Int, Long> = emptyMap(),
    ): SourceDelta {
        val safety = scanSafety(problem)
        val bounds = problem.intBounds
        val tightening = bounds.tightening()
        for (v in 0 until problem.numIntVars) {
            if (!safety.pinnable(v) || fixedColumn(bounds, v)) continue
            val c = objectiveIntCoeffs[v] ?: 0L
            // Each arm carries its own justification, so falling past an arm whose bound is open and
            // pinning on the other is sound rather than a second-choice guess: reaching the lower arm
            // needs `downSafe`, the upper one `upSafe`, and only `c == 0` satisfies both coefficient
            // tests at once.
            when {
                safety.downSafe[v] && c >= 0L && bounds.hasLower(v) -> tightening.atMost(v, bounds.lower(v))
                safety.upSafe[v] && c <= 0L && bounds.hasUpper(v) -> tightening.atLeast(v, bounds.upper(v))
            }
        }
        val extra = safety.boolPins(objectiveBoolCoeffs)
        val proved = tightening.build()
        if (proved == null && extra.isEmpty()) return SourceDelta()
        return SourceDelta(addedFactors = extra, bounds = proved)
    }

    /** Whether [v] is already pinned by its declared range, so a dual-fixing pin would restate it. */
    private fun fixedColumn(bounds: IntBounds, v: Int): Boolean =
        bounds.hasLower(v) && bounds.hasUpper(v) && bounds.lower(v) == bounds.upper(v)

    /**
     * What the monotone-occurrence scan proved about each column and each literal.
     *
     * Holds no domain and no range: every field is decided by the rows the factors declare, which is why
     * both lanes run the identical scan and differ only in where a pin is written.
     */
    private class Safety(
        /** Columns whose every occurrence makes lowering safe. */
        val downSafe: BooleanArray,
        /** Columns whose every occurrence makes raising safe. */
        val upSafe: BooleanArray,
        /** Columns no `=`/`≠` row and no opaque global mentions. */
        val intEligible: BooleanArray,
        /** Columns some surviving factor reads. */
        val intSeen: BooleanArray,
        /** Literals for which `true` never violates a row. */
        val trueSafe: BooleanArray,
        /** Literals for which `false` never violates a row. */
        val falseSafe: BooleanArray,
        /** Booleans no non-monotone factor mentions. */
        val boolEligible: BooleanArray,
        /** Booleans some surviving factor reads. */
        val boolSeen: BooleanArray,
        /** Booleans a unit clause already forces. */
        val alreadyPinned: IntHashSet,
    ) {
        /** Whether column [v] admits a pin at all, before its bound or objective coefficient is read. */
        fun pinnable(v: Int): Boolean = intEligible[v] && intSeen[v]

        /** The unit clauses pinning every safe-direction Boolean under [objectiveBoolCoeffs]. */
        fun boolPins(objectiveBoolCoeffs: Map<Int, Long>): List<Factor> {
            val extra = ArrayList<Factor>()
            for (b in boolEligible.indices) {
                if (!boolEligible[b] || !boolSeen[b] || b in alreadyPinned) continue
                val c = objectiveBoolCoeffs[b] ?: 0L
                when {
                    trueSafe[b] && c <= 0L -> extra.add(Clause(intArrayOf(Lit.make(b, true))))
                    falseSafe[b] && c >= 0L -> extra.add(Clause(intArrayOf(Lit.make(b, false))))
                    else -> continue
                }
            }
            return extra
        }
    }

    /** Read every factor's declared rows for the one direction each variable may safely move. */
    private fun scanSafety(problem: Problem): Safety {
        val n = problem.numIntVars
        val nb = problem.numBoolVars
        val safety = Safety(
            downSafe = BooleanArray(n) { true },
            upSafe = BooleanArray(n) { true },
            intEligible = BooleanArray(n) { true },
            // A pin has to be earned by an occurrence the safety scan actually read. A variable no
            // surviving factor mentions is not thereby free: an earlier pass may have folded its defining
            // factor away — [ComparisonClauseFold] consumes a sole-use indicator's reified definition
            // together with the clause using it, leaving the indicator referenced nowhere — while its
            // value stays tied to an integer column through the bake. Pinning one of those asserts
            // something the model never stated, and buys nothing: a variable nothing references prunes
            // nothing. The same rule holds for an integer column, and for the same reason.
            intSeen = BooleanArray(n),
            trueSafe = BooleanArray(nb) { true }, // b = true never violates a constraint
            falseSafe = BooleanArray(nb) { true }, // b = false never violates a constraint
            boolEligible = BooleanArray(nb) { true },
            boolSeen = BooleanArray(nb),
            alreadyPinned = IntHashSet(), // bool vars already forced by a unit clause
        )
        for (f in problem.factors) {
            for (v in f.boolVars) safety.boolSeen[v] = true
            for (v in f.intVars) safety.intSeen[v] = true
            val rows = f.impliedLinearRows
            val monotoneIntRows = (f.linearForm is LinearForm.Conjunction) && rows.isNotEmpty() &&
                rows.all {
                    (it.relation == LinearOp.LE || it.relation == LinearOp.GE) && it.isLongUnconditional
                }
            if (monotoneIntRows) {
                // Every exact row is monotone ≤/≥, so each variable has one safe direction per row.
                for (row in rows) {
                    for (i in 0 until row.size) {
                        if (!Term.isInt(row.ref(i))) continue
                        val a = row.coeff(i)
                        if (a == 0L) continue
                        val v = Term.intVar(row.ref(i))
                        // Lowering is safe iff (LE ∧ a>0) ∨ (GE ∧ a<0); raising is the complement.
                        val loweringSafe = if (row.relation == LinearOp.LE) a > 0 else a < 0
                        if (loweringSafe) safety.upSafe[v] = false else safety.downSafe[v] = false
                    }
                }
            } else {
                // An =/≠ row, a factor with no exact integer-linear form, or any other global makes the
                // single-variable safety undecidable.
                for (v in f.intVars) safety.intEligible[v] = false
            }
            markBoolSafety(f, safety.trueSafe, safety.falseSafe, safety.boolEligible, safety.alreadyPinned)
        }
        return safety
    }

    // Every row must be an unconditional monotone comparison equivalent to the complete factor.
    private fun markBoolSafety(
        f: Factor,
        trueSafe: BooleanArray,
        falseSafe: BooleanArray,
        boolEligible: BooleanArray,
        alreadyPinned: IntHashSet,
    ) {
        if (f is Clause && f.literals.size == 1) alreadyPinned.add(Lit.variable(f.literals[0]))
        val rows = (f.linearForm as? LinearForm.Conjunction)?.rows
        if (rows == null || rows.isEmpty() || rows.any {
                !it.isLongUnconditional || (it.relation != LinearOp.LE && it.relation != LinearOp.GE)
            }
        ) {
            for (v in f.boolVars) boolEligible[v] = false
            return
        }
        for (row in rows) {
            if (row.isVacuousBooleanRow()) continue
            val riseUnsafe = row.relation == LinearOp.LE
            for (i in 0 until row.size) {
                if (!Term.isBool(row.ref(i))) continue
                markBoolMonotoneLiteral(
                    Term.lit(row.ref(i)),
                    row.coeff(i),
                    riseUnsafe,
                    !riseUnsafe,
                    trueSafe,
                    falseSafe,
                )
            }
        }
    }

    private fun LinearRow.isVacuousBooleanRow(): Boolean {
        if ((0 until size).any { !Term.isBool(ref(it)) }) return false
        val upper = relation == LinearOp.LE
        var extreme = 0L
        try {
            for (i in 0 until size) {
                val coefficient = coeff(i)
                if ((upper && coefficient > 0L) || (!upper && coefficient < 0L)) {
                    extreme = addExact(extreme, coefficient)
                }
            }
        } catch (_: CheckedLongOverflowException) {
            return false
        }
        return if (upper) extreme <= bound else extreme >= bound
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
        if (weight == 0L) return
        val positive = (weight > 0L) == Lit.isPositive(lit)
        // The value that raises the sum: true if positive, else false. Mirror for lowering.
        if (riseUnsafe) (if (positive) trueSafe else falseSafe)[v] = false
        if (fallUnsafe) (if (positive) falseSafe else trueSafe)[v] = false
    }
}
