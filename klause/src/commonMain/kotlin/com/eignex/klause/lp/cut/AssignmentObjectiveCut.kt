package com.eignex.klause.lp.cut

import com.eignex.klause.ir.values
import com.eignex.klause.lp.bound.MinCostAssignment
import com.eignex.klause.lp.engine.Cut
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.util.CheckedLongOverflowException
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.LongArrayList
import com.eignex.klause.util.MutableLongIntMap
import com.eignex.klause.util.mulExact

/**
 * Lagrangian-augmented LP cut: the objective-weighted AllDifferent bound. For an
 * AllDifferent over variables `V`, the minimum of `Σ_{i∈V} c_i·x_i` subject to all-different is the
 * exact min-cost assignment of the objective coefficients to distinct values ([MinCostAssignment]) —
 * a stronger statement than the unweighted Hall sum cut whenever the `c_i` differ. Emitting
 * `Σ_{i∈V} c_i·x_i ≥ assignmentMin` as a cut injects that global, integral bound into the LP, which
 * is exactly the synergy the Lagrangian-augmented LP path provides: a plain multiplier→coefficient
 * adjustment buys nothing for an LP relaxation (LP strong duality), but the integral assignment bound
 * does. The cut is emitted only when the LP point violates it.
 */
internal class AssignmentObjectiveCut(private val intCoef: LongArray) : CutSeparator {
    private val tol = 1e-6

    override fun separate(ctx: CutContext): List<Cut> {
        val cuts = ArrayList<Cut>()
        for (vars in allDifferentGroups(ctx.problem)) {
            if (vars.size < 2) continue
            // Need a column for every variable and at least one nonzero objective coefficient.
            if (vars.any { ctx.relaxation.intColOf[it] < 0 }) continue
            if (vars.none { intCoef.getOrElse(it) { 0L } != 0L }) continue

            val assignmentMin = assignmentMin(vars, ctx.session) ?: continue
            // Cut is over the nonzero-cost columns; zero-cost variables only shaped the assignment.
            val cols = IntArrayList()
            val coeffs = LongArrayList()
            var lpLhs = 0.0
            for (v in vars) {
                val c = intCoef.getOrElse(v) { 0L }
                if (c == 0L) continue
                val col = ctx.relaxation.intColOf[v]
                cols.add(col)
                coeffs.add(c)
                lpLhs += c.toDouble() * ctx.primalOf(col)
            }
            if (lpLhs < assignmentMin - tol) {
                // The assignment enumerated the live value sets hole-aware, so globality needs full
                // domain equality with the declared sets, not just matching intervals.
                val global = liveDomainsAreDeclared(ctx, vars)
                cuts.add(Cut(cols.toIntArray(), coeffs.toLongArray(), Relation.GE, assignmentMin, global))
            }
        }
        return cuts
    }

    /**
     * Exact minimum of `Σ_{i∈V} c_i·x_i` over distinct assignments of the live domains, via
     * [MinCostAssignment]. Returns null when the value set is too large to assign over or the
     * arithmetic overflows (then no cut is produced — sound, just no strengthening).
     */
    private fun assignmentMin(vars: IntArray, session: PropagationSession): Long? {
        val valueIndex = MutableLongIntMap()
        val values = LongArrayList()
        for (v in vars) {
            // Abort before walking a domain that is too large to assign over — in particular a wide
            // (>2^31-span) domain, whose `sizeLong` saturates well past the cap, so it is never
            // enumerated value-by-value. Sound: no cut is produced, only strengthening is skipped.
            if (session.intDomain(v).spanOrNull(MAX_VALUES.toLong()) == null) return null
            session.intDomain(v).values.forEach { value ->
                if (!valueIndex.containsKey(value)) {
                    valueIndex.put(value, values.size)
                    values.add(value)
                }
            }
        }
        if (values.size > MAX_VALUES || values.size < vars.size) return null
        return try {
            val assign = MinCostAssignment(vars.size, values.size)
            for (i in vars.indices) {
                val c = intCoef.getOrElse(vars[i]) { 0L }
                session.intDomain(vars[i]).values.forEach { value ->
                    assign.addOption(i, valueIndex.getOrDefault(value, -1), mulExact(c, value))
                }
            }
            val r = assign.solve()
            if (r.feasible) r.cost else null
        } catch (_: CheckedLongOverflowException) {
            null
        }
    }

    private companion object {
        const val MAX_VALUES: Int = 512
    }
}
