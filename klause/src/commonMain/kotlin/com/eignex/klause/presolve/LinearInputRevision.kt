package com.eignex.klause.presolve

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntegerConstants
import com.eignex.klause.ir.IntegralConstants
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.ScanMeter

// One lane owns these block revisions. Counts retract dropped occurrences, so neither duplicate factors
// nor successive rewrites retain historical columns. An interrupted tracker cannot publish a revision.
internal class LinearInputRevision(
    problem: Problem,
    private val pass: PresolvePass,
    private val cancellation: Cancellation,
) {
    private val columns = HashMap<Int, Int>()
    private val ranges = HashMap<Int, Pair<Long?, Long?>>()
    private var dirty = true
    private var complete = true
    private var revision = 0L

    init {
        require(pass.tracksLinearInputs)
        val work = ScanMeter(cancellation)
        for (factor in problem.factors) {
            if (!record(factor, 1, work)) {
                complete = false
                break
            }
        }
        complete = work.check() && complete
        if (!complete) clear()
    }

    fun recordDelta(
        factors: Array<Factor>,
        dropped: IntArray,
        added: List<Factor>,
        deductionsChanged: Boolean = true,
    ) {
        if (!complete) return
        val work = ScanMeter(cancellation)
        for (index in dropped) {
            if (!record(factors[index], -1, work)) break
        }
        if (!work.stopped) for (factor in added) {
            if (!record(factor, 1, work)) break
        }
        dirty = dirty || deductionsChanged
        complete = work.check()
        if (!complete) clear()
    }

    fun current(problem: Problem): Long? = current { v ->
        val domain = (problem as? BakedProblem)?.rootIntDomain(v)
        if (domain != null) domain.min to domain.max else {
            val bounds = problem.intBounds
            (if (bounds.hasLower(v)) bounds.lower(v) else null) to
                (if (bounds.hasUpper(v)) bounds.upper(v) else null)
        }
    }

    fun current(range: (Int) -> Pair<Long?, Long?>): Long? {
        if (!complete) return null
        if (!dirty) return revision
        val work = ScanMeter(cancellation)
        var changed = false
        for (v in columns.keys) {
            if (!work.visit(1L)) break
            val next = range(v)
            if (ranges.put(v, next) != next) changed = true
        }
        complete = work.check()
        if (!complete) {
            clear()
            return null
        }
        if (changed) revision++
        dirty = false
        return revision
    }

    private fun record(factor: Factor, direction: Int, work: ScanMeter): Boolean {
        if (!work.visit(1L)) return false
        if (factor is Clause) return true
        val form = factor.linearForm ?: return true
        if (form is LinearForm.Disjunction) return true
        val whole = (form as? LinearForm.Conjunction)?.rows?.singleOrNull()
        var relevant = false
        for (row in form.rows) {
            if (!work.visit(1L)) return false
            if (row.activator != LinearRow.ALWAYS) continue
            var integer = true
            var real = false
            for (k in 0 until row.size) {
                if (!work.visit(1L)) return false
                val ref = row.ref(k)
                if (!Term.isInt(ref)) integer = false
                if (Term.isReal(ref)) real = true
            }
            val narrow = row.constants is IntegerConstants && !row.strict && !real
            // Strengthening can refute implied equalities, but replacing a row requires the whole factor.
            if (pass == PresolvePass.STRENGTHEN_COEFFICIENTS && row.relation == LinearOp.EQ && narrow) {
                relevant = true
            }
            if (row !== whole) continue
            // Specialization can turn a wide integral declaration into a narrow integer row.
            val integral = integer && row.constants is IntegralConstants
            if (integral || (pass == PresolvePass.STRENGTHEN_COEFFICIENTS && narrow)) relevant = true
            if (!integral || pass != PresolvePass.STRENGTHEN_COEFFICIENTS ||
                (row.relation != LinearOp.LE && row.relation != LinearOp.GE)
            ) continue
            for (k in 0 until row.size) {
                if (!work.visit(1L)) return false
                val v = Term.intVar(row.ref(k))
                val count = (columns[v] ?: 0) + direction
                if (count == 0) {
                    columns.remove(v)
                    ranges.remove(v)
                } else columns[v] = count
            }
        }
        if (relevant) revision++
        return true
    }

    private fun clear() {
        columns.clear()
        ranges.clear()
    }
}
