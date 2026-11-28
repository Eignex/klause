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
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.collections.IntHashSet
import com.eignex.util.numbers.CheckedLongOverflowException
import com.eignex.util.numbers.addExact

internal object DominatedVariables {

    /**
     * Dual fixing: for `min Σ cⱼxⱼ`, a variable that only occurs where lowering it is safe (`≤` rows with a
     * positive or `≥` rows with a negative coefficient) and has `cⱼ ≥ 0` has an optimum at its lower bound;
     * the mirror pins to the upper bound. Only monotone unconditional `≤`/`≥` rows from
     * `Factor.impliedLinearRows` decide safety; an `=`/`≠` row, a reified row or an opaque global excludes
     * its variables. Booleans use the same argument over clauses, [Cardinality] and [PseudoBoolean] rows and
     * are pinned with unit clauses (already-pinned ones are skipped, keeping the pass idempotent).
     *
     * Solution-set altering, so it runs only for non-solution-set-sensitive queries. This finite form pins
     * into root-propagated domains, reaching columns the declaration leaves too wide to fix.
     *
     * E. D. Andersen, K. D. Andersen, "Presolving in linear programming", Mathematical Programming 71, 1995,
     * https://doi.org/10.1007/BF01586000.
     */
    fun fixDominatedVariables(
        problem: BakedProblem,
        objectiveIntCoeffs: Map<Int, Long>,
        objectiveBoolCoeffs: Map<Int, Long> = emptyMap(),
        cancellation: ExecutionControl = ExecutionControl.Never,
    ): PassDelta {
        if (cancellation()) return PassDelta()
        val safety = scanSafety(problem)
        // Allocated on the first pin: most rounds on a wide model pin nothing, and staying null keeps a
        // bool-only fixing a pure-add delta.
        var domains: Array<IntDomain>? = null
        fun pin(v: Int, value: Long) {
            val target = domains ?: problem.rootIntDomains().also { domains = it }
            target[v] = IntDomain(value, value)
        }
        for (v in 0 until problem.numIntVars) {
            if (!safety.pinnable(v)) continue
            val d = problem.rootIntDomain(v)
            if (d.min == d.max) continue // already fixed
            val c = objectiveIntCoeffs[v] ?: 0L
            when {
                safety.downSafe[v] && c >= 0L -> pin(v, d.min)
                safety.upSafe[v] && c <= 0L -> pin(v, d.max)
            }
        }
        val extra = safety.boolPins(objectiveBoolCoeffs) +
            CoverColumnDominance.pins(problem, objectiveBoolCoeffs, cancellation)
        val pinned = domains
        if (pinned == null && extra.isEmpty()) return PassDelta()
        return PassDelta(addedFactors = extra, domains = pinned)
    }

    // The safety scan reads no domain, so a column open on the other side still pins. A column open on its
    // safe side is skipped: the reduction proves moving that way never costs, not where it stops. The pin
    // uses the declared value set's endpoint where one exists, since a range endpoint outside the
    // declaration would name an excluded value and read as a refutation.
    fun fixDominatedSourceVariables(
        problem: Problem,
        objectiveIntCoeffs: Map<Int, Long>,
        objectiveBoolCoeffs: Map<Int, Long> = emptyMap(),
        cancellation: ExecutionControl = ExecutionControl.Never,
    ): SourceDelta {
        if (cancellation()) return SourceDelta()
        val safety = scanSafety(problem)
        val bounds = problem.intBounds
        // Allocated on the first pin: most rounds on a wide model pin nothing.
        var tightening: IntBounds.Tightening? = null
        fun pin(v: Int, value: Long) {
            val target = tightening ?: bounds.tightening().also { tightening = it }
            target.atLeast(v, value)
            target.atMost(v, value)
        }
        for (v in 0 until problem.numIntVars) {
            if (!safety.pinnable(v)) continue
            val declared = problem.intDomainOrNull(v)
            if (fixedColumn(bounds, declared, v)) continue
            val c = objectiveIntCoeffs[v] ?: 0L
            // Each arm carries its own justification, so falling past an arm whose bound is open to pin on
            // the other is sound.
            when {
                safety.downSafe[v] && c >= 0L && bounds.hasLower(v) -> pin(v, declared?.min ?: bounds.lower(v))
                safety.upSafe[v] && c <= 0L && bounds.hasUpper(v) -> pin(v, declared?.max ?: bounds.upper(v))
            }
        }
        val extra = safety.boolPins(objectiveBoolCoeffs) +
            CoverColumnDominance.pins(problem, objectiveBoolCoeffs, cancellation)
        val proved = tightening?.build()
        if (proved == null && extra.isEmpty()) return SourceDelta()
        return SourceDelta(addedFactors = extra, bounds = proved)
    }

    private fun fixedColumn(bounds: IntBounds, declared: IntDomain?, v: Int): Boolean = if (declared != null) {
        declared.min == declared.max
    } else {
        bounds.hasLower(v) && bounds.hasUpper(v) && bounds.lower(v) == bounds.upper(v)
    }

    // Decided only by the rows factors declare, so both lanes run the identical scan.
    private class Safety(
        val downSafe: BooleanArray,
        val upSafe: BooleanArray,
        val intEligible: BooleanArray,
        val intSeen: BooleanArray,
        val trueSafe: BooleanArray,
        val falseSafe: BooleanArray,
        val boolEligible: BooleanArray,
        val boolSeen: BooleanArray,
        val alreadyPinned: IntHashSet,
    ) {
        fun pinnable(v: Int): Boolean = intEligible[v] && intSeen[v]

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

    private fun scanSafety(problem: Problem): Safety {
        val n = problem.numIntVars
        val nb = problem.numBoolVars
        val safety = Safety(
            downSafe = BooleanArray(n) { true },
            upSafe = BooleanArray(n) { true },
            intEligible = BooleanArray(n) { true },
            // A pin must be earned by an occurrence the scan read. An unreferenced variable is not free: a pass
            // such as [ComparisonClauseFold] may have folded its definition away while the bake still ties its
            // value to an integer column, and pinning it prunes nothing anyway.
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
