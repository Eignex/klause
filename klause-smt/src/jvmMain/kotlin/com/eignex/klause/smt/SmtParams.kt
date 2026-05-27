package com.eignex.klause.smt

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.SolverParams
import org.sosy_lab.java_smt.SolverContextFactory

/**
 * Per-call params for [SmtSolver]. Mirrors [com.eignex.klause.logicng.LogicNGParams] in
 * shape so the cross-backend harness can hand each backend its own typed params and stay
 * symmetric.
 *
 *  - [solver] — which JavaSMT backend to use. SMTInterpol is pure-Java and always
 *    available. Z3 / CVC5 / MathSAT5 / Bitwuzla / Yices2 require their respective native
 *    libraries on the classpath. Princess is pure-Java but slower in practice.
 *  - [randomSeed] — passed to the backend if it accepts a seed; SMTInterpol and most
 *    others do, though phase selection for "the same model again" varies across solvers.
 *  - [minHammingDistance] / [recentWindow] — opt-in diversity post-filter on
 *    [SmtSolver.enumerate]. Default `0 / 0` means no filter.
 *  - [maxModels] — caps the number of model attempts before the sequence ends.
 *  - [timeoutMillis] — wall-clock cap. Checked between solves; a long-running individual
 *    solve is not interrupted mid-call (JavaSMT's ShutdownNotifier could be wired for that
 *    but isn't today).
 *  - [assumptions] — variables to pin for the duration of this call.
 */
data class SmtParams(
    val solver: SolverContextFactory.Solvers = SolverContextFactory.Solvers.SMTINTERPOL,
    val randomSeed: Long? = null,
    val minHammingDistance: Int = 0,
    val recentWindow: Int = 0,
    val maxModels: Long = Long.MAX_VALUE,
    val timeoutMillis: Long? = null,
    val assumptions: Assumptions = Assumptions.None,
    /**
     * Wall-clock-independent operation budget — present for API symmetry with the other
     * backends' [com.eignex.klause.solver.SolverParams]. **Currently ignored** by this
     * backend: JavaSMT does not expose Z3's `rlimit` or any equivalent uniformly across
     * its supported solvers, so there's no per-solver knob we can pass through here.
     * Use [timeoutMillis] until JavaSMT (or specific backends below it) gain such a knob.
     */
    val maxInstructions: Long? = null,
) : SolverParams {
    override fun withAssumptions(assumptions: Assumptions): SmtParams =
        if (assumptions.isEmpty) this else copy(assumptions = merge(this.assumptions, assumptions))

    private companion object {
        fun merge(a: Assumptions, b: Assumptions): Assumptions = a.mergedWith(b)
    }
}
