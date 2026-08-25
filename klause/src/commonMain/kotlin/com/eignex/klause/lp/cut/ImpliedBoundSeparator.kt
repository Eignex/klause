package com.eignex.klause.lp.cut

import com.eignex.klause.ir.Lit
import com.eignex.klause.lp.Relation
import com.eignex.klause.presolve.Presolve
import com.eignex.klause.solver.Cancellation

/**
 * Implied-bound cuts from the binary **implication graph**. Probing
 * derives implications `litA ⇒ litB` that hold at every solution but are not explicit binary clauses
 * (those already enter the LP as clause rows). Each such implication is the linear inequality
 * `litVal(A) ≤ litVal(B)` — where `litVal` of a positive literal over Boolean column `c` is `xₑ` and of
 * a negative literal is `1 − xₑ` — valid at every integer solution (if `A` holds so must `B`, so
 * `litVal(A) ≤ litVal(B)` always). The separator emits the ones the fractional LP point violates.
 *
 * The implication graph is computed once (probing is expensive) from the root problem and cached; it is
 * literal-indexed (`graph[lit]` = the literals `lit` implies). Cuts are [Cut.global] — probing
 * implications are problem-wide. Sound: every emitted cut holds at every solution, so it only tightens
 * the relaxation, never removes a feasible point (validated by brute enumeration in the tests).
 */
internal class ImpliedBoundSeparator(private val maxCandidates: Int = DEFAULT_MAX_CANDIDATES) : CutSeparator {

    private var graph: Array<IntArray>? = null

    override fun separate(ctx: CutContext): List<Cut> {
        val g = graph ?: Presolve.implicationGraph(ctx.problem, maxCandidates, Cancellation.Never).also { graph = it }
        val boolColOf = ctx.relaxation.boolColOf
        val cuts = ArrayList<Cut>()
        for (fromLit in g.indices) {
            for (toLit in g[fromLit]) {
                val cut = impliedBoundCut(fromLit, toLit, boolColOf) ?: continue
                // litVal(from) − litVal(to): a violation means the fractional point breaks the implication.
                val fromVal = litValue(fromLit, boolColOf, ctx)
                val toVal = litValue(toLit, boolColOf, ctx)
                if (fromVal - toVal > VIOLATION_TOL) cuts.add(cut)
            }
        }
        return cuts
    }

    /** The LP value of [lit]: `xₑ` for a positive literal over Boolean column `c`, `1 − xₑ` for a negative one. */
    private fun litValue(lit: Int, boolColOf: IntArray, ctx: CutContext): Double {
        val col = boolColOf[Lit.variable(lit)]
        val x = ctx.primalOf(col)
        return if (Lit.isPositive(lit)) x else 1.0 - x
    }

    internal companion object {
        /** Probe at most this many Boolean variables when building the implication graph. */
        const val DEFAULT_MAX_CANDIDATES: Int = 512

        /** Minimum `litVal(A) − litVal(B)` for the LP point to count as violating `A ⇒ B`. */
        private const val VIOLATION_TOL: Double = 1e-6

        /**
         * The implied-bound cut for `fromLit ⇒ toLit` as `Σ coeffs·x ≤ rhs`, or null when the two share a
         * variable (a unit fact, not a two-variable bound) or a literal's variable has no LP column.
         * Derivation of `litVal(from) − litVal(to) ≤ 0`, with `litVal(+v)=xᵥ`, `litVal(−v)=1−xᵥ`:
         * the per-literal coefficient is `±1` by polarity and the `1`s from negative literals move to
         * the right-hand side. Valid at every solution because the implication is.
         */
        fun impliedBoundCut(fromLit: Int, toLit: Int, boolColOf: IntArray): Cut? {
            val fv = Lit.variable(fromLit)
            val tv = Lit.variable(toLit)
            if (fv == tv) return null // same variable ⇒ a unit fact, not a pairwise bound cut
            val fc = boolColOf.getOrElse(fv) { -1 }
            val tc = boolColOf.getOrElse(tv) { -1 }
            if (fc < 0 || tc < 0) return null
            val fromPos = Lit.isPositive(fromLit)
            val toPos = Lit.isPositive(toLit)
            // litVal(from) contributes (+1·x_fc, const 0) if positive, else (−1·x_fc, const +1).
            // −litVal(to)   contributes (−1·x_tc, const 0) if positive, else (+1·x_tc, const −1).
            val cFrom = if (fromPos) 1L else -1L
            val cTo = if (toPos) -1L else 1L
            val constSum = (if (fromPos) 0L else 1L) + (if (toPos) 0L else -1L)
            // cFrom·x_fc + cTo·x_tc + constSum ≤ 0  ⇒  rhs = −constSum.
            return Cut(intArrayOf(fc, tc), longArrayOf(cFrom, cTo), Relation.LE, -constSum, global = true)
        }
    }
}
