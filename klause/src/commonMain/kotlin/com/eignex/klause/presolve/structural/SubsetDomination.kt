package com.eignex.klause.presolve.structural

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.impliedLinearRows
import com.eignex.klause.ir.linearRows
import com.eignex.klause.presolve.ColumnRanges
import com.eignex.klause.presolve.PresolveInvocation
import com.eignex.klause.presolve.PresolveShared
import com.eignex.klause.presolve.observe
import com.eignex.klause.presolve.presolveBudget
import com.eignex.klause.solver.result.PresolveInvocationOutcome
import com.eignex.klause.solver.result.PresolveLane
import com.eignex.klause.solver.result.PresolveSizeStats
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.CheckedLongOverflowException
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.addExact
import com.eignex.klause.util.mulExact

internal class SubsetDomination {
    private data class Signature(val terms: List<Pair<Int, Long>>, val bound: Long)
    private class Row(val factor: Int, val signature: Signature, val droppable: Boolean) {
        val coefficients = signature.terms.toMap()
        val width get() = signature.terms.size
        val anchor get() = signature.terms.first().first
    }
    private data class Activity(val row: Signature, val endpoints: List<Long?>)
    private class Progress {
        var version = -1L
        var variable = 0
        var row = 0
        var complete = false
        val tested = HashSet<Signature>()
    }

    private var previous = emptySet<Signature>()
    private val anchorVersions = HashMap<Int, MutableMap<Int, Long>>()
    private var version = 0L
    private val progress = HashMap<Activity, Progress>()
    private var rememberedPairs = 0

    fun removeDominated(
        problem: Problem,
        ranges: ColumnRanges,
        factors: List<Factor>,
        cancellation: Cancellation,
    ): List<Factor> {
        val budget = cancellation.presolveBudget()
        val lane = budget?.activeInvocation?.lane ?: PresolveLane.SOURCE
        var output = factors
        return budget.observe("subsume-subset", lane, {
            PresolveSizeStats(output.size, problem.numBoolVars, problem.numIntVars, reals = problem.numRealVars)
        }, null) { invocation ->
            val scan = Scan(cancellation)
            invocation?.outcome = PresolveInvocationOutcome.ELIGIBILITY_SKIP
            if (problem.numIntVars == 0) return@observe factors
            output = analyze(ranges, factors, scan, invocation)
            if (scan.stopped) invocation?.outcome = PresolveInvocationOutcome.INTERRUPTED
            output
        }
    }

    private fun analyze(
        ranges: ColumnRanges,
        factors: List<Factor>,
        scan: Scan,
        invocation: PresolveInvocation?,
    ): List<Factor> {
        val rows = ArrayList<Row>()
        for ((i, factor) in factors.withIndex()) {
            if (!scan.visit()) return factors
            if (factor is Clause) continue
            val droppable = factor.linearForm is LinearForm.Conjunction && factor.linearRows.size == 1
            for (row in factor.impliedLinearRows) {
                if (row.size > SUBSET_WIDTH_CAP) continue
                if (!scan.visit(1 + 4 * row.size)) return factors
                normalized(row, i, droppable)?.let(rows::add)
            }
        }
        val minimum = rows.minOfOrNull { it.width } ?: return factors
        val candidates = rows.filter { it.droppable && it.width > minimum }
        if (candidates.isEmpty()) return factors

        val byAnchor = HashMap<Int, MutableList<Row>>()
        val current = rows.mapTo(HashSet()) { it.signature }
        val changed = (current - previous) + (previous - current)
        version++
        for (signature in changed) {
            if (!scan.visit()) return factors
            anchorVersions.getOrPut(signature.terms.first().first) { HashMap() }[signature.terms.size] = version
        }
        previous = current
        for (row in rows.sortedBy { it.width }) {
            if (!scan.visit()) return factors
            byAnchor.getOrPut(row.anchor) { ArrayList() }.add(row)
        }
        val active = HashSet<Activity>()
        val activities = ArrayList<Pair<Row, Activity>>(candidates.size)
        for (candidate in candidates) {
            if (!scan.visit(candidate.width)) return factors
            val activity = Activity(candidate.signature, candidate.signature.terms.map { (v, coefficient) ->
                if (!ranges.isClosed(v)) null else if (coefficient >= 0L) ranges.max(v) else ranges.min(v)
            })
            active.add(activity)
            activities.add(candidate to activity)
        }
        val iterator = progress.iterator()
        while (iterator.hasNext()) {
            if (!scan.visit()) return factors
            val entry = iterator.next()
            if (entry.key !in active) {
                rememberedPairs -= entry.value.tested.size
                iterator.remove()
            }
        }

        val dropped = IntHashSet()
        for ((candidate, activity) in activities) {
            if (!scan.visit(candidate.width)) break
            var stamp = 0L
            for ((v, _) in candidate.signature.terms) {
                for ((width, changedAt) in anchorVersions[v].orEmpty()) {
                    if (!scan.visit()) break
                    if (width < candidate.width) stamp = maxOf(stamp, changedAt)
                }
            }
            if (scan.stopped) break
            val state = progress.getOrPut(activity) { Progress() }
            if (state.version != stamp) {
                state.version = stamp
                state.variable = 0
                state.row = 0
                state.complete = false
            }
            if (state.complete) continue
            compare(candidate, ranges, byAnchor, state, dropped, scan, invocation)
            if (scan.stopped) break
        }
        if (dropped.isEmpty()) return factors
        invocation?.outcome = PresolveInvocationOutcome.CHANGED
        return factors.filterIndexed { i, _ -> i !in dropped }
    }

    private fun compare(
        candidate: Row,
        ranges: ColumnRanges,
        byAnchor: Map<Int, List<Row>>,
        state: Progress,
        dropped: IntHashSet,
        scan: Scan,
        invocation: PresolveInvocation?,
    ) {
        while (state.variable < candidate.width) {
            val anchor = candidate.signature.terms[state.variable].first
            val possible = byAnchor[anchor].orEmpty()
            while (state.row < possible.size) {
                val dominator = possible[state.row]
                if (!scan.visit()) return
                if (dominator.width >= candidate.width) break
                if (!scan.visit(dominator.width)) return
                if (dominator.factor == candidate.factor || dominator.signature in state.tested) {
                    state.row++
                    continue
                }
                if (rememberedPairs >= SUBSET_PAIR_CAP) {
                    scan.stopped = true
                    return
                }
                if (!scan.visit(candidate.width)) return
                invocation?.outcome = PresolveInvocationOutcome.COMPLETE_MISS
                if (dominates(ranges, dominator, candidate)) {
                    dropped.add(candidate.factor)
                    state.complete = true
                    return
                }
                state.tested.add(dominator.signature)
                rememberedPairs++
                state.row++
            }
            state.variable++
            state.row = 0
        }
        state.complete = true
    }

    private fun normalized(row: LinearRow, factor: Int, droppable: Boolean): Row? {
        if (!row.isIntegerOnly || !row.isLongUnconditional || row.bound == Long.MIN_VALUE ||
            (row.relation != LinearOp.LE && row.relation != LinearOp.GE) ||
            (0 until row.size).any { row.coeff(it) == Long.MIN_VALUE }
        ) return null
        val coefficients = LongArray(row.size) { row.coeff(it) }
        val gcd = maxOf(1L, PresolveShared.gcdOf(coefficients))
        val sign = if (row.relation == LinearOp.LE) 1L else -1L
        val terms = (0 until row.size).filter { coefficients[it] != 0L }
            .map { Term.intVar(row.ref(it)) to sign * (coefficients[it] / gcd) }.sortedBy { it.first }
        if (terms.isEmpty() || terms.map { it.first }.toSet().size != terms.size) return null
        return Row(factor, Signature(terms, (sign * row.bound).floorDiv(gcd)), droppable)
    }

    // Strictly smaller supports make implication chains acyclic, including dropped dominators.
    private fun dominates(ranges: ColumnRanges, a: Row, b: Row): Boolean = try {
        dominationWithinBound(ranges, a, b)
    } catch (_: CheckedLongOverflowException) {
        false
    }

    private fun dominationWithinBound(ranges: ColumnRanges, a: Row, b: Row): Boolean {
        var scale = 0L
        for ((v, ca) in a.signature.terms) {
            val cb = b.coefficients[v] ?: return false
            if (cb % ca != 0L) return false
            val ratio = cb / ca
            if (ratio <= 0L || (scale != 0L && scale != ratio)) return false
            scale = ratio
        }
        var extra = 0L
        for ((v, cb) in b.signature.terms) {
            if (v in a.coefficients) continue
            if (!ranges.isClosed(v)) return false
            extra = addExact(extra, mulExact(cb, if (cb >= 0L) ranges.max(v) else ranges.min(v)))
        }
        return addExact(mulExact(scale, a.signature.bound), extra) <= b.signature.bound
    }

    private class Scan(private val cancellation: Cancellation) {
        private var visits = 0
        var stopped = false

        fun visit(entries: Int = 1): Boolean {
            if (stopped) return false
            if (entries > SUBSET_VISIT_CAP - visits || cancellation()) {
                stopped = true
                return false
            }
            visits += entries
            cancellation.charge(SUBSET_WORK_WEIGHT * entries)
            if (cancellation()) stopped = true
            return !stopped
        }
    }
}

private const val SUBSET_WIDTH_CAP = 128
private const val SUBSET_VISIT_CAP = 65_536
private const val SUBSET_PAIR_CAP = 65_536
private const val SUBSET_WORK_WEIGHT = 10L
