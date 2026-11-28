package com.eignex.klause.factor.table

import com.eignex.klause.config.DEFAULT_DOMAIN_WALK_CAP
import com.eignex.klause.factor.arithmetic.internals.collectHoleAndBoundAntecedents
import com.eignex.klause.factor.table.internals.TableGroupCache
import com.eignex.klause.factor.table.internals.TableStr2State
import com.eignex.klause.factor.table.internals.allEventWatches
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.values
import com.eignex.klause.propagation.ExtensionalSupportCounts
import com.eignex.klause.propagation.ExtensionalSupportProvider
import com.eignex.klause.propagation.InterruptiblePropagator
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.carvedAt
import com.eignex.klause.propagation.checkFiniteWork
import com.eignex.klause.propagation.domainAt
import com.eignex.klause.propagation.excludeIntValues
import com.eignex.klause.propagation.excludeInteriorIntValues
import com.eignex.klause.propagation.lazyReason
import com.eignex.klause.propagation.restrictIntToSurvivors
import com.eignex.util.arrays.binarySearchLong
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.IntHashSet
import com.eignex.util.collections.LongArrayList
import com.eignex.util.collections.LongHashSet
import com.eignex.util.collections.MutableLongIntMap

internal class TablePropagator(
    val boolVars: IntArray,
    val intVars: IntArray,
    private val xs: IntArray,
    private val tuples: LongArray,
    private val arity: Int,
    private val numTuples: Int,
    private val hi: LongArray?,
    // Shared by a group's rows over one relation, so later rows with the same column bounds skip re-sweeping.
    private val groupCache: TableGroupCache? = null,
) : InterruptiblePropagator, ExtensionalSupportProvider {

    private val unarySupports = boundedUnarySupports()
    private val supportCache = groupCache ?: TableGroupCache(arity, tuples, hi)

    override val expensiveBake: Boolean get() = unarySupports == null

    override fun interrupted(state: PropagationState, factorId: Int) {
        (state.refPayload[factorId] as? TableStr2State)?.started = false
    }

    override fun supportCounts(
        state: PropagationState,
        factorId: Int,
        variable: Int,
        values: LongArray,
        visit: () -> Boolean,
    ): ExtensionalSupportCounts? {
        val support = state.refPayload[factorId] as? TableStr2State ?: return null
        if (hi != null || !support.started || support.numValid == 0) return null
        var col = -1
        for (i in xs.indices) {
            if (!visit()) return null
            if (xs[i] == variable) {
                col = i
                break
            }
        }
        if (col < 0) return null
        val index = support.index
        val counts = IntArray(values.size)
        if (index != null) {
            if (!support.baselineMatches(visit)) return null
            val live = requireNotNull(support.live)
            for (i in values.indices) {
                counts[i] = index.columns[col].countLive(values[i], { live[it] }, visit) ?: return null
            }
        } else {
            for (i in 0 until support.numValid) {
                if (!visit()) return null
                val candidate = values.binarySearchLong(cellLo(support.validTuples[i], col))
                if (candidate >= 0) counts[candidate]++
            }
        }
        return ExtensionalSupportCounts(counts, support.numValid)
    }

    private fun cellLo(row: Int, col: Int): Long = tuples[row * arity + col]
    private fun cellHi(row: Int, col: Int): Long = hi?.get(row * arity + col) ?: tuples[row * arity + col]

    private fun boundedUnarySupports(): LongArray? {
        if (arity != 1 || numTuples > DEFAULT_DOMAIN_WALK_CAP) return null
        val supports = LongArrayList()
        for (row in 0 until numTuples) {
            val lo = cellLo(row, 0)
            val upper = cellHi(row, 0)
            val distance = upper - lo
            if (upper < lo || distance < 0 || distance >= DEFAULT_DOMAIN_WALK_CAP - supports.size) return null
            var value = lo
            while (true) {
                supports.add(value)
                if (value == upper) break
                value++
            }
        }
        val values = supports.toLongArray()
        values.sort()
        var kept = 0
        for (value in values) {
            if (kept == 0 || values[kept - 1] != value) values[kept++] = value
        }
        return values.copyOf(kept)
    }

    // STR2 is hole-aware GAC, so it watches every event kind.
    override val initialIntEventWatches: IntArray = allEventWatches(xs)

    override val consumesIntEventDelta: Boolean = true

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        if (state.undoLogging) supportLoss(state, -1, state.undo.size) else collectHoleAndBoundAntecedents(state, xs)

    override fun explain(
        state: PropagationState,
        factorId: Int,
        payload: IntArray,
        atTrail: Int,
        atLevel: Int,
    ): IntArray {
        if (payload.size == 1) return supportLoss(state, payload[0], atTrail)
        val value = (payload[2].toLong() shl 32) or (payload[3].toLong() and 0xFFFFFFFFL)
        return groundSupportLoss(state, payload[0], payload[1], value, atTrail)
    }

    // The conclusion identifies the ground cells that lost support. Other values' surviving tuples
    // cannot justify this deduction, so they need no historical support reconstruction.
    private fun groundSupportLoss(state: PropagationState, col: Int, kind: Int, value: Long, atTrail: Int): IntArray {
        val snapshot = ExplanationSnapshot(state, xs, atTrail)
        val out = LitSet()
        if (kind != VALUE) columnLiterals(state, col, snapshot, out)
        val pick = IntArrayList()
        visitRemovedRows(state, col, kind, value) { row ->
            state.checkFiniteWork()
            if (kind != VALUE && cellAt(state, row, col, snapshot, null) == Cell.KILLED) return@visitRemovedRows
            var chosen = Lit.NONE
            var killed = false
            for (c in 0 until arity) {
                state.checkFiniteWork()
                if (c == col) continue
                pick.clear()
                if (cellAt(state, row, c, snapshot, pick) == Cell.LIVE) continue
                killed = true
                if (pick.size == 0 || out.has(pick[0])) {
                    chosen = Lit.NONE
                    break
                }
                if (chosen == Lit.NONE) chosen = pick[0]
            }
            check(killed) { "a removed ground value must have no historical support" }
            if (chosen != Lit.NONE) out.add(chosen)
        }
        return out.toArray()
    }

    private fun visitRemovedRows(state: PropagationState, col: Int, kind: Int, value: Long, visit: (Int) -> Unit) {
        if ((kind == LOWER && value == Long.MIN_VALUE) || (kind == UPPER && value == Long.MAX_VALUE)) return
        val lower = if (kind == LOWER) Long.MIN_VALUE else if (kind == UPPER) value + 1 else value
        val upper = if (kind == UPPER) Long.MAX_VALUE else if (kind == LOWER) value - 1 else value
        val column = supportCache.supports { state.checkFiniteWork() }?.columns?.get(col)
        if (column != null) {
            column.forEachRow(lower, upper, visit) { state.checkFiniteWork() }
        } else {
            for (row in 0 until numTuples) {
                state.checkFiniteWork()
                if (cellLo(row, col) in lower..upper) visit(row)
            }
        }
    }

    internal fun historicalAlternative(
        state: PropagationState,
        payload: IntArray,
        atTrail: Int,
        preferred: IntHashSet,
    ): IntArray? {
        if (hi != null || payload.size != 4 || numTuples > 256 || arity > 64 ||
            numTuples.toLong() * arity > 4096
        ) return null
        val col = payload[0]
        val kind = payload[1]
        val value = (payload[2].toLong() shl 32) or (payload[3].toLong() and 0xFFFFFFFFL)
        val snapshot = ExplanationSnapshot(state, xs, atTrail)
        if (kind != VALUE && snapshot.domains[col].holeCount > 62) return null
        val out = LitSet()
        if (kind != VALUE) columnLiterals(state, col, snapshot, out)
        val examined = IntHashSet()
        for (lit in out.toArray()) examined.add(lit)
        val pick = IntArrayList()
        for (row in 0 until numTuples) {
            state.checkFiniteWork()
            val target = cellLo(row, col)
            if (when (kind) {
                    LOWER -> target >= value
                    UPPER -> target <= value
                    else -> target != value
                }) continue
            if (kind != VALUE && cellAt(state, row, col, snapshot, null) == Cell.KILLED) continue
            var chosen = Lit.NONE
            var killed = false
            for (c in 0 until arity) {
                state.checkFiniteWork()
                if (c == col) continue
                pick.clear()
                if (cellAt(state, row, c, snapshot, pick) == Cell.LIVE) continue
                killed = true
                if (pick.size > 0) examined.add(pick[0])
                if (examined.size > 64) return null
                if (pick.size == 0 || out.has(pick[0])) {
                    chosen = Lit.NONE
                    break
                }
                if (chosen == Lit.NONE || preferred.contains(pick[0])) chosen = pick[0]
                if (preferred.contains(chosen)) break
            }
            if (!killed) return null
            if (chosen != Lit.NONE) out.add(chosen)
            if (out.size > 64) return null
        }
        return out.toArray()
    }

    // Reason for [col]'s prune at [atTrail], or for running out of tuples when [col] = -1: the column's own domain,
    // and for each ruled-out tuple that held a removed value one column that ruled it out, preferring one already
    // cited, else the one needing fewest literals.
    private fun supportLoss(state: PropagationState, col: Int, atTrail: Int): IntArray {
        val snapshot = ExplanationSnapshot(state, xs, atTrail)
        val out = LitSet()
        if (col >= 0) columnLiterals(state, col, snapshot, out)
        val ruledOut = IntArrayList()
        val kept = ArrayList<LongRange>()
        val groundSupport = if (hi == null && col >= 0) LongHashSet() else null
        for (row in 0 until numTuples) {
            state.checkFiniteWork()
            if (col >= 0 && cellAt(state, row, col, snapshot, null) == Cell.KILLED) continue
            val stood = (0 until arity).all { c -> c == col || cellAt(state, row, c, snapshot, null) == Cell.LIVE }
            when {
                !stood -> ruledOut.add(row)

                groundSupport != null -> groundSupport.add(cellLo(row, col))

                col >= 0 -> kept.add(ownCell(row, col, snapshot))

                // The table cannot fail with a tuple standing, so a conflict found elsewhere rests on every domain.
                else -> for (c in 0 until arity) columnLiterals(state, c, snapshot, out)
            }
        }
        val support = mergeRanges(kept)
        val pick = IntArrayList()
        for (k in 0 until ruledOut.size) {
            state.checkFiniteWork()
            val row = ruledOut[k]
            if (col >= 0) {
                val supported = if (groundSupport != null) {
                    cellLo(row, col) in groundSupport
                } else {
                    covers(support, ownCell(row, col, snapshot))
                }
                if (supported) continue
            }
            var covered = false
            var best = -1
            var bestCost = Int.MAX_VALUE
            for (c in 0 until arity) {
                state.checkFiniteWork()
                if (c == col) continue
                pick.clear()
                if (cellAt(state, row, c, snapshot, pick) == Cell.LIVE) continue
                if ((0 until pick.size).all { out.has(pick[it]) }) {
                    covered = true
                    break
                }
                if (pick.size < bestCost) {
                    best = c
                    bestCost = pick.size
                }
            }
            if (covered) continue
            pick.clear()
            cellAt(state, row, best, snapshot, pick)
            for (i in 0 until pick.size) out.add(pick[i])
        }
        return out.toArray()
    }

    // The cell at (row, col) clipped to the historical column bounds.
    private fun ownCell(row: Int, col: Int, snapshot: ExplanationSnapshot): LongRange {
        val d = snapshot.domains[col]
        return maxOf(cellLo(row, col), d.min)..minOf(cellHi(row, col), d.max)
    }

    private enum class Cell { LIVE, KILLED }

    // When the cell missed column c's historical domain, adds the literals saying so: the bound it fell past and
    // each hole carved on the path. Root holes and survivor restrictions are unconditional and need none.
    private fun cellAt(
        state: PropagationState,
        row: Int,
        c: Int,
        snapshot: ExplanationSnapshot,
        into: IntArrayList?,
    ): Cell {
        state.checkFiniteWork()
        val v = xs[c]
        val lo = cellLo(row, c)
        val hiC = cellHi(row, c)
        val d = snapshot.domains[c]
        val root = state.rootDomains[v]
        if (hiC < d.min || lo > d.max) {
            if (into != null) {
                if (hiC < d.min && hiC >= root.min) into.add(snapshot.lowerLiteral(c))
                if (lo > d.max && lo <= root.max) into.add(snapshot.upperLiteral(c))
            }
            return Cell.KILLED
        }
        if (lo == hiC) {
            if (lo in d) return Cell.LIVE
            val pos = snapshot.carvedAt(c, lo)
            if (pos > snapshot.atTrail) return Cell.LIVE
            if (pos >= 0 && into != null) into.add(snapshot.holeLiteral(c, lo))
            return Cell.KILLED
        }
        val a = maxOf(lo, d.min)
        val b = minOf(hiC, d.max)
        var holes = 0L
        d.forEachHoleInRange(a, b) {
            state.checkFiniteWork()
            holes++
        }
        if (b - a >= holes) return Cell.LIVE
        var carvedLater = false
        d.forEachHoleInRange(a, b) { k ->
            state.checkFiniteWork()
            val pos = snapshot.carvedAt(c, k)
            if (pos > snapshot.atTrail) {
                carvedLater = true
            } else if (pos >= 0 && into != null) {
                into.add(snapshot.holeLiteral(c, k))
            }
        }
        if (carvedLater) return Cell.LIVE
        if (into != null) {
            if (lo < d.min && d.min > root.min) into.add(snapshot.lowerLiteral(c))
            if (hiC > d.max && d.max < root.max) into.add(snapshot.upperLiteral(c))
        }
        return Cell.KILLED
    }

    // Historical bounds above the root and the holes carved on the path inside them.
    private fun columnLiterals(state: PropagationState, col: Int, snapshot: ExplanationSnapshot, out: LitSet) {
        val v = xs[col]
        val root = state.rootDomains[v]
        val d = snapshot.domains[col]
        if (d.min > root.min) out.add(snapshot.lowerLiteral(col))
        if (d.max < root.max) out.add(snapshot.upperLiteral(col))
        val cite: (Long) -> Unit = { k ->
            state.checkFiniteWork()
            val pos = snapshot.carvedAt(col, k)
            if (pos in 0..snapshot.atTrail) out.add(snapshot.holeLiteral(col, k))
        }
        // Root holes need no literal; sparse root members can be much fewer than the span's holes.
        val members = root.spanOrNull(d.holeCount)
        if (members != null) {
            members.forEach { k -> if (k in d.min..d.max && k !in d) cite(k) }
        } else {
            d.forEachHole { k -> cite(k) }
        }
    }

    // One explanation is synchronous, so its historical reads are stable. Keeping this cache local
    // avoids carrying domain references or carve positions across subsequent propagation and undo.
    private class ExplanationSnapshot(
        private val state: PropagationState,
        private val xs: IntArray,
        val atTrail: Int,
    ) {
        val domains = Array(xs.size) {
            state.checkFiniteWork()
            state.domainAt(xs[it], atTrail)
        }
        private val carves = Array(xs.size) { MutableLongIntMap() }
        private val holes = Array(xs.size) { MutableLongIntMap() }
        private val lower = IntArray(xs.size) { -1 }
        private val upper = IntArray(xs.size) { -1 }

        fun lowerLiteral(col: Int): Int {
            if (lower[col] < 0) lower[col] = Lit.make(state.atomVarGe(xs[col], domains[col].min), false)
            return lower[col]
        }

        fun upperLiteral(col: Int): Int {
            if (upper[col] < 0) upper[col] = Lit.make(state.atomVarLe(xs[col], domains[col].max), false)
            return upper[col]
        }

        fun holeLiteral(col: Int, value: Long): Int {
            val cached = holes[col].getOrDefault(value, -1)
            if (cached >= 0) return cached
            val lit = Lit.make(state.atomVarEq(xs[col], value), true)
            holes[col].put(value, lit)
            return lit
        }

        fun carvedAt(col: Int, value: Long): Int {
            val cached = carves[col].getOrDefault(value, -2)
            if (cached != -2) return cached
            val pos = state.carvedAt(xs[col], value)
            carves[col].put(value, pos)
            return pos
        }
    }

    private class LitSet {
        private val seen = IntHashSet()
        private val lits = IntArrayList()

        val size: Int get() = lits.size

        fun has(lit: Int) = seen.contains(lit)

        fun add(lit: Int) {
            if (seen.add(lit)) lits.add(lit)
        }

        fun toArray(): IntArray = lits.toIntArray()
    }

    // STR2 (Lecoutre, "STR2: optimized simple tabular reduction for table constraints", Constraints 16(4), 2011,
    // https://doi.org/10.1007/s10601-011-9107-6) over a sparse set of live tuples whose size
    // [TableStr2State.numValid] is trailed, so a pop restores it in O(1). A short-support cell is feasible when its
    // interval meets the live domain, and supports every live value it covers.
    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        state.finiteWork.begin()
        val payload = state.refPayload[factorId]
        val existing = payload as? TableStr2State
        val dirty = state.drainIntEventDirtyVars(factorId)
        if ((existing?.started ?: (payload === NoopStarted)) && dirty.isEmpty()) return true
        // Unary supports are unconditional facts. Root folding retains them without walking the span;
        // search pruning uses STR2 to preserve its bound and hole explanations.
        val supports = unarySupports
        if (supports != null && state.currentLevel == 0 && !state.undoLogging) {
            val ok = state.restrictIntToSurvivors(xs[0], supports)
            if (ok) state.refPayload[factorId] = NoopStarted
            return ok
        }
        // The bitset support map is sized to the column span; wider columns take the value-keyed path.
        val bitsetEligible = (0 until arity).all { col ->
            val d = state.intDomains[xs[col]]
            d.min >= Int.MIN_VALUE.toLong() && d.max <= Int.MAX_VALUE.toLong() && d.max - d.min < MAX_BITSET_SPAN
        }
        // Under full contiguous domains, whether the sweep prunes is a function of (relation, column bounds), so
        // once one row of a group records a no-prune verdict, siblings with the same bounds skip their sweep. A
        // hole could remove a value's only support, so contiguity is required.
        val gc = groupCache
        if (gc != null && bitsetEligible) {
            var contiguous = true
            val mins = LongArray(arity)
            val maxs = LongArray(arity)
            for (col in 0 until arity) {
                state.checkFiniteWork()
                val d = state.intDomains[xs[col]]
                mins[col] = d.min
                maxs[col] = d.max
                if (d.holeCount != 0L) contiguous = false
            }
            if (contiguous && gc.isNoop(mins, maxs)) {
                // No live set: each row would otherwise allocate its own O(numTuples) index, which dominates peak
                // memory on the largest tables.
                if (existing == null) state.refPayload[factorId] = NoopStarted
                return true
            }
            val s = existing ?: newLiveSet(state, factorId)
            val ok = propagateBitset(state, s)
            if (ok) {
                s.commit()
                // Under full contiguous bounds the sweep pruned nothing iff every column still spans its bounds.
                if (contiguous && gc.noopMins == null) {
                    var noPrune = true
                    for (col in 0 until arity) {
                        state.checkFiniteWork()
                        if (state.intDomains[xs[col]].holeCount != 0L) {
                            noPrune = false
                            break
                        }
                    }
                    if (noPrune) gc.setNoop(mins, maxs)
                }
            }
            return ok
        }
        val s = existing ?: newLiveSet(state, factorId)
        val ok = if (bitsetEligible) propagateBitset(state, s) else propagateWide(state, s)
        if (ok) s.commit()
        return ok
    }

    // Every tuple starts live: a row reaching here has filtered nothing yet.
    private fun newLiveSet(state: PropagationState, factorId: Int): TableStr2State {
        val index = if (hi == null && numTuples >= 128) supportCache.supports { state.checkFiniteWork() } else null
        val fresh = TableStr2State(
            IntArray(numTuples) {
                state.checkFiniteWork()
                it
            }, numTuples, state, xs, index,
        )
        state.refPayload[factorId] = fresh
        return fresh
    }

    private fun cellFeasible(state: PropagationState, row: Int, col: Int, d: IntDomain): Boolean {
        val lo = cellLo(row, col)
        val hiC = cellHi(row, col)
        return if (lo == hiC) lo in d else domainOverlapsRange(state, d, lo, hiC)
    }

    private fun propagateBitset(state: PropagationState, s: TableStr2State): Boolean {
        val delta = s.beginSweep()
        val domLo = LongArray(arity)
        val domHi = LongArray(arity)
        val supportBits = arrayOfNulls<LongArray>(arity)
        val remainingSupports = IntArray(arity)
        // A cell whose interval covers the whole domain (a `*`, or a range spanning it) supports every
        // value of that column, so the column is fully supported and skips gathering and pruning.
        val fullySupported = BooleanArray(arity)
        for (col in 0 until arity) {
            state.checkFiniteWork()
            val d = state.intDomains[xs[col]]
            domLo[col] = d.min
            domHi[col] = d.max
            remainingSupports[col] = d.valueCount.toInt()
            val span = domHi[col] - domLo[col] + 1
            supportBits[col] = LongArray(((span + 63) ushr 6).toInt())
        }
        var numValid = s.numValid
        var i = 0
        while (i < numValid) {
            state.checkFiniteWork()
            val row = s.validTuples[i]
            val feasible = if (delta) {
                requireNotNull(s.live)[row ushr 6] and (1L shl (row and 63)) != 0L
            } else {
                (0 until arity).all { col ->
                    state.checkFiniteWork()
                    cellFeasible(state, row, col, state.intDomains[xs[col]])
                }
            }
            if (!feasible) {
                if (!delta) s.rejectRow(row)
                val last = numValid - 1
                if (i != last) {
                    s.validTuples[i] = s.validTuples[last]
                    s.validTuples[last] = row
                }
                numValid = last
            } else {
                for (col in 0 until arity) {
                    state.checkFiniteWork()
                    if (fullySupported[col]) continue
                    val lo = cellLo(row, col)
                    val hiC = cellHi(row, col)
                    if (lo <= domLo[col] && hiC >= domHi[col]) {
                        fullySupported[col] = true
                        continue
                    }
                    // Bits over non-domain offsets are harmless: the prune consults only in-domain positions.
                    val bits = requireNotNull(supportBits[col])
                    val first = (maxOf(lo, domLo[col]) - domLo[col]).toInt()
                    val last = (minOf(hiC, domHi[col]) - domLo[col]).toInt()
                    if (first == last) {
                        val word = first ushr 6
                        val mask = 1L shl (first and 63)
                        if (bits[word] and mask == 0L) {
                            bits[word] = bits[word] or mask
                            // Ground cells contribute only live values; the count includes sparse domains.
                            if (hi == null && --remainingSupports[col] == 0) fullySupported[col] = true
                        }
                    } else {
                        setSupportRange(state, bits, first, last)
                    }
                }
                i++
            }
        }
        // The sparse-set permutation restores every removed row when the prefix size is undone.
        s.numValid = numValid
        if (!delta) s.rebuildLive()
        if (numValid == 0) return false
        if (fullySupported.all { it }) return true
        val coarse by lazy(LazyThreadSafetyMode.NONE) { collectHoleAndBoundAntecedents(state, xs) }
        for (col in 0 until arity) {
            state.checkFiniteWork()
            if (fullySupported[col]) continue
            val ant = if (hi == null) null else columnReason(state, col) { coarse }
            val bits = requireNotNull(supportBits[col])
            var firstSet = -1
            for (w in bits.indices) {
                state.checkFiniteWork()
                if (bits[w] != 0L) {
                    firstSet = (w shl 6) + bits[w].countTrailingZeroBits()
                    break
                }
            }
            if (firstSet < 0) return false
            var lastSet = -1
            for (w in bits.indices.reversed()) {
                state.checkFiniteWork()
                if (bits[w] != 0L) {
                    lastSet = (w shl 6) + (63 - bits[w].countLeadingZeroBits())
                    break
                }
            }
            val minSup = domLo[col] + firstSet
            val maxSup = domLo[col] + lastSet
            if (minSup > state.intDomains[xs[col]].min &&
                !state.tightenIntMin(xs[col], minSup, deductionReason(state, col, LOWER, minSup, ant) { coarse })
            ) {
                return false
            }
            if (maxSup < state.intDomains[xs[col]].max &&
                !state.tightenIntMax(xs[col], maxSup, deductionReason(state, col, UPPER, maxSup, ant) { coarse })
            ) {
                return false
            }
            val d = state.intDomains[xs[col]]
            val colLo = domLo[col]
            val colHi = domHi[col]
            var toRemoveCount = 0
            val toRemove = LongArray(d.values.size)
            d.values.forEach { value ->
                state.checkFiniteWork()
                if (value in colLo..colHi) {
                    val off = (value - colLo).toInt()
                    if (((bits[off ushr 6] ushr (off and 63)) and 1L) == 0L) toRemove[toRemoveCount++] = value
                } else {
                    toRemove[toRemoveCount++] = value
                }
            }
            val removed = if (toRemoveCount == toRemove.size) toRemove else toRemove.copyOf(toRemoveCount)
            if (!excludeUnsupported(state, col, removed, ant) { coarse }) return false
        }
        return true
    }

    // Value-keyed supports for columns outside Int range or too wide for a bitset.
    private fun propagateWide(state: PropagationState, s: TableStr2State): Boolean {
        val delta = s.beginSweep()
        val supported = Array(arity) { LongHashSet(numTuples) }
        val minSup = LongArray(arity) { Long.MAX_VALUE }
        val maxSup = LongArray(arity) { Long.MIN_VALUE }
        val fullySupported = BooleanArray(arity)
        var numValid = s.numValid
        var i = 0
        while (i < numValid) {
            state.checkFiniteWork()
            val row = s.validTuples[i]
            val feasible = if (delta) {
                requireNotNull(s.live)[row ushr 6] and (1L shl (row and 63)) != 0L
            } else {
                (0 until arity).all { col ->
                    state.checkFiniteWork()
                    cellFeasible(state, row, col, state.intDomains[xs[col]])
                }
            }
            if (!feasible) {
                if (!delta) s.rejectRow(row)
                val last = numValid - 1
                if (i != last) {
                    s.validTuples[i] = s.validTuples[last]
                    s.validTuples[last] = row
                }
                numValid = last
            } else {
                for (col in 0 until arity) {
                    state.checkFiniteWork()
                    val d = state.intDomains[xs[col]]
                    val lo = cellLo(row, col)
                    val hiC = cellHi(row, col)
                    if (lo <= d.min && hiC >= d.max) {
                        fullySupported[col] = true
                        continue
                    }
                    var v = maxOf(lo, d.min)
                    val vEnd = minOf(hiC, d.max)
                    while (v <= vEnd) {
                        state.checkFiniteWork()
                        supported[col].add(v)
                        if (v < minSup[col]) minSup[col] = v
                        if (v > maxSup[col]) maxSup[col] = v
                        if (v == vEnd) break
                        v++
                    }
                }
                i++
            }
        }
        // The sparse-set permutation restores every removed row when the prefix size is undone.
        s.numValid = numValid
        if (!delta) s.rebuildLive()
        if (numValid == 0) return false
        val coarse by lazy(LazyThreadSafetyMode.NONE) { collectHoleAndBoundAntecedents(state, xs) }
        for (col in 0 until arity) {
            state.checkFiniteWork()
            if (fullySupported[col]) continue
            val ant = if (hi == null) null else columnReason(state, col) { coarse }
            // numValid > 0 leaves every column at least one supported value.
            if (minSup[col] > state.intDomains[xs[col]].min &&
                !state.tightenIntMin(
                    xs[col],
                    minSup[col],
                    deductionReason(state, col, LOWER, minSup[col], ant) { coarse },
                )
            ) {
                return false
            }
            if (maxSup[col] < state.intDomains[xs[col]].max &&
                !state.tightenIntMax(
                    xs[col],
                    maxSup[col],
                    deductionReason(state, col, UPPER, maxSup[col], ant) { coarse },
                )
            ) {
                return false
            }
            val sup = supported[col]
            // Skipping a column too large to walk is sound: removal runs once it narrows, and leaves are singletons.
            if (state.intDomains[xs[col]].spanOrNull(DEFAULT_DOMAIN_WALK_CAP) != null) {
                val toRemove = LongArrayList()
                state.intDomains[xs[col]].values.forEach { value ->
                    state.checkFiniteWork()
                    if (value !in sup) toRemove.add(value)
                }
                if (!excludeUnsupported(state, col, toRemove.toLongArray(), ant) { coarse }) return false
            }
        }
        return true
    }

    private inline fun excludeUnsupported(
        state: PropagationState,
        col: Int,
        values: LongArray,
        intervalReason: IntArray?,
        crossinline coarse: () -> IntArray?,
    ): Boolean {
        if (values.isEmpty()) return true
        val v = xs[col]
        val d = state.intDomains[v]
        // Repeated coordinates can expose an endpoint after another column has pruned the same variable.
        if (values.size == 1 || values.first() <= d.min || values.last() >= d.max) {
            for (value in values) {
                state.checkFiniteWork()
                val reason = deductionReason(state, col, VALUE, value, intervalReason, coarse)
                if (!state.excludeIntValue(v, value, reason)) return false
            }
            return true
        }
        if (hi != null || state.currentLevel == 0 || !state.undoLogging) {
            return state.excludeIntValues(
                v,
                values,
                deductionReason(state, col, VALUE, values[0], intervalReason, coarse),
            )
        }
        return state.excludeInteriorIntValues(v, values) { value ->
            deductionReason(state, col, VALUE, value, intervalReason, coarse)
        }
    }

    // Without the undo log nothing reads reasons, so every column's current domain stands in.
    private inline fun columnReason(state: PropagationState, col: Int, coarse: () -> IntArray?): IntArray? =
        if (state.currentLevel == 0) {
            null
        } else if (state.undoLogging) {
            state.lazyReason(intArrayOf(col))
        } else {
            coarse()
        }

    private inline fun deductionReason(
        state: PropagationState,
        col: Int,
        kind: Int,
        value: Long,
        intervalReason: IntArray?,
        coarse: () -> IntArray?,
    ): IntArray? = when {
        hi != null -> intervalReason
        state.currentLevel == 0 -> null
        state.undoLogging -> state.lazyReason(intArrayOf(col, kind, (value ushr 32).toInt(), value.toInt()))
        else -> coarse()
    }

    private companion object {
        const val LOWER = 0
        const val UPPER = 1
        const val VALUE = 2

        private fun mergeRanges(ranges: List<LongRange>): List<LongRange> {
            val merged = ArrayList<LongRange>()
            for (r in ranges.sortedBy { it.first }) {
                val last = merged.lastOrNull()
                if (last != null && r.first <= last.last + 1) {
                    merged[merged.size - 1] = last.first..maxOf(last.last, r.last)
                } else {
                    merged.add(r)
                }
            }
            return merged
        }

        private fun covers(ranges: List<LongRange>, range: LongRange): Boolean =
            ranges.any { range.first >= it.first && range.last <= it.last }

        const val MAX_BITSET_SPAN: Long = 1L shl 24

        private fun setSupportRange(state: PropagationState, bits: LongArray, first: Int, last: Int) {
            val firstWord = first ushr 6
            val lastWord = last ushr 6
            val firstMask = -1L shl (first and 63)
            val lastMask = -1L ushr (63 - (last and 63))
            if (firstWord == lastWord) {
                bits[firstWord] = bits[firstWord] or (firstMask and lastMask)
            } else {
                bits[firstWord] = bits[firstWord] or firstMask
                for (word in firstWord + 1 until lastWord) {
                    state.checkFiniteWork()
                    bits[word] = -1L
                }
                bits[lastWord] = bits[lastWord] or lastMask
            }
        }

        private fun domainOverlapsRange(state: PropagationState, d: IntDomain, lo: Long, hi: Long): Boolean {
            val a = maxOf(lo, d.min)
            val b = minOf(hi, d.max)
            if (a > b) return false
            if (d.holeCount == 0L) return true
            var holes = 0L
            d.forEachHoleInRange(a, b) {
                state.checkFiniteWork()
                holes++
            }
            return b - a + 1 > holes
        }
    }
}

// Marks a started table with no STR2 live set yet; the first fire that must sweep replaces it.
private object NoopStarted
