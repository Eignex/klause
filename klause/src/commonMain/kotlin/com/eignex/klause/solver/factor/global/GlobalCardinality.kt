package com.eignex.klause.solver.factor.global

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Move
import com.eignex.klause.solver.factor.OptPresence
import com.eignex.klause.solver.factor.OptionalFactor
import com.eignex.klause.solver.factor.arithmetic.collectHoleAndBoundAntecedents
import com.eignex.klause.solver.factor.compressViolation
import com.eignex.klause.solver.factor.global.internals.reginTarjanScc
import com.eignex.klause.solver.factor.remapLits
import com.eignex.klause.solver.factor.remapVars
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.propagation.PropagationState
import com.eignex.klause.solver.propagation.RevIntArray
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntIntMap

/**
 * Global Cardinality Constraint (GCC). Covers the four MiniZinc variants in one factor:
 *
 *  - `global_cardinality(xs, cover, counts)` — `counts(k) = #{i : xs(i) = cover(k)}`. Use
 *    [countVars] (`size = cover.size`) and [closed] = `false`.
 *  - `global_cardinality_low_up(xs, cover, lo, up)` — `lo(k) ≤ #{i : xs(i) = cover(k)} ≤ up(k)`.
 *    Use [countLow] / [countHigh] (constant arrays) and [countVars] = `null`.
 *  - `_closed` variants additionally require every `xs(i) ∈ cover` — i.e. no value outside
 *    the cover set may appear. Pass [closed] = `true`.
 *
 * Exactly one of ([countVars], [countLow]+[countHigh]) is non-null — the constructor
 * validates.
 *
 * Propagation: count-bound tightening (definite/possible matchers per cover value) plus
 * Régin-style max-flow GAC. The flow has lower bounds on `cover_k → sink` (matching the
 * cover lo/hi or current `countVars(k)` domain), is reduced to standard max-flow via the
 * super-source/super-sink trick, solved by Edmonds-Karp, then the residual graph is
 * SCC'd. Any `xᵢ → cover_k` edge with zero flow whose endpoints sit in different SCCs
 * cannot extend to a feasible solution and is pruned from `dom(xᵢ)`.
 */
class GlobalCardinality(
    /** Variable ids the constraint ranges over. */
    val xs: IntArray,
    /** Values whose occurrence counts are bounded. */
    val cover: IntArray,
    val countVars: IntArray? = null,
    val countLow: IntArray? = null,
    val countHigh: IntArray? = null,
    val closed: Boolean = false,
    /** Per-xs presence literals; empty for the non-opt fast path. Absent positions
     *  contribute nothing to any cover-value count and don't trip the closed check. */
    override val presents: IntArray = EmptyIntArray,
) : Factor,
    OptionalFactor {

    init {
        require(xs.isNotEmpty()) { "gcc: empty xs" }
        require(cover.isNotEmpty()) { "gcc: empty cover" }
        if (countVars != null) {
            require(countVars.size == cover.size) { "gcc: countVars size mismatch" }
            require(countLow == null && countHigh == null) { "gcc: pass either countVars OR countLow+countHigh" }
        } else {
            require(countLow != null && countHigh != null) { "gcc: missing countLow/countHigh" }
            require(countLow.size == cover.size && countHigh.size == cover.size) { "gcc: lo/hi size mismatch" }
        }
        require(presents.isEmpty() || presents.size == xs.size) {
            "gcc: presents must be empty or match xs arity"
        }
    }

    override fun remap(boolMap: IntArray, intMap: IntArray): Factor = GlobalCardinality(
        xs.remapVars(intMap),
        cover,
        countVars?.remapVars(intMap),
        countLow,
        countHigh,
        closed,
        presents.remapLits(boolMap),
    )

    // xs is a set (counts are per cover value, order-independent) so xs/presents pairs are sorted by
    // var id; cover triples are sorted by value. Encodes every distinguishing field — fine enough
    // that two non-equivalent GCCs never collide (a coarser key would let a symmetry swap through).
    override fun structuralKey(): String {
        val xsPart = xs.indices.sortedBy { xs[it] }.joinToString(",") { i ->
            if (presents.isEmpty()) "${xs[i]}" else "${xs[i]}@${presents[i]}"
        }
        val coverPart = cover.indices.sortedBy { cover[it] }.joinToString(",") { i ->
            if (countVars != null) {
                "${cover[i]}=v${countVars[i]}"
            } else {
                "${cover[i]}=${requireNotNull(countLow)[i]}_${requireNotNull(countHigh)[i]}"
            }
        }
        return "gcc:$closed:$xsPart:$coverPart"
    }

    /** Relabel the cover values (#374). Only the constant-count form is value-relabelable: with count
     *  *variables* the counts live in a second value universe that one map can't relabel, so that form
     *  blocks value symmetry (returns `null`). A value transposition is a bijection, so the relabeled
     *  cover stays distinct. */
    override fun remapValues(valueMap: (Int) -> Int): Factor? {
        if (countVars != null) return null
        return GlobalCardinality(
            xs,
            IntArray(cover.size) { valueMap(cover[it]) },
            null,
            countLow,
            countHigh,
            closed,
            presents,
        )
    }

    override val boolVars: IntArray = OptPresence.presenceVarIds(presents)
    override val intVars: IntArray = run {
        val cv = countVars
        if (cv != null) xs + cv else xs
    }

    // Cover value → its index. IntIntMap keeps the per-probe lookup unboxed; indices are ≥ 0 so
    // -1 is a safe absent sentinel (a value not in the cover).
    private val coverIndexByValue: IntIntMap =
        IntIntMap.build(cover, IntArray(cover.size) { it }, absent = -1)

    /** Per-cover-index count under the current assignment. */
    private class State(val counts: IntArray)

    override fun initialize(state: LocalSearchState, factorId: Int) {
        val counts = IntArray(cover.size)
        for (i in xs.indices) {
            if (!present(state, i)) continue
            val value = state.assignment.intValue(xs[i])
            val idx = coverIndexByValue[value]
            if (idx < 0) continue // out-of-cover; counts unaffected
            counts[idx]++
        }
        state.refPayload[factorId] = State(counts)
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean {
        val s = state.refPayload[factorId] as State
        return rawDegree(state, s.counts, ovVar = -1, ovVal = 0) > 0L
    }

    /** Graded violation: per-cover count error (`|count − countVar|`, or the bound shortfall
     *  `max(0, lo − count) + max(0, count − hi)`) summed over the cover, plus — for the closed
     *  variant — one unit per present `xs[i]` whose value falls outside the cover. Compressed so
     *  a wide cover can't dominate the global cost. */
    override fun violationDegree(state: LocalSearchState, factorId: Int): Int {
        val s = state.refPayload[factorId] as State
        return compressViolation(rawDegree(state, s.counts, ovVar = -1, ovVal = 0), state.violationSoftCap)
    }

    /** Per-cover count-error term over [simCounts]; `(ovVar, ovVal)` overrides one count-var's
     *  value when reading the target. */
    private fun countsDegree(state: LocalSearchState, simCounts: IntArray, ovVar: Int, ovVal: Int): Long {
        var deg = 0L
        for (k in cover.indices) {
            if (countVars != null) {
                val expected = if (countVars[k] == ovVar) ovVal else state.assignment.intValue(countVars[k])
                val d = expected.toLong() - simCounts[k]
                deg += if (d < 0) -d else d
            } else {
                val cnt = simCounts[k]
                val lo = requireNotNull(countLow)[k]
                val hi = requireNotNull(countHigh)[k]
                if (cnt < lo) {
                    deg += (lo - cnt).toLong()
                } else if (cnt > hi) {
                    deg += (cnt - hi).toLong()
                }
            }
        }
        return deg
    }

    /** Closed-variant term: present `xs[i]` whose value is outside the cover. `(ovVar, ovVal)`
     *  overrides one xs value; `flipVar >= 0` inverts the presence of positions controlled by
     *  that presence-var (used to evaluate a candidate bool flip). */
    private fun closedDegree(state: LocalSearchState, ovVar: Int, ovVal: Int, flipVar: Int = -1): Long {
        if (!closed) return 0L
        var deg = 0L
        for (i in xs.indices) {
            val controlled = flipVar >= 0 && presents.isNotEmpty() && Lit.variable(presents[i]) == flipVar
            val p = if (controlled) !present(state, i) else present(state, i)
            if (!p) continue
            val v = if (xs[i] == ovVar) ovVal else state.assignment.intValue(xs[i])
            if (!coverIndexByValue.contains(v)) deg++
        }
        return deg
    }

    private fun rawDegree(state: LocalSearchState, simCounts: IntArray, ovVar: Int, ovVal: Int): Long =
        countsDegree(state, simCounts, ovVar, ovVal) + closedDegree(state, ovVar, ovVal)

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Int): Int {
        val s = state.refPayload[factorId] as State
        val sim = s.counts.copyOf()
        var occurrencesInXs = 0
        for (i in xs.indices) if (xs[i] == intVar && present(state, i)) occurrencesInXs++
        if (occurrencesInXs > 0) {
            val old = state.assignment.intValue(intVar)
            val oldIdx = coverIndexByValue[old]
            if (oldIdx >= 0) sim[oldIdx] -= occurrencesInXs
            val newIdx = coverIndexByValue[newValue]
            if (newIdx >= 0) sim[newIdx] += occurrencesInXs
        }
        val after = rawDegree(state, sim, ovVar = intVar, ovVal = newValue)
        // The pre-move degree is the factor's current violation degree, already maintained in
        // factorDegree — reuse it instead of re-scanning the cover for `before`.
        return compressViolation(after, state.violationSoftCap) - state.factorDegree[factorId]
    }

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Int): Int {
        val s = state.refPayload[factorId] as State
        val cur = state.assignment.intValue(intVar)
        if (cur == oldValue) return 0
        // The pre-move degree is the factor's current violation degree, already maintained in
        // factorDegree (still pre-move here — the engine reconciles after apply*), so the prior
        // counts need not be reconstructed and re-scanned.
        val beforeDeg = state.factorDegree[factorId]
        var occurrencesInXs = 0
        for (i in xs.indices) if (xs[i] == intVar && present(state, i)) occurrencesInXs++
        if (occurrencesInXs > 0) {
            val oldIdx = coverIndexByValue[oldValue]
            if (oldIdx >= 0) s.counts[oldIdx] -= occurrencesInXs
            val curIdx = coverIndexByValue[cur]
            if (curIdx >= 0) s.counts[curIdx] += occurrencesInXs
        }
        val afterDeg = rawDegree(state, s.counts, ovVar = -1, ovVal = 0)
        return compressViolation(afterDeg, state.violationSoftCap) - beforeDeg
    }

    override fun deltaIfBoolFlipped(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        if (presents.isEmpty()) return 0
        val s = state.refPayload[factorId] as State
        val sim = s.counts.copyOf()
        for (i in presents.indices) {
            if (Lit.variable(presents[i]) != boolVar) continue
            val wasP = present(state, i)
            val coverIdx = coverIndexByValue[state.assignment.intValue(xs[i])]
            if (coverIdx < 0) continue
            sim[coverIdx] += if (wasP) -1 else +1
        }
        // Counts term uses the simulated counts; closed term re-evaluates with the flip applied.
        val after = countsDegree(state, sim, ovVar = -1, ovVal = 0) +
            closedDegree(state, ovVar = -1, ovVal = 0, flipVar = boolVar)
        // Pre-move degree is the maintained current violation degree — reuse it for `before`.
        return compressViolation(after, state.violationSoftCap) - state.factorDegree[factorId]
    }

    override fun applyBoolFlip(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        if (presents.isEmpty()) return 0
        val s = state.refPayload[factorId] as State
        // Flip is already applied to the assignment; the pre-flip degree is the maintained current
        // violation degree (the engine reconciles factorDegree only after apply*), so it need not
        // be reconstructed from the inverted presence.
        val beforeDeg = state.factorDegree[factorId]
        for (i in presents.indices) {
            if (Lit.variable(presents[i]) != boolVar) continue
            val nowP = present(state, i)
            val coverIdx = coverIndexByValue[state.assignment.intValue(xs[i])]
            if (coverIdx < 0) continue
            s.counts[coverIdx] += if (nowP) +1 else -1
        }
        val afterDeg = rawDegree(state, s.counts, ovVar = -1, ovVal = 0)
        return compressViolation(afterDeg, state.violationSoftCap) - beforeDeg
    }

    /** Vars currently pinned (singleton) to [value], among [scope]. */
    private fun pinnedTo(state: PropagationState, scope: IntArray, value: Int): IntArrayList {
        val out = IntArrayList()
        for (x in scope) {
            val d = state.intDomains[x]
            if (d.min == d.max && d.min == value) out.add(x)
        }
        return out
    }

    /** Clause-form literals for every pinned presence: the conclusion rests on which xs are
     *  in and which are out, so each pinned presence literal joins the premise set as its
     *  currently-false form. Empty for the non-opt fast path. */
    private fun presencePremiseLits(state: PropagationState): IntArray {
        if (presents.isEmpty()) return EmptyIntArray
        val out = IntArrayList()
        for (i in presents.indices) {
            when {
                definitelyPresent(i, state) -> out.add(Lit.negate(presents[i]))
                definitelyAbsent(i, state) -> out.add(presents[i])
            }
        }
        return out.toIntArray()
    }

    private fun withPresencePremises(state: PropagationState, base: IntArray?): IntArray? {
        val pres = presencePremiseLits(state)
        if (pres.isEmpty()) return base
        if (base == null) return pres
        val out = IntArray(base.size + pres.size)
        base.copyInto(out)
        pres.copyInto(out, base.size)
        return out
    }

    /** Hole-aware conflict reason, sharpened to the pigeonhole subset captured by [propagate];
     *  falls back to all vars when no subset was recorded. Presence pins join the premises:
     *  without them a conflict derived among the present xs reads as if it held for every
     *  assignment, and the learned clause prunes feasible solutions that simply mark some
     *  of those xs absent. */
    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? = withPresencePremises(
        state,
        collectHoleAndBoundAntecedents(state, (state.refPayload[factorId] as? PropCache)?.conflictVars ?: intVars),
    )

    /** Per-[PropagationState] propagation scratch (so it is never shared across worker threads).
     *  [cachedDoms] holds each [intVars] entry's domain ref at the last successful propagate, for the
     *  non-opt unchanged-domains fast path; [conflictVars] records the sharpened pigeonhole/flow var
     *  subset for [conflictReason]. [flow] is a reusable max-flow builder, reset and refilled every
     *  fire so the Régin-GAC pass reuses one graph instead of allocating a fresh [FlowBuilder] per
     *  call. Not snapshotted: all three are per-fire scratch (the refs only ever *miss* — never
     *  falsely skip — after a restore, the flow is rebuilt every fire, the subset is advisory), so
     *  the slot drifts across snapshot/restore. */
    private class PropCache(val cachedDoms: Array<IntDomain?>) {
        var conflictVars: IntArray? = null
        val flow = FlowBuilder()

        /** Reversible per-`xs` cover-index the var's flow used at the last successful propagate
         *  (`-1` = none / routed to the "other" arc). Replayed to warm-start [flow] each fire and
         *  rolled back with the search, so after a backtrack the seed reflects that level's flow.
         *  Non-opt only (indexed by `xs` position). Created lazily once [PropagationState] is known. */
        var flowAssign: RevIntArray? = null
    }

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        val cache = (state.refPayload[factorId] as? PropCache) ?: run {
            val fresh = PropCache(arrayOfNulls(intVars.size))
            state.refPayload[factorId] = fresh
            fresh
        }
        // Fast path — only when every taker is unconditionally present (no presence literals). Then
        // the analysis reads exactly the [intVars] int domains, so if none changed since the last
        // successful propagate the prior fixpoint still holds. (With presence bools the result also
        // depends on bool state, which these refs don't capture, so the opt case always re-runs.)
        if (presents.isEmpty() && intVars.isNotEmpty()) {
            var changed = false
            for (i in intVars.indices) {
                if (cache.cachedDoms[i] !== state.intDomains[intVars[i]]) {
                    changed = true
                    break
                }
            }
            if (!changed && cache.cachedDoms[0] != null) return true
        }
        cache.conflictVars = null // stale-guard; set at each pigeonhole failure point below.
        // ---- 1. Count tightening + closure --------------------------------------------
        // Opt-aware: filter to definitely-present xs for the flow analysis. Definitely-
        // absent xs contribute nothing; unpinned-presence xs may still go absent, so we
        // can't require them to take a cover value — skip them too for soundness.
        val origIdx: IntArray = if (presents.isEmpty()) {
            IntArray(xs.size) { it }
        } else {
            val acc = IntArrayList()
            for (i in xs.indices) if (definitelyPresent(i, state)) acc.add(i)
            IntArray(acc.size) { acc[it] }
        }
        val effectiveXs: IntArray = if (presents.isEmpty()) {
            xs
        } else {
            IntArray(origIdx.size) { xs[origIdx[it]] }
        }
        val n = effectiveXs.size
        val m = cover.size
        // Maybe-present xs: presence undecided. They contribute no definite count and take
        // no pruning, but they remain potential takers of any cover value, so every
        // upper-bound argument must include them.
        val maybeXs: IntArray = if (presents.isEmpty()) {
            EmptyIntArray
        } else {
            val acc = IntArrayList()
            for (i in xs.indices) {
                if (!definitelyPresent(i, state) &&
                    !definitelyAbsent(i, state)
                ) {
                    acc.add(xs[i])
                }
            }
            acc.toIntArray()
        }
        // LCG antecedents: the union of every involved var's int trail plus the presence
        // pins. Count vars join the set — every flow-shaped conclusion rests on their
        // current bounds (the demand side), and composing emits nothing for a count var
        // still at its root domain. Same coarse approach as AllDifferent — analyzer
        // minimization shrinks redundant pieces.
        val gccAntecedents = withPresencePremises(
            state,
            state.composeIntVarAtomAntecedents(effectiveXs + maybeXs + (countVars ?: EmptyIntArray)),
        )
        if (closed) {
            for (x in effectiveXs) {
                val d = state.intDomains[x]
                val toRemove = IntArrayList()
                d.forEach { if (!coverIndexByValue.contains(it)) toRemove.add(it) }
                for (k in 0 until toRemove.size) {
                    if (!state.excludeIntValue(
                            x,
                            toRemove[k],
                            gccAntecedents,
                        )
                    ) {
                        return false
                    }
                }
            }
        }
        val definite = IntArray(m)
        val possible = IntArray(m)
        for (k in cover.indices) {
            val target = cover[k]
            for (x in effectiveXs) {
                val d = state.intDomains[x]
                if (d.min == d.max && d.min == target) definite[k]++
                if (target in d) possible[k]++
            }
            // A maybe-present x can still become present and take the value: it counts
            // toward the possible takers (never toward the definite ones).
            for (x in maybeXs) {
                if (target in state.intDomains[x]) possible[k]++
            }
            if (countVars != null) {
                // More vars pinned to cover[k] than countVars[k]'s max: the pinned vars plus
                // the count var alone prove it (a pigeonhole over cover[k]).
                if (!state.tightenIntMin(countVars[k], definite[k], gccAntecedents)) {
                    cache.conflictVars = pinnedTo(
                        state,
                        effectiveXs,
                        target,
                    ).also { it.add(countVars[k]) }.toIntArray()
                    return false
                }
                if (!state.tightenIntMax(countVars[k], possible[k], gccAntecedents)) return false
            } else {
                if (requireNotNull(countLow)[k] > possible[k]) return false
                // More vars pinned to cover[k] than countHigh[k] allows: cite only those pins.
                if (requireNotNull(countHigh)[k] < definite[k]) {
                    cache.conflictVars = pinnedTo(state, effectiveXs, target).toIntArray()
                    return false
                }
            }
        }

        // Resolve effective [lo_k, hi_k] for cover values (post count-tightening).
        val lo = IntArray(m)
        val hi = IntArray(m)
        for (k in 0 until m) {
            if (countVars != null) {
                val cd = state.intDomains[countVars[k]]
                lo[k] = cd.min
                hi[k] = cd.max
            } else {
                lo[k] = requireNotNull(countLow)[k]
                hi[k] = requireNotNull(countHigh)[k]
            }
        }

        // The flow model below assumes the present set is exact: a maybe-present x is
        // potential supply for any lower bound and potential demand on any upper bound,
        // and neither role fits a fixed-node flow soundly. Defer GAC until every
        // presence is decided; the count tightening above already ran.
        if (maybeXs.isNotEmpty()) return true

        // Detect whether any xᵢ has an out-of-cover value reachable (drives "other" arc).
        val hasOtherVar = BooleanArray(n)
        val anyOther = !closed && run {
            var any = false
            for (i in 0 until n) {
                val d = state.intDomains[effectiveXs[i]]
                var found = false
                d.forEach { if (!found && !coverIndexByValue.contains(it)) found = true }
                hasOtherVar[i] = found
                if (found) any = true
            }
            any
        }

        // ---- 2. Régin GAC via max-flow -----------------------------------------------
        // Node layout: 0 = source, 1 = sink, 2..2+n-1 = var nodes, 2+n..2+n+m-1 = cover nodes,
        // (optional) 2+n+m = other node. Plus superSource, superSink appended for lower-bound reduction.
        val source = 0
        val sink = 1
        val varNode = IntArray(n) { 2 + it }
        val covNode = IntArray(m) { 2 + n + it }
        val otherNode = if (anyOther) 2 + n + m else -1
        val baseNodes = 2 + n + m + (if (anyOther) 1 else 0)
        val superSource = baseNodes
        val superSink = baseNodes + 1
        val totalNodes = baseNodes + 2

        // Edge list: parallel arrays (to, cap, rev). `headForward[i]` = first forward
        // edge index in `edgeTo` for node i; we just keep flat lists per node. Reuse the
        // per-session builder (reset to an empty graph) instead of allocating a fresh one each fire.
        val flow = cache.flow.also { it.reset(totalNodes) }

        // source → x_i with bounds [1, 1]: reduces to cap 0, excess[source] -= 1, excess[x_i] += 1.
        // Encoded by accumulating into `excess` and adding zero-cap edge.
        val excess = IntArray(totalNodes)
        // Track xᵢ → cover_k edge indices so we can read out flow + check residual later.
        val xToCovEdgeIdx = Array(n) { IntArray(m) { -1 } }
        val xToOtherEdgeIdx = IntArray(n) { -1 }

        for (i in 0 until n) {
            // source → x_i lower bound 1, upper 1.
            excess[source] -= 1
            excess[varNode[i]] += 1
            // No residual capacity on this edge (l == h).
            flow.addEdge(source, varNode[i], 0)
        }

        for (i in 0 until n) {
            val d = state.intDomains[effectiveXs[i]]
            for (k in 0 until m) {
                if (cover[k] in d) {
                    val eIdx = flow.addEdge(varNode[i], covNode[k], 1) // [0, 1]
                    xToCovEdgeIdx[i][k] = eIdx
                }
            }
            if (otherNode != -1 && hasOtherVar[i]) {
                xToOtherEdgeIdx[i] = flow.addEdge(varNode[i], otherNode, 1) // [0, 1]
            }
        }

        for (k in 0 until m) {
            if (lo[k] > hi[k]) return false
            // cover_k → sink with bounds [lo_k, hi_k]
            excess[covNode[k]] -= lo[k]
            excess[sink] += lo[k]
            flow.addEdge(covNode[k], sink, hi[k] - lo[k])
        }
        if (otherNode != -1) {
            // other → sink with bounds [0, n]; no excess shift.
            flow.addEdge(otherNode, sink, n)
        }
        // sink → source back-edge to convert s-t feasibility into a circulation: bounds [n, n].
        excess[sink] -= n
        excess[source] += n
        flow.addEdge(sink, source, 0)

        // superSource / superSink excess-balancing edges.
        var requiredSSFlow = 0
        for (v in 0 until baseNodes) {
            when {
                excess[v] > 0 -> {
                    flow.addEdge(superSource, v, excess[v])
                    requiredSSFlow += excess[v]
                }

                excess[v] < 0 -> flow.addEdge(v, superSink, -excess[v])
            }
        }

        // Warm-start (non-opt): replay the previous fire's var→cover assignment as valid
        // augmentations, seeding the flow toward the prior solution. Each push is
        // capacity-respecting, so the maxFlow below still reaches the true maximum regardless of
        // how stale the replay is; the assignment is reversible, so after a backtrack it reflects
        // that level's flow.
        if (presents.isEmpty()) {
            val assign = cache.flowAssign ?: RevIntArray(state, n, -1).also { cache.flowAssign = it }
            for (i in 0 until n) {
                val k = assign[i]
                if (k in 0 until m && xToCovEdgeIdx[i][k] >= 0) {
                    flow.augmentThroughEdge(superSource, superSink, varNode[i], covNode[k])
                }
            }
        }

        // Edmonds-Karp max-flow from superSource to superSink. If the saturation of all ss-out edges
        // is less than requiredSSFlow → infeasible.
        val obtained = flow.maxFlow(superSource, superSink)
        if (obtained < requiredSSFlow) {
            // Flow-deficiency conflict: the min cut's source side (nodes still reachable from
            // superSource in the residual) carries the unroutable demand. Cite only the vars
            // (and any count vars) on that side — a generalized-Hall subset — rather than all.
            // Vars off the source side route fine; their domains play no part in the deficit.
            val reach = flow.residualReachable(superSource)
            val resp = IntArrayList()
            for (i in 0 until n) if (reach[varNode[i]]) resp.add(effectiveXs[i])
            // Every count var joins the citation: the deficit is supply (the vars above)
            // against demand, and the demand is the count vars' search-derived bounds —
            // the demand-side cover nodes are generally NOT residual-reachable, so
            // filtering by the cut would drop exactly the premises that matter. A count
            // var still at its root domain contributes no literal.
            if (countVars != null) for (k in 0 until m) resp.add(countVars[k])
            if (resp.size > 0) cache.conflictVars = resp.toIntArray()
            return false
        }

        // Record the var→cover assignment of this (maximum) flow as next fire's warm-start seed.
        cache.flowAssign?.let { assign ->
            for (i in 0 until n) {
                var chosen = -1
                for (k in 0 until m) {
                    val e = xToCovEdgeIdx[i][k]
                    if (e >= 0 && flow.flowOf(e) > 0) {
                        chosen = k
                        break
                    }
                }
                assign[i] = chosen
            }
        }

        // ---- 3. SCC on residual graph (excluding superSource, superSink) -----------------------------
        val sccId = IntArray(baseNodes) { -1 }
        flow.computeSccResidual(baseNodes, sccId)

        // ---- 4. Prune zero-flow xᵢ→cover_k edges across SCC boundaries ---------------
        for (i in 0 until n) {
            for (k in 0 until m) {
                val eIdx = xToCovEdgeIdx[i][k]
                if (eIdx < 0) continue
                if (flow.flowOf(eIdx) > 0) continue // active in current flow; alive.
                if (sccId[varNode[i]] == sccId[covNode[k]]) continue // may carry flow elsewhere.
                if (!state.excludeIntValue(effectiveXs[i], cover[k], gccAntecedents)) return false
            }
            // If the var→other arc exists but cannot carry flow in any feasible flow,
            // every non-cover value in dom(xᵢ) is dead — prune them all.
            val oIdx = xToOtherEdgeIdx[i]
            if (oIdx >= 0 && flow.flowOf(oIdx) == 0 && sccId[varNode[i]] != sccId[otherNode]) {
                val d = state.intDomains[effectiveXs[i]]
                val toRemove = IntArrayList()
                d.forEach { if (!coverIndexByValue.contains(it)) toRemove.add(it) }
                for (k in 0 until toRemove.size) {
                    if (!state.excludeIntValue(
                            effectiveXs[i],
                            toRemove[k],
                            gccAntecedents,
                        )
                    ) {
                        return false
                    }
                }
            }
        }
        // Record post-propagate int-domain refs so a later no-op fire can short-circuit (non-opt only;
        // the opt case never reads them — presence bools aren't captured here).
        if (presents.isEmpty()) for (i in intVars.indices) cache.cachedDoms[i] = state.intDomains[intVars[i]]
        return true
    }

    /**
     * Minimal Edmonds-Karp max-flow over an integer-capacity graph stored as parallel
     * arrays. Edges come in forward/reverse pairs; even indices are forward edges (with
     * the original capacity), odd indices are residual reverses (initially zero). Flow
     * pushed on edge `e` shows up as `originalCap - cap[e]` for forward edges.
     */
    private class FlowBuilder {
        // Reusable across propagate calls: [reset] grows the adjacency array on demand, clears the
        // live `[0, numNodes)` lists, and empties the parallel edge arrays — so a fire refills the
        // same backing instead of allocating a fresh graph (the dominant per-fire GCC allocation).
        // Behaviour-identical: every fire rebuilds the full graph before [maxFlow] reads it.
        private var adj: Array<IntArrayList> = emptyArray()
        private val edgeTo = IntArrayList()
        private val cap = IntArrayList()
        private val originalCap = IntArrayList()

        var numNodes: Int = 0
            private set

        // Reusable BFS scratch for [maxFlow] / [augmentThroughEdge], grown in [reset].
        private var parentEdge = IntArray(0)
        private var bfsQueue = IntArray(0)

        /** Clear to an empty graph over [nodes] nodes, reusing existing backing where possible. */
        fun reset(nodes: Int) {
            if (adj.size < nodes) {
                val old = adj
                adj = Array(nodes) { if (it < old.size) old[it] else IntArrayList() }
            }
            if (parentEdge.size < nodes) {
                parentEdge = IntArray(nodes)
                bfsQueue = IntArray(nodes)
            }
            for (i in 0 until nodes) adj[i].clear()
            edgeTo.clear()
            cap.clear()
            originalCap.clear()
            numNodes = nodes
        }

        /** Push one unit (or the path bottleneck) along a residual path
         *  `source → … → viaU → viaV → … → sink`, *forced* through the `viaU→viaV` edge. Returns
         *  true iff such a residual path exists (and was pushed). Used to replay a cached
         *  var→cover assignment as a valid augmentation, warm-starting [maxFlow] toward the previous
         *  solution — any push is capacity-respecting, so the subsequent [maxFlow] still reaches the
         *  true maximum regardless of how good the replay was. */
        fun augmentThroughEdge(source: Int, sink: Int, viaU: Int, viaV: Int): Boolean {
            var viaEdge = -1
            val nu = adj[viaU]
            for (k in 0 until nu.size) {
                val e = nu[k]
                if (edgeTo[e] == viaV && cap[e] > 0) {
                    viaEdge = e
                    break
                }
            }
            if (viaEdge < 0) return false
            val parent = parentEdge
            parent.fill(-1, 0, numNodes)
            parent[source] = -2
            val q = bfsQueue
            var h = 0
            var t = 0
            q[t++] = source
            var found = false
            while (h < t && !found) {
                val u = q[h++]
                if (u == viaU) {
                    // Force the path through the chosen viaU→viaV edge only.
                    if (parent[viaV] == -1) {
                        parent[viaV] = viaEdge
                        if (viaV == sink) found = true else q[t++] = viaV
                    }
                } else {
                    val neigh = adj[u]
                    for (k in 0 until neigh.size) {
                        val e = neigh[k]
                        val v = edgeTo[e]
                        if (parent[v] != -1 || cap[e] <= 0) continue
                        parent[v] = e
                        if (v == sink) {
                            found = true
                            break
                        }
                        q[t++] = v
                    }
                }
            }
            if (!found) return false
            var bottleneck = Int.MAX_VALUE
            var cur = sink
            while (cur != source) {
                val e = parent[cur]
                if (cap[e] < bottleneck) bottleneck = cap[e]
                cur = edgeTo[e xor 1]
            }
            cur = sink
            while (cur != source) {
                val e = parent[cur]
                cap[e] = cap[e] - bottleneck
                cap[e xor 1] = cap[e xor 1] + bottleneck
                cur = edgeTo[e xor 1]
            }
            return true
        }

        fun addEdge(u: Int, v: Int, c: Int): Int {
            val eIdx = edgeTo.size
            edgeTo.add(v)
            cap.add(c)
            originalCap.add(c)
            adj[u].add(eIdx)
            edgeTo.add(u)
            cap.add(0)
            originalCap.add(0)
            adj[v].add(eIdx + 1)
            return eIdx
        }

        fun flowOf(eIdx: Int): Int = originalCap[eIdx] - cap[eIdx]

        /** Nodes reachable from [source] over residual arcs (positive remaining capacity) —
         *  the source side of the min cut after a (failed) max-flow. */
        fun residualReachable(source: Int): BooleanArray {
            val seen = BooleanArray(numNodes)
            val queue = IntArray(numNodes)
            var qHead = 0
            var qTail = 0
            seen[source] = true
            queue[qTail++] = source
            while (qHead < qTail) {
                val u = queue[qHead++]
                val neigh = adj[u]
                for (i in 0 until neigh.size) {
                    val eIdx = neigh[i]
                    if (cap[eIdx] <= 0) continue
                    val v = edgeTo[eIdx]
                    if (!seen[v]) {
                        seen[v] = true
                        queue[qTail++] = v
                    }
                }
            }
            return seen
        }

        fun maxFlow(source: Int, sink: Int): Int {
            // Start from any flow already present (e.g. a warm-start seed): the value is the flow
            // leaving `source`, so the return is the absolute maximum, not the delta this call adds.
            var total = 0
            val srcAdj = adj[source]
            for (k in 0 until srcAdj.size) total += flowOf(srcAdj[k])
            val parentEdge = this.parentEdge
            val queue = bfsQueue
            while (true) {
                parentEdge.fill(-1, 0, numNodes)
                parentEdge[source] = -2
                var qHead = 0
                var qTail = 0
                queue[qTail++] = source
                var found = false
                while (qHead < qTail && !found) {
                    val u = queue[qHead++]
                    val neigh = adj[u]
                    for (k in 0 until neigh.size) {
                        val eIdx = neigh[k]
                        val v = edgeTo[eIdx]
                        if (parentEdge[v] != -1 || cap[eIdx] <= 0) continue
                        parentEdge[v] = eIdx
                        if (v == sink) {
                            found = true
                            break
                        }
                        queue[qTail++] = v
                    }
                }
                if (!found) break
                // Find bottleneck along the path.
                var bottleneck = Int.MAX_VALUE
                var cur = sink
                while (cur != source) {
                    val eIdx = parentEdge[cur]
                    if (cap[eIdx] < bottleneck) bottleneck = cap[eIdx]
                    cur = edgeTo[eIdx xor 1]
                }
                cur = sink
                while (cur != source) {
                    val eIdx = parentEdge[cur]
                    cap[eIdx] = cap[eIdx] - bottleneck
                    cap[eIdx xor 1] = cap[eIdx xor 1] + bottleneck
                    cur = edgeTo[eIdx xor 1]
                }
                total += bottleneck
            }
            return total
        }

        /** Tarjan SCC over the residual subgraph induced by nodes `[0, limit)`. Edges with
         *  positive remaining capacity (forward residuals + already-used reverses) define the
         *  directed graph. Materialises that residual adjacency (a static snapshot once the flow
         *  is fixed) and delegates to the shared [reginTarjanScc] (#99). SCC ids are only ever
         *  compared for equality, so component numbering is irrelevant. */
        fun computeSccResidual(limit: Int, sccId: IntArray) {
            val nodeAdj = Array(limit) { IntArrayList() }
            for (v in 0 until limit) {
                val neigh = adj[v]
                for (k in 0 until neigh.size) {
                    val eIdx = neigh[k]
                    val w = edgeTo[eIdx]
                    if (w < limit && cap[eIdx] > 0) nodeAdj[v].add(w)
                }
            }
            val res = reginTarjanScc(nodeAdj, limit)
            for (i in 0 until limit) sccId[i] = res[i]
        }
    }

    /** Repair moves: for each cover index `k` whose count violates the constraint, push it
     *  toward the target band. With [countVars], also snap each `countVars[k]` to the current
     *  count. For [closed]=true, drag any out-of-cover `xs[i]` into the cover. */
    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        if (!isViolated(state, factorId)) return
        val s = state.refPayload[factorId] as State
        // 1. Snap countVars to current count when they disagree.
        if (countVars != null) {
            for (k in cover.indices) {
                val cv = countVars[k]
                val cur = state.assignment.intValue(cv)
                if (cur != s.counts[k] && s.counts[k] in state.problem.intDomains[cv]) {
                    sink.addChannelingIntSet(state, cv, s.counts[k])
                }
            }
        }
        // 2. For each violating cover index, propose moves to push count toward target.
        for (k in cover.indices) {
            val coverVal = cover[k]
            val cnt = s.counts[k]
            val target: Int
            val needIncrease: Boolean
            if (countVars != null) {
                target = state.assignment.intValue(countVars[k])
                if (cnt == target) continue
                needIncrease = cnt < target
            } else {
                if (cnt in requireNotNull(countLow)[k]..requireNotNull(countHigh)[k]) continue
                needIncrease = cnt < countLow[k]
                target = if (needIncrease) countLow[k] else countHigh[k]
            }
            if (needIncrease) {
                // Pick xs[i] currently NOT at coverVal whose domain contains it; switch.
                for (i in xs.indices) {
                    if (!present(state, i)) continue
                    val cur = state.assignment.intValue(xs[i])
                    if (cur != coverVal && coverVal in state.problem.intDomains[xs[i]]) {
                        sink.addChannelingIntSet(state, xs[i], coverVal)
                    }
                }
            } else {
                // Pick xs[i] currently AT coverVal; move it to a non-coverVal in its domain.
                for (i in xs.indices) {
                    if (!present(state, i)) continue
                    val cur = state.assignment.intValue(xs[i])
                    if (cur != coverVal) continue
                    val d = state.problem.intDomains[xs[i]]
                    var pick: Int? = null
                    d.forEach { if (pick == null && it != coverVal) pick = it }
                    if (pick != null) sink.addChannelingIntSet(state, xs[i], pick)
                }
            }
        }
        // 3. Closed mode: drag out-of-cover xs[i] into the cover.
        if (closed) {
            for (i in xs.indices) {
                if (!present(state, i)) continue
                val cur = state.assignment.intValue(xs[i])
                if (coverIndexByValue.contains(cur)) continue
                val d = state.problem.intDomains[xs[i]]
                for (cv in cover) {
                    if (cv in d && cv != cur) {
                        sink.addChannelingIntSet(state, xs[i], cv)
                        break
                    }
                }
            }
        }
    }

    override val providesImplicitNeighbourhood: Boolean get() = true

    /** Feasibility-preserving neighbourhood: swap the values of two present `xs` positions. Every
     *  cover value's count is unchanged (one position loses it, another gains it), so all bound /
     *  count-var obligations and the closed check are preserved while the assignment is perturbed —
     *  which can clear a clash in a coupled constraint sharing one of those variables. */
    override fun proposeStructuredMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        if (xs.size < 2) return
        var emitted = 0
        var attempts = 0
        while (emitted < STRUCTURED_SWAP_CAP && attempts < STRUCTURED_SWAP_CAP * SWAP_ATTEMPT_STRIDE) {
            attempts++
            val ai = state.rng.nextInt(xs.size)
            val bi = state.rng.nextInt(xs.size)
            val a = xs[ai]
            val b = xs[bi]
            if (a == b) continue
            if (!present(state, ai) || !present(state, bi)) continue
            val va = state.assignment.intValue(a)
            val vb = state.assignment.intValue(b)
            if (va == vb) continue
            if (vb !in state.problem.intDomains[a] || va !in state.problem.intDomains[b]) continue
            sink.addCompound(listOf(Move.IntSet(a, vb), Move.IntSet(b, va)))
            emitted++
        }
    }

    /** Feasible init: assign present `xs` to cover values meeting every lower bound without
     *  exceeding an upper bound (bound form) or any in-domain cover value (count-var form, then
     *  the count vars are set to the realised counts). Frozen vars keep their value and are
     *  counted first. Returns false — leaving the random assignment — if no feasible assignment is
     *  reachable greedily. */
    override fun seedFeasible(state: LocalSearchState, factorId: Int): Boolean {
        val counts = IntArray(cover.size)
        val free = IntArrayList()
        for (i in xs.indices) {
            if (!present(state, i)) continue
            if (state.assumptions.isFrozenInt(xs[i])) {
                val idx = coverIndexByValue[state.assignment.intValue(xs[i])]
                if (idx >= 0) {
                    counts[idx]++
                } else if (closed) {
                    return false
                }
            } else {
                free.add(i)
            }
        }
        val assigned = BooleanArray(xs.size)
        if (countVars == null) {
            val lo = requireNotNull(countLow)
            val hi = requireNotNull(countHigh)
            for (k in cover.indices) {
                while (counts[k] < lo[k]) {
                    val pos = takeFreeFor(state, free, assigned, cover[k]) ?: return false
                    state.assignment.setInt(xs[pos], cover[k])
                    counts[k]++
                }
            }
            for (fi in 0 until free.size) {
                val pos = free[fi]
                if (assigned[pos]) continue
                val pick = pickUnderHigh(state, xs[pos], counts, hi) ?: return false
                state.assignment.setInt(xs[pos], pick)
                val idx = coverIndexByValue[pick]
                if (idx >= 0) counts[idx]++
            }
        } else {
            for (fi in 0 until free.size) {
                val pos = free[fi]
                val pick = firstCoverInDomain(state, xs[pos])
                    ?: if (closed) return false else firstInDomain(state, xs[pos])
                state.assignment.setInt(xs[pos], pick)
                val idx = coverIndexByValue[pick]
                if (idx >= 0) counts[idx]++
            }
            for (k in cover.indices) {
                val cv = countVars[k]
                if (state.assumptions.isFrozenInt(cv)) {
                    if (state.assignment.intValue(cv) != counts[k]) return false
                } else {
                    if (counts[k] !in state.problem.intDomains[cv]) return false
                    state.assignment.setInt(cv, counts[k])
                }
            }
        }
        return true
    }

    /** First unassigned free position whose domain contains [value]; marks it assigned. */
    private fun takeFreeFor(state: LocalSearchState, free: IntArrayList, assigned: BooleanArray, value: Int): Int? {
        for (fi in 0 until free.size) {
            val pos = free[fi]
            if (assigned[pos]) continue
            if (value in state.problem.intDomains[xs[pos]]) {
                assigned[pos] = true
                return pos
            }
        }
        return null
    }

    /** A cover value still under its high whose domain contains it, for filling a free position. */
    private fun pickUnderHigh(state: LocalSearchState, varId: Int, counts: IntArray, hi: IntArray): Int? {
        for (k in cover.indices) {
            if (counts[k] < hi[k] && cover[k] in state.problem.intDomains[varId]) return cover[k]
        }
        return if (closed) null else firstInDomain(state, varId)
    }

    private fun firstCoverInDomain(state: LocalSearchState, varId: Int): Int? {
        val d = state.problem.intDomains[varId]
        for (cv in cover) if (cv in d) return cv
        return null
    }

    private fun firstInDomain(state: LocalSearchState, varId: Int): Int = state.problem.intDomains[varId].min

    private companion object {
        /** Cap on `xs` value-swap compounds offered per [proposeStructuredMoves] call. */
        const val STRUCTURED_SWAP_CAP: Int = 4

        /** Rejection-sampling attempts per requested swap before giving up. */
        const val SWAP_ATTEMPT_STRIDE: Int = 6
    }
}
