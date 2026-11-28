package com.eignex.klause.localsearch

import com.eignex.klause.localsearch.Move
import com.eignex.util.arrays.emptyIntArray
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.IntHashSet
import com.eignex.util.collections.LongArrayList

private val EMPTY_INTS = emptyIntArray()

// Directed ejection chains after F. Glover, "Ejection chains, reference structures and alternating path methods
// for traveling salesman problems", Discrete Applied Mathematics 65, 1996,
// https://doi.org/10.1016/0166-218X(94)00037-E.
// Each step repairs the factor the previous step newly regressed, so the coordinated shape follows what actually
// breaks. Touched variables stay pinned, so a chain never undoes itself; the state is reverted exactly and the best
// prefix of at least two parts is emitted as one compound. Returns the number of chains emitted.
internal fun LocalSearchState.proposeRepairChains(
    seedFactor: Int,
    maxDepth: Int,
    firstMoveCap: Int,
    sink: MoveSink,
): Int {
    val propose = repairChainProposals.also {
        it.clear()
        it.setAssumptions(assumptions)
        it.setInvariants(invariants)
        it.setOwners(seeding.ownerInt)
    }
    val firsts = repairChainFirsts.also {
        it.clear()
        it.setAssumptions(assumptions)
        it.setInvariants(invariants)
        it.setOwners(seeding.ownerInt)
    }
    factors[seedFactor].proposeRepairMoves(this, seedFactor, firsts)
    var emitted = sampleChainFirsts(firsts, firstMoveCap, maxDepth, propose, sink)
    // Some cost-1 orbits are closed under violated-factor repairs, so an escape must first perturb a satisfied
    // neighbour. Repair firsts keep their own budget so they are not crowded out.
    firsts.clear()
    neighbourPrimitives(seedFactor, firsts)
    emitted += sampleChainFirsts(firsts, firstMoveCap, maxDepth, propose, sink)
    return emitted
}

internal fun LocalSearchState.sampleChainFirsts(
    firsts: MoveSink,
    cap: Int,
    maxDepth: Int,
    propose: MoveSink,
    sink: MoveSink,
): Int {
    val count = minOf(cap, firsts.size).coerceAtLeast(0)
    if (count == 0) return 0
    var emitted = 0
    // Sparse Fisher-Yates swaps retain the draw order while storing only the sampled prefix.
    // Chain growth consumes the same RNG between first-move draws.
    val positions = IntArray(count)
    val replacements = IntArray(count)
    fun at(index: Int, swaps: Int): Int {
        for (k in swaps - 1 downTo 0) if (positions[k] == index) return replacements[k]
        return index
    }
    for (i in 0 until count) {
        val j = i + rng.nextInt(firsts.size - i)
        val selected = at(j, i)
        positions[i] = j
        replacements[i] = at(i, i)
        val parts = buildRepairChain(firsts.moveAt(selected), maxDepth, propose)
        if (parts != null) {
            sink.addCompound(parts)
            emitted++
        }
    }
    return emitted
}

internal fun LocalSearchState.neighbourPrimitives(fid: Int, sink: MoveSink) {
    val f = problem.factors[fid]
    val seenFactors = IntHashSet()
    for (v in f.intVars) for (nf in projection.intOccurrences[v]) emitFactorPrimitives(fid, nf, seenFactors, sink)
    for (v in f.boolVars) for (nf in projection.boolOccurrences[v]) emitFactorPrimitives(fid, nf, seenFactors, sink)
}

// On successor encodings the min endpoint removes a node from the structure, a jump a neighbour step cannot express.
internal fun LocalSearchState.emitFactorPrimitives(seed: Int, nf: Int, seenFactors: IntHashSet, sink: MoveSink) {
    if (nf == seed || !seenFactors.add(nf)) return
    val nfac = problem.factors[nf]
    for (u in nfac.intVars) {
        val cur = assignment.intValue(u)
        val d = rootDomains[u]
        val up = if (cur < d.max) d.higher(cur) else cur
        val down = if (cur > d.min) d.lower(cur) else cur
        if (up != cur) sink.addChannelingIntSet(this, u, up)
        if (down != cur) sink.addChannelingIntSet(this, u, down)
        if (down > d.min) sink.addChannelingIntSet(this, u, d.min)
        if (up < d.max) sink.addChannelingIntSet(this, u, d.max)
    }
    for (u in nfac.boolVars) sink.addBoolFlip(u)
}

// The best prefix is emitted even above the start cost so the score race arbitrates; keeping only improving walks
// starves the pool where coordinated escapes are needed. Single-part walks are already in the repair pool.
internal fun LocalSearchState.buildRepairChain(first: Move, maxDepth: Int, propose: MoveSink): List<Move>? {
    val startCost = cost
    val oldStep = tabu.step
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
    val baseDegree = repairChainDegrees.also { it.clear() }

    return runRepairChain(
        first, maxDepth, propose, parts, inverses, savedSlots, savedTouched,
        savedCounts, pinnedSlots, baseDegree, startCost, oldStep, oldBest,
        oldBoolConf, oldIntConf,
    )
}

@Suppress("LongParameterList")
private fun LocalSearchState.runRepairChain(
    first: Move,
    maxDepth: Int,
    propose: MoveSink,
    parts: ArrayList<Move>,
    inverses: ArrayList<Move>,
    savedSlots: IntArrayList,
    savedTouched: LongArrayList,
    savedCounts: IntArrayList,
    pinnedSlots: IntHashSet,
    baseDegree: RepairChainDegrees,
    startCost: Long,
    oldStep: Long,
    oldBest: Long,
    oldBoolConf: BooleanArray,
    oldIntConf: BooleanArray,
): List<Move>? {
    fun applyPart(p: Move) {
        val slot = slotOf(p)
        if (pinnedSlots.add(slot)) {
            savedSlots.add(slot)
            savedTouched.add(tabu.lastTouched[slot])
            savedCounts.add(tabu.touchCount[slot])
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
    try {
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
        return if (bestPrefixLen >= 2) parts.subList(0, bestPrefixLen) else null
    } finally {
        for (i in inverses.indices.reversed()) apply(inverses[i])
        tabu.step = oldStep
        for (i in 0 until savedSlots.size) {
            tabu.lastTouched[savedSlots[i]] = savedTouched[i]
            tabu.touchCount[savedSlots[i]] = savedCounts[i]
        }
        oldBoolConf.copyInto(boolConfChange)
        oldIntConf.copyInto(intConfChange)
        bestCostSeen = oldBest
    }
}

// Keeps each factor's first sighting, the chain-start baseline.
internal fun LocalSearchState.recordBaseDegrees(p: Move, base: RepairChainDegrees) {
    when (p) {
        is Move.BoolFlip -> {
            for (fid in projection.boolOccurrences[p.varId]) recordFirstDegree(base, fid)
            recordConeDegrees(intSeeds = EMPTY_INTS, boolSeeds = intArrayOf(p.varId), base)
        }

        is Move.IntSet -> {
            for (fid in projection.intOccurrences[p.varId]) recordFirstDegree(base, fid)
            recordConeDegrees(intSeeds = intArrayOf(p.varId), boolSeeds = EMPTY_INTS, base)
        }

        is Move.RealSet -> for (fid in projection.realOccurrences[p.varId]) recordFirstDegree(base, fid)

        is Move.Compound -> error("chain parts are primitive by construction")
    }
}

internal fun LocalSearchState.recordFirstDegree(base: RepairChainDegrees, fid: Int) {
    base.record(fid, factorDegree[fid])
}

internal fun LocalSearchState.recordConeDegrees(intSeeds: IntArray, boolSeeds: IntArray, base: RepairChainDegrees) {
    val net = invariants ?: return
    for (idx in net.affectedNodes(intSeeds, boolSeeds)) {
        val n = net.node(idx)
        val occ = if (n.outIsBool) projection.boolOccurrences[n.out] else projection.intOccurrences[n.out]
        for (fid in occ) recordFirstDegree(base, fid)
    }
}

// -1 when nothing regressed.
internal fun LocalSearchState.worstRegressedFactor(base: RepairChainDegrees): Int =
    base.worstRegressed(factorDegree, weights.factorWeights)

// Ties are broken uniformly; null ends the chain.
internal fun LocalSearchState.pickChainRepair(target: Int, pinnedSlots: IntHashSet, propose: MoveSink): Move? {
    propose.clear()
    factors[target].proposeRepairMoves(this, target, propose)
    var best: Move? = null
    var bestDelta = Long.MAX_VALUE
    var ties = 0
    outer@ for (i in 0 until propose.size) {
        val m = propose.moveAt(i)
        when (m) {
            is Move.Compound -> {
                for (q in m.parts) if (slotOf(q) in pinnedSlots) continue@outer
            }

            else -> if (slotOf(m) in pinnedSlots) continue@outer
        }
        val d = if (m is Move.BoolFlip && repairChainClauseOnly && invariants?.readsBool(m.varId) != true) {
            boolBreakCount[m.varId].toLong() - boolMakeCount[m.varId]
        } else {
            netDelta(m)
        }
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
    is Move.RealSet -> Move.RealSet(part.varId, assignment.realValue(part.varId))
    is Move.Compound -> error("Compound parts are primitive by construction")
}

internal fun LocalSearchState.slotOf(part: Move): Int = when (part) {
    is Move.BoolFlip -> part.varId
    is Move.IntSet -> problem.numBoolVars + part.varId
    is Move.RealSet -> problem.numBoolVars + problem.numIntVars + part.varId
    is Move.Compound -> error("Compound parts are primitive by construction")
}
