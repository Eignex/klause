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
import com.eignex.klause.util.ScanMeter
import com.eignex.klause.util.addExact
import com.eignex.klause.util.mulExact

internal class SubsetDomination {
    private data class Signature(val terms: List<Pair<Int, Long>>, val bound: Long)
    private class Normal(val signature: Signature, val droppable: Boolean) {
        val coefficients = signature.terms.toMap()
    }
    private class Row(val factor: Int, val normal: Normal) {
        val signature get() = normal.signature
        val coefficients get() = normal.coefficients
        val droppable get() = normal.droppable
        val width get() = signature.terms.size
        val anchor get() = signature.terms.first().first
    }
    // A factor is immutable, so its rows normalize once and the same occurrence reuses them in every later call.
    private class Normalized(val rows: List<Normal>, var call: Long)
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
    private val normalized = HashMap<Factor, Normalized>()
    private var calls = 0L

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
            try {
                output = if (scan.check()) analyze(ranges, factors, scan, invocation) else factors
            } finally {
                scan.flush()
            }
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
        val rows = rows(factors, scan) ?: return factors
        val minimum = rows.minOfOrNull { it.width } ?: return factors
        val candidates = rows.filter { it.droppable && it.width > minimum }
        if (candidates.isEmpty()) return factors

        val byAnchor = HashMap<Int, MutableList<Row>>()
        val current = rows.mapTo(HashSet()) { it.signature }
        val changed = (current - previous) + (previous - current)
        version++
        for (signature in changed) {
            if (!scan.track()) return factors
            anchorVersions.getOrPut(signature.terms.first().first) { HashMap() }[signature.terms.size] = version
        }
        previous = current
        for (row in rows.sortedBy { it.width }) {
            if (!scan.track()) return factors
            byAnchor.getOrPut(row.anchor) { ArrayList() }.add(row)
        }
        val active = HashSet<Activity>()
        val activities = ArrayList<Pair<Row, Activity>>(candidates.size)
        for (candidate in candidates) {
            if (!scan.track(candidate.width)) return factors
            val activity = Activity(candidate.signature, candidate.signature.terms.map { (v, coefficient) ->
                if (!ranges.isClosed(v)) null else if (coefficient >= 0L) ranges.max(v) else ranges.min(v)
            })
            active.add(activity)
            activities.add(candidate to activity)
        }
        val iterator = progress.iterator()
        while (iterator.hasNext()) {
            if (!scan.track()) return factors
            val entry = iterator.next()
            if (entry.key !in active) {
                rememberedPairs -= entry.value.tested.size
                iterator.remove()
            }
        }

        val dropped = IntHashSet()
        for ((candidate, activity) in activities) {
            if (!scan.track(candidate.width)) break
            var stamp = 0L
            for ((v, _) in candidate.signature.terms) {
                for ((width, changedAt) in anchorVersions[v].orEmpty()) {
                    if (!scan.track()) break
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
                    scan.stop()
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

    // Every current occurrence is walked each call, but only factors no earlier call finished normalizing spend capped
    // visits, so bounded calls over one model reach their comparisons after finitely many calls.
    private fun rows(factors: List<Factor>, scan: Scan): List<Row>? {
        val call = ++calls
        val rows = ArrayList<Row>()
        var live = 0
        for ((i, factor) in factors.withIndex()) {
            if (!scan.track()) return null
            if (factor is Clause) continue
            val entry = normalized[factor] ?: normalize(factor, scan) ?: return null
            if (entry.call != call) live++
            entry.call = call
            for (normal in entry.rows) rows.add(Row(i, normal))
        }
        if (normalized.size > live) normalized.values.removeAll { it.call != call }
        return rows
    }

    private fun normalize(factor: Factor, scan: Scan): Normalized? {
        val droppable = factor.linearForm is LinearForm.Conjunction && factor.linearRows.size == 1
        val rows = ArrayList<Normal>()
        for (row in factor.impliedLinearRows) {
            if (row.size > SUBSET_WIDTH_CAP) continue
            if (!scan.visit(1 + 4 * row.size)) return null
            normalized(row, droppable)?.let(rows::add)
        }
        return Normalized(rows, 0L).also { normalized[factor] = it }
    }

    private fun normalized(row: LinearRow, droppable: Boolean): Normal? {
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
        return Normal(Signature(terms, (sign * row.bound).floorDiv(gcd)), droppable)
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

    private class Scan(cancellation: Cancellation) {
        private val meter = ScanMeter(cancellation)
        private var visits = 0
        private var capped = false
        val stopped get() = capped || meter.stopped

        // Normalization and comparison visits, capped per call.
        fun visit(entries: Int = 1): Boolean {
            if (stopped) return false
            if (entries > SUBSET_VISIT_CAP - visits) {
                capped = true
                return false
            }
            visits += entries
            return meter.visit(SUBSET_WORK_WEIGHT * entries)
        }

        // Upkeep linear in the current rows and candidates, charged outside the cap so that it cannot starve the
        // comparisons once every row is normalized.
        fun track(entries: Int = 1): Boolean = !stopped && meter.visit(SUBSET_WORK_WEIGHT * entries)

        fun check(): Boolean = !stopped && meter.check()

        fun stop() {
            capped = true
        }

        fun flush() = meter.flush()
    }
}

private const val SUBSET_WIDTH_CAP = 128
private const val SUBSET_VISIT_CAP = 65_536
private const val SUBSET_PAIR_CAP = 65_536
private const val SUBSET_WORK_WEIGHT = 10L
