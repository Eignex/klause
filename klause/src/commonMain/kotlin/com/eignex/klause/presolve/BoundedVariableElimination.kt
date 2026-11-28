package com.eignex.klause.presolve

import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.IntHashSet

// Bounded variable elimination over the pure-SAT part of the model. A Boolean `v` is eliminated by
// replacing every clause on `v` with the non-tautological resolvents of its two polarities, only when
// neither the clause count nor the literal volume grows; a monotone `v`'s clauses simply drop. Only
// [SatClauseDb.eligible] variables qualify. The eliminated `v` is left unconstrained, so this is not
// solution-set preserving; [asRebuilds] recovers its value.
// N. Eén, A. Biere, "Effective preprocessing in SAT through variable and clause elimination", SAT 2005,
// https://doi.org/10.1007/11499107_5.
internal object BoundedVariableElimination {

    // Per-variable bound so a high-degree variable cannot blow up pairwise resolution.
    private const val RESOLVENT_PRODUCT_CAP = 4_096

    private const val RESOLVENT_WIDTH_CAP = 64
    private const val PIVOT_WORK_CAP = 65_536
    private const val CANCEL_POLL_MASK = 0xFF

    fun eliminate(
        problem: Problem,
        objectiveBoolVars: Set<Int> = emptySet(),
        cancellation: ExecutionControl = ExecutionControl.Never,
    ): PassDelta = run(problem, objectiveBoolVars, cancellation) { db, rebuild, infeasible ->
        if (infeasible) PassDelta(infeasible = true) else db.toDelta(rebuild)
    } ?: PassDelta()

    // Resolution reads only the clause database, so it holds before a finite projection exists.
    fun eliminateSource(
        problem: Problem,
        objectiveBoolVars: Set<Int> = emptySet(),
        cancellation: ExecutionControl = ExecutionControl.Never,
    ): SourceDelta = run(problem, objectiveBoolVars, cancellation) { db, rebuild, infeasible ->
        if (infeasible) SourceDelta(infeasible = true) else db.toSourceDelta(rebuild)
    } ?: SourceDelta()

    private fun <T> run(
        problem: Problem,
        objectiveBoolVars: Set<Int>,
        cancellation: ExecutionControl,
        delta: (SatClauseDb, SourceRebuilds, Boolean) -> T,
    ): T? {
        val nb = problem.numBoolVars
        if (nb == 0) return null
        val db = SatClauseDb.build(problem, objectiveBoolVars, cancellation) ?: return null

        val eliminations = ArrayList<VarElim>()
        val order = candidateOrder(db, cancellation) ?: return null
        for (v in order) {
            if (cancellation()) break
            if (eliminateVar(v, db, eliminations, cancellation)) {
                return delta(db, eliminations.asRebuilds(), true)
            }
        }

        return delta(db, eliminations.asRebuilds(), false)
    }

    private fun candidateOrder(db: SatClauseDb, cancellation: ExecutionControl): IntArray? {
        val candidates = IntArrayList()
        val degrees = LongArray(db.numBoolVars)
        for (v in 0 until db.numBoolVars) {
            cancellation.charge(BVE_WORK_WEIGHT)
            if (cancellation()) return null
            if (!db.eligible[v]) continue
            candidates.add(v)
            degrees[v] = db.occ(Lit.make(v, true)).size.toLong() + db.occ(Lit.make(v, false)).size
        }
        if (cancellation()) return null
        var order = candidates.toIntArray()
        if (cancellation()) return null
        var scratch = IntArray(order.size)
        // Stable cheapest-first order keeps the original variable order for equal degrees.
        var width = 1
        while (width < order.size) {
            var lo = 0
            while (lo < order.size) {
                val mid = minOf(lo.toLong() + width, order.size.toLong()).toInt()
                val hi = minOf(mid.toLong() + width, order.size.toLong()).toInt()
                var left = lo
                var right = mid
                for (out in lo until hi) {
                    cancellation.charge(BVE_WORK_WEIGHT)
                    if (cancellation()) return null
                    scratch[out] = if (left < mid && (right >= hi || degrees[order[left]] <= degrees[order[right]])) {
                        order[left++]
                    } else {
                        order[right++]
                    }
                }
                lo = hi
            }
            val previous = order
            order = scratch
            scratch = previous
            if (width > order.size / 2) break
            width *= 2
        }
        return if (cancellation()) null else order
    }

    private fun eliminateVar(
        v: Int,
        db: SatClauseDb,
        eliminations: ArrayList<VarElim>,
        cancellation: ExecutionControl,
    ): Boolean {
        val work = PivotWork(cancellation)
        if (!work.visit()) return false
        val posSlots = IntArrayList()
        val negSlots = IntArrayList()
        val posLits = ArrayList<IntArray>()
        val negLits = ArrayList<IntArray>()
        if (!gather(db, Lit.make(v, true), posSlots, posLits, work)) return false
        if (!gather(db, Lit.make(v, false), negSlots, negLits, work)) return false
        if (posLits.isEmpty() && negLits.isEmpty()) return false

        val clauses = ArrayList<IntArray>(posLits.size + negLits.size)
        clauses.addAll(posLits)
        clauses.addAll(negLits)

        // Monotone variable: its clauses are all satisfied by the pure value, so they drop with no
        // resolvent. Otherwise resolve pos × neg and keep the elimination only if it is bounded.
        val resolvents: List<IntArray>
        if (posLits.isEmpty() || negLits.isEmpty()) {
            resolvents = emptyList()
        } else {
            if (posLits.size.toLong() * negLits.size > RESOLVENT_PRODUCT_CAP) return false
            val removedVolume = clauses.sumOf { it.size.toLong() }
            var addedVolume = 0L
            val out = ArrayList<IntArray>()
            val seen = HashSet<List<Int>>() // exact identity: a lossy hash could drop a needed resolvent
            for (a in posLits.indices) {
                for (b in negLits.indices) {
                    if (!work.visit()) return false
                    val r = resolve(posLits[a], negLits[b], v, work)
                    if (work.stopped) return false
                    if (r == null) continue // tautology
                    // A complete empty resolvent refutes the input even if the pivot is not committed.
                    if (r.isEmpty()) return true
                    if (r.size > RESOLVENT_WIDTH_CAP) return false
                    if (seen.add(r.sorted())) {
                        addedVolume += r.size
                        if (addedVolume > removedVolume) return false
                        out.add(r)
                    }
                    if (out.size > clauses.size) return false // unbounded: give up before committing
                }
            }
            resolvents = out
        }

        if (cancellation()) return false
        // Commit only a complete pivot, retaining earlier eliminations if this pivot is declined.
        for (k in 0 until posSlots.size) db.remove(posSlots[k])
        for (k in 0 until negSlots.size) db.remove(negSlots[k])
        for (r in resolvents) db.add(r)
        eliminations.add(VarElim(v, clauses))
        return false
    }

    private fun gather(
        db: SatClauseDb,
        lit: Int,
        slots: IntArrayList,
        lits: ArrayList<IntArray>,
        work: PivotWork,
    ): Boolean {
        val col = db.occ(lit)
        for (k in 0 until col.size) {
            if (!work.visit()) return false
            val s = col[k]
            val c = db.clause(s) ?: continue
            slots.add(s)
            lits.add(c)
        }
        return true
    }

    // Null for a tautology.
    private fun resolve(c1: IntArray, c2: IntArray, v: Int, work: PivotWork): IntArray? {
        val lits = IntHashSet()
        for (l in c1) {
            if (!work.visit()) return null
            if (Lit.variable(l) != v) lits.add(l)
        }
        for (l in c2) {
            if (!work.visit()) return null
            if (Lit.variable(l) == v) continue
            if (Lit.negate(l) in lits) return null
            lits.add(l)
        }
        return lits.toIntArray()
    }

    private class PivotWork(private val cancellation: ExecutionControl) {
        private var visits = 0
        var stopped = false
            private set

        fun visit(): Boolean {
            if (visits >= PIVOT_WORK_CAP) {
                stopped = true
                return false
            }
            visits++
            cancellation.charge(BVE_WORK_WEIGHT)
            if ((visits and CANCEL_POLL_MASK) == 0 && cancellation()) stopped = true
            return !stopped
        }
    }

    private class VarElim(val v: Int, val clauses: List<IntArray>)

    // Latest first, so a variable whose clauses mention a later-eliminated one reads its recovered value.
    // Each recovered variable satisfies its clauses; resolution guarantees no two clauses force opposite
    // polarities.
    private fun List<VarElim>.asRebuilds(): SourceRebuilds =
        SourceRebuilds(asReversed().map { RebuildStep.SatisfyClauses(it.v, it.clauses) })
}

// Work units per candidate, occurrence, literal or clause pair visited, relative to one simplex operation.
private const val BVE_WORK_WEIGHT = 10L
