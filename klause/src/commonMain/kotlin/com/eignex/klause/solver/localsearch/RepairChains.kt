package com.eignex.klause.solver.localsearch

import com.eignex.klause.solver.Move
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.LongArrayList
import com.eignex.klause.util.MutableIntIntMap

/** Shared empty seed array for one-sided [recordConeDegrees] calls. */
private val EMPTY_INTS = IntArray(0)

/**
 * **Directed ejection-chain proposal**: grow a coordinated multi-variable move by chaining repairs
 * through the break structure. First moves are drawn from two pools with separate budgets:
 * [seedFactor]'s own repair proposals (repair-first) and primitives on neighbouring factors'
 * variables (ejection-first — see `neighbourPrimitives`). Each accepted step is applied (with
 * invariant propagation), the factor it *newly regressed* is identified, and that factor's
 * best-scoring repair — restricted to variables the chain hasn't already touched — becomes the next
 * step. The walk stops at the first strict cost improvement, at [maxDepth] steps, or when no
 * eligible repair exists; the state is then reverted exactly and the walk's best (minimum-cost)
 * ≥2-part prefix is emitted into [sink] as one atomic [Move.Compound].
 *
 * Unlike the stall-swap plateau buster, which hard-codes one coordinated shape (a same-domain value
 * exchange), the chain *derives* the coordinated shape from which constraints actually break — what
 * successor/path encodings need, where repairing a successor link requires re-linking predecessor,
 * position and chain tail in one move. Chains are **directed** (each step repairs the damage of the
 * previous one) and only ever compete **on score**.
 *
 * Variables touched once are pinned for the rest of the chain, so a chain never undoes itself and
 * always terminates. Cost is bounded by [maxDepth] × (apply + propose + probe); callers gate this
 * behind the stall detector.
 *
 * Returns the number of chains emitted into [sink].
 */
internal fun LocalSearchState.proposeRepairChains(
    seedFactor: Int,
    maxDepth: Int,
    firstMoveCap: Int,
    sink: MoveSink,
): Int {
    val propose = MoveSink(assumptions).also { it.setInvariants(invariants) }
    factors[seedFactor].proposeRepairMoves(this, seedFactor, propose)
    var emitted = sampleChainFirsts(propose.list, firstMoveCap, maxDepth, propose, sink)
    // Ejection firsts: primitives on the variables of factors neighbouring the seed (sharing a
    // variable). Some cost-1 orbits are closed under violated-factor repairs — every escape must
    // first perturb a satisfied neighbour (eject), then repair the cascade. Repair firsts keep their
    // own budget above so they aren't crowded out.
    propose.clear()
    neighbourPrimitives(seedFactor, propose)
    emitted += sampleChainFirsts(propose.list, firstMoveCap, maxDepth, propose, sink)
    return emitted
}

/** Sample up to [cap] distinct first moves from [firsts] (uniform, without replacement)
 *  and emit each one's grown chain into [sink]; returns the number emitted. */
internal fun LocalSearchState.sampleChainFirsts(
    firsts: List<Move>,
    cap: Int,
    maxDepth: Int,
    propose: MoveSink,
    sink: MoveSink,
): Int {
    if (firsts.isEmpty()) return 0
    var emitted = 0
    val order = IntArray(firsts.size) { it }
    for (i in 0 until minOf(cap, firsts.size)) {
        val j = i + rng.nextInt(firsts.size - i)
        val tmp = order[i]
        order[i] = order[j]
        order[j] = tmp
        val parts = buildRepairChain(firsts[order[i]], maxDepth, propose)
        if (parts != null) {
            sink.addCompound(parts)
            emitted++
        }
    }
    return emitted
}

/** Emit ±1 int steps and bool flips on every variable of every factor adjacent to [fid]
 *  (sharing a variable) — the ejection-step vocabulary for chain firsts. */
internal fun LocalSearchState.neighbourPrimitives(fid: Int, sink: MoveSink) {
    val f = factors[fid]
    val seenFactors = IntHashSet()
    for (v in f.intVars) for (nf in problem.intOccurrences[v]) emitFactorPrimitives(fid, nf, seenFactors, sink)
    for (v in f.boolVars) for (nf in problem.boolOccurrences[v]) emitFactorPrimitives(fid, nf, seenFactors, sink)
}

/** [neighbourPrimitives] helper: primitives for one adjacent factor, deduplicated. Ints get ±1
 *  steps *and* the domain endpoints: on successor/path encodings the min endpoint is the semantic
 *  "remove from the structure" eject (`next(i)` → 0), letting a chain dismantle a parasitic
 *  successor fragment backwards — ±1 alone cannot express that jump. */
internal fun LocalSearchState.emitFactorPrimitives(seed: Int, nf: Int, seenFactors: IntHashSet, sink: MoveSink) {
    if (nf == seed || !seenFactors.add(nf)) return
    val nfac = factors[nf]
    for (u in nfac.intVars) {
        val cur = assignment.intValue(u)
        val d = problem.intDomains[u]
        if (cur < d.max) sink.addChannelingIntSet(this, u, cur + 1)
        if (cur > d.min) sink.addChannelingIntSet(this, u, cur - 1)
        if (cur - 1 > d.min) sink.addChannelingIntSet(this, u, d.min)
        if (cur + 1 < d.max) sink.addChannelingIntSet(this, u, d.max)
    }
    for (u in nfac.boolVars) sink.addBoolFlip(u)
}

/** Grow one chain from [first] (see [proposeRepairChains]), apply-evaluate-revert style. Returns the
 *  flattened primitive parts of the walk's minimum-cost prefix with ≥ 2 parts, else null (single-part
 *  walks are already in the normal repair pool). The prefix is emitted even when it ends above the
 *  start cost, letting the score race arbitrate; pre-filtering to improving-only walks would starve
 *  the pool on the trajectories that need coordinated escapes. The state is restored exactly
 *  (assignment, cost, step, lastTouched / touchCount on touched slots, conf-change flags, best-cost
 *  watermark). */
internal fun LocalSearchState.buildRepairChain(first: Move, maxDepth: Int, propose: MoveSink): List<Move>? {
    val startCost = cost
    val oldStep = step
    val oldBest = bestCostSeen
    val oldBoolConf = boolConfChange.copyOf()
    val oldIntConf = intConfChange.copyOf()
    val parts = ArrayList<Move>(maxDepth + 1)
    val inverses = ArrayList<Move>(maxDepth + 1)
    val savedSlots = IntArrayList(maxDepth + 1)
    val savedTouched = LongArrayList(maxDepth + 1)
    val savedCounts = IntArrayList(maxDepth + 1)
    val pinnedSlots = IntHashSet()
    // Degree of every potentially-affected factor at its first sighting — the chain-start
    // baseline that "newly regressed" is measured against.
    val baseDegree = MutableIntIntMap()

    fun applyPart(p: Move) {
        val slot = slotOf(p)
        if (pinnedSlots.add(slot)) {
            savedSlots.add(slot)
            savedTouched.add(lastTouched[slot])
            savedCounts.add(touchCount[slot])
        }
        recordBaseDegrees(p, baseDegree)
        inverses.add(inverseOf(p))
        apply(p)
        parts.add(p)
    }

    // Best (minimum-cost, ties to shorter) ≥2-part prefix seen during the walk — the
    // candidate actually emitted, so a walk that peaks early and then degrades still
    // contributes its good prefix.
    var bestPrefixLen = 0
    var bestPrefixCost = Long.MAX_VALUE
    var next: Move? = first
    var depth = 0
    while (next != null) {
        when (val m = next) {
            is Move.Compound -> for (q in m.parts) applyPart(q)
            else -> applyPart(m)
        }
        depth++
        if (parts.size >= 2 && cost < bestPrefixCost) {
            bestPrefixCost = cost
            bestPrefixLen = parts.size
        }
        if (cost < startCost || depth >= maxDepth) break
        val target = worstRegressedFactor(baseDegree)
        next = if (target < 0) null else pickChainRepair(target, pinnedSlots, propose)
    }
    val keep = bestPrefixLen >= 2

    for (i in inverses.indices.reversed()) apply(inverses[i])
    step = oldStep
    for (i in 0 until savedSlots.size) {
        lastTouched[savedSlots[i]] = savedTouched[i]
        touchCount[savedSlots[i]] = savedCounts[i]
    }
    for (i in oldBoolConf.indices) boolConfChange[i] = oldBoolConf[i]
    for (i in oldIntConf.indices) intConfChange[i] = oldIntConf[i]
    bestCostSeen = oldBest

    return if (keep) parts.subList(0, bestPrefixLen) else null
}

/** Record into [base] the current degree of every factor [p] could affect — factors over
 *  [p]'s own variable plus factors over any defined variable in its invariant cone —
 *  keeping the *first* sighting (the chain-start baseline). */
internal fun LocalSearchState.recordBaseDegrees(p: Move, base: MutableIntIntMap) {
    when (p) {
        is Move.BoolFlip -> {
            for (fid in problem.boolOccurrences[p.varId]) recordFirstDegree(base, fid)
            recordConeDegrees(intSeeds = EMPTY_INTS, boolSeeds = intArrayOf(p.varId), base)
        }

        is Move.IntSet -> {
            for (fid in problem.intOccurrences[p.varId]) recordFirstDegree(base, fid)
            recordConeDegrees(intSeeds = intArrayOf(p.varId), boolSeeds = EMPTY_INTS, base)
        }

        is Move.Compound -> error("chain parts are primitive by construction")
    }
}

/** Record [LocalSearchState.factorDegree] for [fid] on its first sighting only (mirrors `getOrPut`). */
internal fun LocalSearchState.recordFirstDegree(base: MutableIntIntMap, fid: Int) {
    if (!base.containsKey(fid)) base.put(fid, factorDegree[fid])
}

/** [recordBaseDegrees] helper: factors over the invariant cone's output vars. */
internal fun LocalSearchState.recordConeDegrees(intSeeds: IntArray, boolSeeds: IntArray, base: MutableIntIntMap) {
    val net = invariants ?: return
    for (idx in net.affectedNodes(intSeeds, boolSeeds)) {
        val n = net.node(idx)
        val occ = if (n.outIsBool) problem.boolOccurrences[n.out] else problem.intOccurrences[n.out]
        for (fid in occ) recordFirstDegree(base, fid)
    }
}

/** The factor with the largest weighted degree increase over its chain-start baseline,
 *  or -1 when nothing regressed (the chain caused no new damage). */
internal fun LocalSearchState.worstRegressedFactor(base: MutableIntIntMap): Int {
    val w = factorWeights
    var worst = -1
    var worstScore = 0.0
    base.forEach { fid, deg0 ->
        val inc = factorDegree[fid] - deg0
        if (inc > 0) {
            val s = w[fid] * inc
            if (s > worstScore) {
                worstScore = s
                worst = fid
            }
        }
    }
    return worst
}

/** Best repair proposal of [target] that avoids every pinned slot, by immediate
 *  [LocalSearchState.netDelta] probe (ties broken uniformly). Null when [target] proposes nothing
 *  eligible — the chain ends. */
internal fun LocalSearchState.pickChainRepair(target: Int, pinnedSlots: IntHashSet, propose: MoveSink): Move? {
    propose.clear()
    factors[target].proposeRepairMoves(this, target, propose)
    var best: Move? = null
    var bestDelta = Long.MAX_VALUE
    var ties = 0
    outer@ for (m in propose.list) {
        when (m) {
            is Move.Compound -> {
                for (q in m.parts) if (slotOf(q) in pinnedSlots) continue@outer
            }

            else -> if (slotOf(m) in pinnedSlots) continue@outer
        }
        val d = netDelta(m)
        if (d < bestDelta) {
            best = m
            bestDelta = d
            ties = 1
        } else if (d == bestDelta) {
            ties++
            if (rng.nextInt(ties) == 0) best = m
        }
    }
    return best
}

internal fun LocalSearchState.inverseOf(part: Move): Move = when (part) {
    is Move.BoolFlip -> part
    is Move.IntSet -> Move.IntSet(part.varId, assignment.intValue(part.varId))
    is Move.Compound -> error("Compound parts are primitive by construction")
}

internal fun LocalSearchState.slotOf(part: Move): Int = when (part) {
    is Move.BoolFlip -> part.varId
    is Move.IntSet -> problem.numBoolVars + part.varId
    is Move.Compound -> error("Compound parts are primitive by construction")
}
