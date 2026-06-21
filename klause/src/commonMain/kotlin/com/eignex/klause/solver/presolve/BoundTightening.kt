package com.eignex.klause.solver.presolve

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.arithmetic.Linear
import com.eignex.klause.solver.factor.arithmetic.LinearOp

internal object BoundTightening {

    /**
     * Iterated activity-based bound tightening (classic FME bound propagation) over the [Linear]
     * inequality / equality rows. For a row `Σ aⱼ·xⱼ ≤ b` the residual on term `j` is
     * `aⱼ·xⱼ ≤ b − minActivityWithout(j)`, so a positive `aⱼ` tightens `xⱼ`'s upper bound to
     * `⌊(b − minActivityWithout(j)) / aⱼ⌋` and a negative `aⱼ` tightens its lower bound to
     * `⌈(b − minActivityWithout(j)) / aⱼ⌉`; a `≥` row is the mirror over the maximal activity. An
     * equality tightens from both sides at once. Every variable here is integral, so the implied
     * rational bound is rounded *inward* (floor for an upper bound, ceil for a lower bound), which
     * never discards a feasible integer point.
     *
     * Only ever **tightens** a domain by a valid implication. A tightened lower bound above the
     * tightened upper bound proves the problem infeasible; the pass surfaces that the way every
     * trivially-infeasible problem does — by posting a unit row the construction-time bake
     * propagates to `Unsat` (see [infeasible]) — rather than by emptying a domain (which the
     * [IntDomain] reps forbid). `≠` rows and non-[Linear] factors carry no two-sided activity bound
     * a single pass can exploit, so they are skipped; their variables still receive tightenings from
     * the rows that do bound them.
     *
     * Objective variables are tightened freely — narrowing a domain by an implication never changes
     * the optimum — the pass simply never *fixes* a variable from objective reasoning (it reads no
     * objective at all).
     */
    fun tightenBounds(problem: Problem): Problem {
        if (problem.numIntVars == 0) return problem
        val tightened = tightenDomains(problem.factors, problem.intDomains) ?: return infeasible(problem)
        if (tightened === problem.intDomains) return problem
        return PresolveShared.rebuildProblem(problem, problem.factors.toList(), tightened)
    }

    /**
     * Local-fixpoint cap. Each iteration costs `O(Σ row arity)`; a model with only integral domains
     * converges in at most `O(total domain span)` iterations because every change shrinks some
     * domain by at least one, but a coupled chain can still take many rounds, and on a model whose
     * implied bounds approach a limit only asymptotically (long chains of fractional residuals that
     * each shave a single unit) the count is large though finite. The cap bounds the per-pass cost; a
     * run that hasn't converged simply leaves a sound, partially-tightened problem for the outer
     * [Presolver] fixpoint (and the construction-time bake) to carry further. SCIP's bound-propagation
     * presolver bounds its rounds the same way.
     */
    private const val MAX_LOCAL_ROUNDS = 64

    /**
     * The [domains] tightened to a local bound fixpoint by activity propagation over the [Linear]
     * rows in [factors], or `null` when the tightening proves infeasibility (an implied lower bound
     * above the implied upper bound, or a row whose activity can never reach its bound). Returns the
     * input array unchanged (`===`) when nothing tightens, so callers can detect a no-op cheaply.
     */
    fun tightenDomains(factors: Array<Factor>, domains: Array<IntDomain>): Array<IntDomain>? {
        // Work on a private copy; return the original (`===`) when nothing tightened so the caller can
        // detect the no-op, and never mutate the input array.
        val working = domains.copyOf()
        var mutated = false
        var round = 0
        var changed = true
        while (changed && round < MAX_LOCAL_ROUNDS) {
            changed = false
            round++
            for (f in factors) {
                if (f !is Linear || f.op == LinearOp.NE) continue
                val range = PresolveShared.activityRange(f.coeffs, f.vars, working)
                val minActivity = range[0]
                val maxActivity = range[1]
                if (rowInfeasible(f.op, f.bound.toLong(), minActivity, maxActivity)) return null
                for (i in f.vars.indices) {
                    val a = f.coeffs[i].toLong()
                    if (a == 0L) continue
                    val v = f.vars[i]
                    val d = working[v]
                    val term = a * d.min
                    val termHi = a * d.max
                    // The term's own contribution at each activity extreme, to take "activity without j".
                    val minWithout = minActivity - if (a >= 0L) term else termHi
                    val maxWithout = maxActivity - if (a >= 0L) termHi else term
                    val newDom = tightenVar(d, a, f.op, f.bound.toLong(), minWithout, maxWithout) ?: return null
                    if (newDom !== d) {
                        working[v] = newDom
                        mutated = true
                        changed = true
                    }
                }
            }
        }
        return if (mutated) working else domains
    }

    /** Whether a row can never reach its [bound] for any in-bounds assignment — its activity interval
     *  `[minActivity, maxActivity]` lies wholly on the violating side of [bound]. */
    private fun rowInfeasible(op: LinearOp, bound: Long, minActivity: Long, maxActivity: Long): Boolean = when (op) {
        LinearOp.LE -> minActivity > bound
        LinearOp.GE -> maxActivity < bound
        LinearOp.EQ -> minActivity > bound || maxActivity < bound
        LinearOp.NE -> false
    }

    /**
     * [d] tightened by the residual of one term `a·xⱼ` in a row with the given [op] and [bound], given
     * the row's activity without this term (`[minWithout, maxWithout]`). `null` when the implied
     * bound empties the domain (infeasible). The `≤` / `≥` sides each tighten one end; `=` applies
     * both. Rounding is inward — `⌊·⌋` for an upper bound, `⌈·⌉` for a lower bound — because `xⱼ` is
     * integral, so the rounded bound still admits every feasible integer value.
     */
    private fun tightenVar(
        d: IntDomain,
        a: Long,
        op: LinearOp,
        bound: Long,
        minWithout: Long,
        maxWithout: Long,
    ): IntDomain? {
        var lo = d.min.toLong()
        var hi = d.max.toLong()
        // Σ aⱼxⱼ ≤ b ⇒ a·xⱼ ≤ b − minWithout. The same residual bounds GE / EQ from the other side.
        if (op == LinearOp.LE || op == LinearOp.EQ) {
            val rhs = bound - minWithout
            if (a > 0L) hi = minOf(hi, rhs.floorDiv(a)) else lo = maxOf(lo, ceilDiv(rhs, a))
        }
        if (op == LinearOp.GE || op == LinearOp.EQ) {
            val rhs = bound - maxWithout
            if (a > 0L) lo = maxOf(lo, ceilDiv(rhs, a)) else hi = minOf(hi, rhs.floorDiv(a))
        }
        if (lo > hi) return null
        var out = d
        if (lo > d.min) out = out.withMinAtLeast(lo.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        if (hi < d.max) out = out.withMaxAtMost(hi.coerceAtLeast(Int.MIN_VALUE.toLong()).toInt())
        return out
    }

    /** `⌈a / b⌉` for [Long] operands, correct for either sign (stdlib `floorDiv` mirrored). */
    private fun ceilDiv(a: Long, b: Long): Long = -((-a).floorDiv(b))

    /** A clone of [problem] made trivially infeasible by an unsatisfiable unit row, so the
     *  construction-time bake folds it to `Unsat` — the same signal every proven-infeasible problem
     *  raises. `2·x₀ = 1` has no integer solution for any nonempty domain of `x₀`, so it forces the
     *  conflict independent of the variable's bound magnitudes (no overflow) and without emptying a
     *  domain at build time (which the [IntDomain] reps forbid). */
    private fun infeasible(problem: Problem): Problem {
        val unsat = Linear(intArrayOf(2), intArrayOf(0), LinearOp.EQ, 1)
        return PresolveShared.rebuildProblem(problem, problem.factors.toList() + unsat)
    }
}
