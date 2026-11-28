package com.eignex.klause.lp.cut

import com.eignex.klause.ir.values
import com.eignex.klause.lp.engine.Cut
import com.eignex.klause.lp.engine.CutFamily
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.util.MinCostAssignment
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.LongArrayList
import com.eignex.util.collections.MutableLongIntMap
import com.eignex.util.numbers.CheckedLongOverflowException
import com.eignex.util.numbers.mulExact

// `Σ_{i∈V} c_i·x_i ≥ assignmentMin` over an all-different group, where the bound is the exact min-cost
// assignment of the objective coefficients to distinct values. Stronger than the Hall sum cut when the `c_i`
// differ; adjusting LP multipliers alone would buy nothing by strong duality.
internal class AssignmentObjectiveCut(private val intCoef: LongArray) : CutSeparator {
    override val family: CutFamily = CutFamily.ASSIGNMENT_OBJECTIVE

    private val tol = 1e-6

    override fun separate(ctx: CutContext): List<Cut> {
        val cuts = ArrayList<Cut>()
        for (vars in allDifferentGroups(ctx.problem)) {
            if (vars.size < 2) continue
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

    private fun assignmentMin(vars: IntArray, session: PropagationSession): Long? {
        val valueIndex = MutableLongIntMap()
        val values = LongArrayList()
        for (v in vars) {
            // Abort before enumerating a domain wider than the cap.
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
