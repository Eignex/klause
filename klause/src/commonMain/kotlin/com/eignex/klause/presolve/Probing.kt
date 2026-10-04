package com.eignex.klause.presolve

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.IntegerConstants
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.impliedLinearRows
import com.eignex.klause.lp.IntervalBounds
import com.eignex.klause.lp.propagateRow
import com.eignex.klause.lp.rowRefuted
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.propagate
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.CheckedLongOverflowException
import com.eignex.klause.util.IntArrayDeque
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.LongArrayList
import com.eignex.klause.util.PollStride
import com.eignex.klause.util.addExact
import com.eignex.klause.util.subExact

internal object Probing {

    /**
     * Probing as a fixpoint presolve pass. For each free Boolean candidate it tentatively pins the
     * literal, runs the engine's own `Problem.propagate`, and harvests only deductions that hold in
     * **every** solution, so the problem's satisfiability and optimum are untouched:
     *  - **failed literal**: pinning `v = true` propagates to a conflict, so `v` is false in every
     *    solution — emit the unit clause `!v` (mirror for the negative polarity). Soundness rests
     *    entirely on the conflict: `propagate` is sound-but-incomplete, so an Unsat is a genuine
     *    proof of infeasibility, never a false positive.
     *  - **common bound**: a bound that propagation implies under *both* polarities holds
     *    unconditionally (the variable is true or false in any solution, and both cases force it).
     *    Every solution lies in one case or the other, so the valid deduction is their *union* —
     *    `min` of the two implied lower bounds, `max` of the two implied upper bounds. Taking the
     *    tighter bound of the pair instead would discard the solutions of the weaker case.
     *
     * Discovered binary implications are deliberately **not** persisted: there is no implication
     * store to hold them, and inventing one is out of scope. They are simply dropped.
     *
     * Idempotent: a derived unit already present is not re-added and a bound already at least as
     * tight is not re-applied, so the round engine reaches a fixpoint instead of churning. Bounded
     * by [maxCandidates] free Booleans per invocation (in id order) so a model with very many
     * Booleans cannot blow up presolve time; the round engine re-enters the pass after other passes
     * fire, and a later round picks up where bumping units shifted the free set.
     */
    fun probe(problem: BakedProblem, maxCandidates: Int, cancellation: Cancellation): PassDelta {
        val pinned = IntHashSet()
        for (f in problem.factors) {
            if (f is Clause && f.literals.size == 1) pinned.add(Lit.variable(f.literals[0]))
        }

        val units = ArrayList<Factor>()
        val domains = problem.rootIntDomains()
        var domainsChanged = false
        var probed = 0
        var v = 0
        while (v < problem.numBoolVars && probed < maxCandidates) {
            if (cancellation()) break
            if (v in pinned) {
                v++
                continue
            }
            probed++
            val tryTrue = problem.propagate(Assumptions.None.withBool(v, true), cancellation)
            if (tryTrue is PropagationResult.Unsat) {
                units.add(Clause(intArrayOf(Lit.make(v, false))))
                v++
                continue
            }
            val tryFalse = problem.propagate(Assumptions.None.withBool(v, false), cancellation)
            if (tryFalse is PropagationResult.Unsat) {
                units.add(Clause(intArrayOf(Lit.make(v, true))))
                v++
                continue
            }
            domainsChanged = harvestCommonBounds(
                tryTrue as PropagationResult.Implied,
                tryFalse as PropagationResult.Implied,
                problem,
                domains,
            ) || domainsChanged
            v++
        }

        if (units.isEmpty() && !domainsChanged) return PassDelta()
        return PassDelta(addedFactors = units, domains = if (domainsChanged) domains else null)
    }

    /**
     * Probing over a source model, where a column may be open and no engine propagator exists yet.
     *
     * Propagation reads only the rows factors declare as individually implied ([impliedLinearRows]), each
     * by interval reasoning over the declared ranges: a row with two unbounded terms implies nothing, so an
     * open side is never given a value it does not have. A reified row propagates in both directions — a
     * fixed activator asserts the row or its complement, and a row the ranges already rule out fixes the
     * activator against it. A Boolean literal is a 0/1 column, so a clause is just another row. Ignoring
     * the factors that declare no rows weakens propagation but never invents a conflict.
     *
     * The root fixpoint comes first; what it proves is kept. Probing then pins each free Boolean both
     * ways on top of it, with the same harvest as [probe]: a failed literal forces the other polarity, a
     * bound implied under both is the union of the two. Every deduction lands as a unit clause or a
     * proved range, so the result is idempotent once re-read.
     *
     * Bounded by [maxCandidates] probes and metered through [cancellation]; stopping early keeps whatever
     * was proved, since a partial propagation still only derives implied bounds.
     */
    fun probeSource(problem: Problem, maxCandidates: Int, cancellation: Cancellation): SourceDelta {
        val probe = OpenRangeProbe(problem, cancellation)
        if (!probe.hasRows) return SourceDelta()
        if (!probe.propagateRoot() || !probe.probeAll(maxCandidates)) return SourceDelta(infeasible = true)
        return probe.delta()
    }

    /** Fold the intersection of the bounds implied under each polarity into [domains]; return whether
     *  any domain was strictly tightened. A bound implied under both `v = true` and `v = false` holds
     *  in every solution, so the tighter side of the lower bounds and the looser-clamping side of the
     *  upper bounds are both globally valid. */
    private fun harvestCommonBounds(
        whenTrue: PropagationResult.Implied,
        whenFalse: PropagationResult.Implied,
        problem: Problem,
        domains: Array<IntDomain>,
    ): Boolean {
        var changed = false
        for (i in 0 until problem.numIntVars) {
            val d = domains[i]
            val loTrue = impliedMin(whenTrue, i)
            val loFalse = impliedMin(whenFalse, i)
            if (loTrue != null && loFalse != null) {
                val common = minOf(loTrue, loFalse)
                if (common > d.min) {
                    domains[i] = domains[i].withMinAtLeast(common)
                    changed = true
                }
            }
            val hiTrue = impliedMax(whenTrue, i)
            val hiFalse = impliedMax(whenFalse, i)
            if (hiTrue != null && hiFalse != null) {
                val common = maxOf(hiTrue, hiFalse)
                if (common < domains[i].max) {
                    domains[i] = domains[i].withMaxAtMost(common)
                    changed = true
                }
            }
        }
        return changed
    }

    /** Lower bound `propagate` implied for int [i] — a singleton pin counts as a lower bound at the
     *  pinned value — or `null` when this polarity implied nothing about [i]'s lower bound. */
    private fun impliedMin(implied: PropagationResult.Implied, i: Int): Long? =
        implied.intValueOrNull(i) ?: implied.intMinOrNullCompat(i)

    /** Upper bound `propagate` implied for int [i]; mirror of [impliedMin]. */
    private fun impliedMax(implied: PropagationResult.Implied, i: Int): Long? =
        implied.intValueOrNull(i) ?: implied.intMaxOrNullCompat(i)
}

// Columns are the model's integers followed by one 0/1 column per Boolean; a half-row is
// `Σ (sign·coeffs(k))·cols(k) ≤ sign·bound`, active unconditionally or only while its guard column holds
// `guardValue`.
@Suppress("TooManyFunctions")
private class OpenRangeProbe(private val problem: Problem, private val cancellation: Cancellation) : IntervalBounds {
    private val numInts = problem.numIntVars
    private val numCols = numInts + problem.numBoolVars
    private val lo = LongArray(numCols)
    private val hi = LongArray(numCols) { if (it >= numInts) 1L else 0L }
    private val loOpen = BooleanArray(numCols)
    private val hiOpen = BooleanArray(numCols)

    private val rowCoeffs = ArrayList<LongArray>()
    private val rowCols = ArrayList<IntArray>()
    private val rowBound = LongArrayList()
    private val rowSign = LongArrayList()
    private val rowGuard = IntArrayList()
    private val rowGuardValue = LongArrayList()
    private val incidenceStart: IntArray
    private val incidence: IntArray

    // Each entry packs `(col shl 2) | (wasOpen shl 1) | side`, side 0 for the lower bound; the value is the
    // bound the entry replaced.
    private val trail = IntArrayList()
    private val trailValue = LongArrayList()
    private val queue = IntArrayDeque()
    private val queued = BooleanArray(numCols)
    private val poll = PollStride()
    private var stopped = false

    private var conflict = false
    override val crossed: Boolean get() = conflict

    val hasRows: Boolean get() = rowCols.isNotEmpty()

    init {
        val bounds = problem.intBounds
        for (v in 0 until numInts) {
            loOpen[v] = bounds.isOpenLower(v)
            hiOpen[v] = bounds.isOpenUpper(v)
            if (!loOpen[v]) lo[v] = bounds.lower(v)
            if (!hiOpen[v]) hi[v] = bounds.upper(v)
        }
        for (factor in problem.factors) for (row in factor.impliedLinearRows) compile(row)
        val counts = IntArray(numCols + 1)
        forEachIncidence { col, _ -> counts[col + 1]++ }
        for (c in 0 until numCols) counts[c + 1] += counts[c]
        incidenceStart = counts.copyOf()
        incidence = IntArray(counts[numCols])
        forEachIncidence { col, row -> incidence[counts[col]++] = row }
    }

    private inline fun forEachIncidence(action: (col: Int, row: Int) -> Unit) {
        for (h in rowCols.indices) {
            for (col in rowCols[h]) action(col, h)
            val guard = rowGuard[h]
            if (guard >= 0) action(guard, h)
        }
    }

    // Literal terms become 0/1 columns: a negative literal `c·¬b` is `c − c·b`, so its constant moves to
    // the right-hand side. A row past 64 bits, over reals, or with wide constants is left out.
    private fun compile(row: LinearRow) {
        val constants = row.constants as? IntegerConstants ?: return
        val cols = IntArray(row.size)
        val coeffs = LongArray(row.size)
        var shift = 0L
        try {
            for (k in 0 until row.size) {
                val ref = row.ref(k)
                val c = constants.coeff(k)
                when {
                    Term.isInt(ref) -> {
                        cols[k] = Term.intVar(ref)
                        coeffs[k] = c
                    }

                    Term.isBool(ref) -> {
                        val literal = Term.lit(ref)
                        cols[k] = numInts + Lit.variable(literal)
                        if (Lit.isPositive(literal)) {
                            coeffs[k] = c
                        } else {
                            coeffs[k] = subExact(0L, c)
                            shift = addExact(shift, c)
                        }
                    }

                    else -> return
                }
            }
            val bound = subExact(constants.bound, shift)
            val guard = if (row.activator == LinearRow.ALWAYS) -1 else numInts + row.activator
            addRow(coeffs, cols, row.relation, bound, guard)
        } catch (_: CheckedLongOverflowException) {
            return
        }
    }

    private fun addRow(coeffs: LongArray, cols: IntArray, relation: LinearOp, bound: Long, guard: Int) {
        fun half(rhs: Long, sign: Long, guardValue: Long) {
            rowCoeffs.add(coeffs)
            rowCols.add(cols)
            rowBound.add(rhs)
            rowSign.add(sign)
            rowGuard.add(guard)
            rowGuardValue.add(guardValue)
        }
        if (relation == LinearOp.LE || relation == LinearOp.EQ) half(bound, 1L, 1L)
        if (relation == LinearOp.GE || relation == LinearOp.EQ) half(bound, -1L, 1L)
        if (guard < 0) return
        // The complement of an integer `≤ b` is `≥ b + 1`, and of `≥ b` is `≤ b − 1`.
        when (relation) {
            LinearOp.LE -> half(addExact(bound, 1L), -1L, 0L)
            LinearOp.GE -> half(subExact(bound, 1L), 1L, 0L)
            LinearOp.NE -> {
                half(bound, 1L, 0L)
                half(bound, -1L, 0L)
            }
            LinearOp.EQ -> Unit
        }
    }

    override fun loOpen(i: Int) = loOpen[i]
    override fun hiOpen(i: Int) = hiOpen[i]
    override fun loVal(i: Int) = lo[i]
    override fun hiVal(i: Int) = hi[i]

    override fun setLo(i: Int, v: Long) {
        if (!loOpen[i] && v <= lo[i]) return
        if (!hiOpen[i] && v > hi[i]) {
            conflict = true
            return
        }
        trail.add((i shl 2) or (if (loOpen[i]) 2 else 0))
        trailValue.add(lo[i])
        lo[i] = v
        loOpen[i] = false
        enqueue(i)
    }

    override fun setHi(i: Int, v: Long) {
        if (!hiOpen[i] && v >= hi[i]) return
        if (!loOpen[i] && v < lo[i]) {
            conflict = true
            return
        }
        trail.add((i shl 2) or (if (hiOpen[i]) 2 else 0) or 1)
        trailValue.add(hi[i])
        hi[i] = v
        hiOpen[i] = false
        enqueue(i)
    }

    private fun enqueue(col: Int) {
        if (queued[col]) return
        queued[col] = true
        queue.addLast(col)
    }

    private fun fixed(col: Int): Boolean = !loOpen[col] && !hiOpen[col] && lo[col] == hi[col]

    private fun undoTo(mark: Int) {
        while (trail.size > mark) {
            val last = trail.size - 1
            val entry = trail[last]
            val col = entry ushr 2
            val wasOpen = (entry and 2) != 0
            if ((entry and 1) == 0) {
                lo[col] = trailValue[last]
                loOpen[col] = wasOpen
            } else {
                hi[col] = trailValue[last]
                hiOpen[col] = wasOpen
            }
            trail.truncateTo(last)
            trailValue.truncateTo(last)
        }
        while (queue.isNotEmpty()) queued[queue.removeFirst()] = false
        conflict = false
    }

    private fun process(h: Int) {
        val cols = rowCols[h]
        cancellation.charge(PROBE_SOURCE_WORK_WEIGHT * (1L + cols.size))
        val guard = rowGuard[h]
        val coeffs = rowCoeffs[h]
        val bound = rowBound[h]
        val sign = rowSign[h]
        if (guard >= 0 && !fixed(guard)) {
            if (rowRefuted(coeffs, cols, bound, sign, this)) {
                if (rowGuardValue[h] == 1L) setHi(guard, 0L) else setLo(guard, 1L)
            }
        } else if (guard < 0 || lo[guard] == rowGuardValue[h]) {
            // A row with no column left has nothing to narrow, so only the refutation can fire.
            if (cols.isEmpty()) {
                if (rowRefuted(coeffs, cols, bound, sign, this)) conflict = true
            } else {
                propagateRow(coeffs, cols, bound, sign, this)
            }
        }
    }

    // Run queued columns to a fixpoint; false on a conflict. Interval reasoning over a cycle of rows can
    // creep a bound inward one unit per visit, so each fixpoint is capped at a few visits per row; stopping
    // there, or on a spent budget, leaves the bounds derived so far, every one of them implied.
    private fun propagate(): Boolean {
        var visits = 0L
        val cap = PROBE_SOURCE_VISITS_PER_ROW * (1L + rowCols.size)
        while (!crossed && !stopped && queue.isNotEmpty()) {
            val col = queue.removeFirst()
            queued[col] = false
            for (k in incidenceStart[col] until incidenceStart[col + 1]) {
                process(incidence[k])
                if (crossed) break
            }
            visits += incidenceStart[col + 1] - incidenceStart[col]
            if (poll.due() && cancellation()) stopped = true
            if (visits > cap) break
        }
        while (queue.isNotEmpty()) queued[queue.removeFirst()] = false
        return !crossed
    }

    fun propagateRoot(): Boolean {
        for (h in rowCols.indices) {
            process(h)
            if (crossed) return false
        }
        return propagate()
    }

    // Per-probe scratch: the columns the `true` branch touched, stamped by probe, and their bounds there.
    private val stamp = IntArray(numCols)
    private val slot = IntArray(numCols)
    private var probeId = 0
    private val touchedLo = LongArrayList()
    private val touchedHi = LongArrayList()
    private val touchedLoOpen = ArrayList<Boolean>()
    private val touchedHiOpen = ArrayList<Boolean>()
    private val pending = IntArrayList()
    private val pendingValue = LongArrayList()

    // Probe up to `maxCandidates` free Booleans; false when the model is refuted.
    fun probeAll(maxCandidates: Int): Boolean {
        var probed = 0
        var b = 0
        while (b < problem.numBoolVars && probed < maxCandidates && !stopped) {
            val col = numInts + b++
            if (fixed(col) || incidenceStart[col] == incidenceStart[col + 1]) continue
            probed++
            if (!probe(col)) return false
            if (!stopped && cancellation()) stopped = true
        }
        return true
    }

    @Suppress("ReturnCount")
    private fun probe(col: Int): Boolean {
        val mark = trail.size
        setLo(col, 1L)
        val trueHolds = propagate()
        if (trueHolds) recordTouched(mark)
        undoTo(mark)
        setHi(col, 0L)
        val falseHolds = propagate()
        if (trueHolds && falseHolds) collectCommon(mark)
        undoTo(mark)
        when {
            !trueHolds && !falseHolds -> return false
            !trueHolds -> setHi(col, 0L)
            !falseHolds -> setLo(col, 1L)
            else -> applyPending()
        }
        return !crossed && propagate()
    }

    private fun applyPending() {
        for (k in 0 until pending.size) {
            val entry = pending[k]
            if ((entry and 1) == 0) setLo(entry ushr 1, pendingValue[k]) else setHi(entry ushr 1, pendingValue[k])
        }
    }

    private fun recordTouched(mark: Int) {
        probeId++
        touchedLo.clear()
        touchedHi.clear()
        touchedLoOpen.clear()
        touchedHiOpen.clear()
        for (k in mark until trail.size) {
            val col = trail[k] ushr 2
            if (stamp[col] == probeId) continue
            stamp[col] = probeId
            slot[col] = touchedLo.size
            touchedLo.add(lo[col])
            touchedHi.add(hi[col])
            touchedLoOpen.add(loOpen[col])
            touchedHiOpen.add(hiOpen[col])
        }
    }

    // A column narrowed under both polarities is narrowed in every solution, to the union of the two.
    // Read off the `false` branch's trail before it is undone; applied once the root is restored.
    private fun collectCommon(mark: Int) {
        pending.clear()
        pendingValue.clear()
        for (k in mark until trail.size) {
            val entry = trail[k]
            val col = entry ushr 2
            if (stamp[col] != probeId) continue
            val s = slot[col]
            if ((entry and 1) == 0) {
                if (!touchedLoOpen[s] && !loOpen[col]) {
                    pending.add(col shl 1)
                    pendingValue.add(minOf(touchedLo[s], lo[col]))
                }
            } else if (!touchedHiOpen[s] && !hiOpen[col]) {
                pending.add((col shl 1) or 1)
                pendingValue.add(maxOf(touchedHi[s], hi[col]))
            }
        }
    }

    fun delta(): SourceDelta {
        val present = IntHashSet()
        for (f in problem.factors) if (f is Clause && f.literals.size == 1) present.add(f.literals[0])
        val units = ArrayList<Factor>()
        for (b in 0 until problem.numBoolVars) {
            val col = numInts + b
            if (!fixed(col)) continue
            val literal = Lit.make(b, lo[col] == 1L)
            if (literal !in present) units.add(Clause(intArrayOf(literal)))
        }
        val tightening = problem.intBounds.tightening()
        for (v in 0 until numInts) {
            if (!loOpen[v]) tightening.atLeast(v, lo[v])
            if (!hiOpen[v]) tightening.atMost(v, hi[v])
        }
        return SourceDelta(addedFactors = units, bounds = tightening.build())
    }
}

// Uncalibrated: one unit per row term visited, scaled like the other source passes.
private const val PROBE_SOURCE_WORK_WEIGHT = 10L

// Row visits one fixpoint may spend, per row of the model.
private const val PROBE_SOURCE_VISITS_PER_ROW = 8L
