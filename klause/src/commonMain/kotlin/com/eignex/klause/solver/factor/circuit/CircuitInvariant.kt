package com.eignex.klause.solver.factor.circuit

import com.eignex.klause.solver.Move
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.util.IntArrayList

/** LS implementation for [Circuit]: violation scoring and move proposal for the Hamiltonian-cycle
 *  constraint. */
internal class CircuitInvariant(succ: IntArray, n: Int, computeCost: (LocalSearchState, Int, Int) -> Int) :
    SuccessorCycleInvariant(succ, n, computeCost) {

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        if (state.intPayload[factorId] == 0) return
        for (i in succ.indices) {
            val v = succ[i]
            val cur = state.assignment.intValue(v)
            val d = state.problem.intDomains[v]
            val span = d.size
            if (span <= MAX_TARGETS) {
                d.forEach { target ->
                    if (target != cur && target != i) sink.addChannelingIntSet(state, v, target)
                }
            } else {
                if (cur < d.max) sink.addChannelingIntSet(state, v, cur + 1)
                if (cur > d.min) sink.addChannelingIntSet(state, v, cur - 1)
                repeat(MAX_TARGETS) {
                    val target = d.valueAt(state.rng.nextInt(span))
                    if (target != cur && target != i) sink.addChannelingIntSet(state, v, target)
                }
            }
        }
        proposeMergeSwaps(state, sink)
    }

    override fun proposeStructuredMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        if (n < 3) return
        val nextOf = IntArray(n) { state.assignment.intValue(succ[it]) }
        val predOf = IntArray(n) { -1 }
        for (i in 0 until n) {
            val s = nextOf[i]
            if (s in 0 until n) predOf[s] = i else return
        }
        var emitted = 0
        var attempts = 0
        while (emitted < MAX_SWAP_CANDIDATES && attempts < MAX_SWAP_CANDIDATES * STRUCTURED_ATTEMPT_STRIDE) {
            attempts++
            val v = state.rng.nextInt(n)
            val p = predOf[v]
            val nv = nextOf[v]
            if (p < 0) continue
            val a = state.rng.nextInt(n)
            if (a == v || a == p) continue
            val b = nextOf[a]
            if (b == v) continue
            if (nv !in state.problem.intDomains[succ[p]]) continue
            if (v !in state.problem.intDomains[succ[a]]) continue
            if (b !in state.problem.intDomains[succ[v]]) continue
            sink.addCompound(
                listOf(
                    Move.IntSet(succ[p], nv),
                    Move.IntSet(succ[a], v),
                    Move.IntSet(succ[v], b),
                ),
            )
            emitted++
        }
        proposeReversalMoves(state, sink)
    }

    /**
     * 2-opt segment reversals. On the current Hamiltonian tour, reverse a bounded interior segment:
     * this removes two edges and reconnects their endpoints while flipping the direction of the
     * segment between them — the classic routing move that a single successor change cannot reach.
     * Each reversal is emitted as one atomic [Move.Compound] over the reversed segment's successor
     * variables. Feasibility-preserving (reversing a segment of a valid tour yields a valid tour), so
     * it belongs with the structured moves; if the successor array is not a single Hamiltonian cycle
     * (mid-repair) the move is undefined and nothing is emitted.
     */
    private fun proposeReversalMoves(state: LocalSearchState, sink: MoveSink) {
        if (n < 4) return
        val order = IntArray(n)
        val seen = BooleanArray(n)
        var cur = 0
        for (k in 0 until n) {
            if (seen[cur]) return
            seen[cur] = true
            order[k] = cur
            val nx = state.assignment.intValue(succ[cur])
            if (nx < 0 || nx >= n) return
            cur = nx
        }
        if (cur != 0) return
        proposeReversals(state, order, sink, MAX_SWAP_CANDIDATES, STRUCTURED_ATTEMPT_STRIDE)
    }

    override fun seedFeasible(state: LocalSearchState, factorId: Int): Boolean {
        for (i in 0 until n) {
            val target = (i + 1) % n
            if (target !in state.problem.intDomains[succ[i]]) return false
            if (state.assumptions.isFrozenInt(succ[i]) && state.assignment.intValue(succ[i]) != target) return false
        }
        for (i in 0 until n) {
            if (!state.assumptions.isFrozenInt(succ[i])) state.assignment.setInt(succ[i], (i + 1) % n)
        }
        return true
    }

    private fun proposeMergeSwaps(state: LocalSearchState, sink: MoveSink) {
        if (n < 3) return
        val cycleOf = IntArray(n) { -1 }
        var cycleId = 0
        val effective = IntArray(n) { i ->
            val s = state.assignment.intValue(succ[i])
            if (s < 0 || s >= n || s == i) -1 else s
        }
        val posOnPath = IntArray(n) { -1 }
        val pathBuf = IntArrayList()
        for (start in 0 until n) {
            if (cycleOf[start] != -1) continue
            pathBuf.clear()
            var cur = start
            while (cur >= 0 && cycleOf[cur] == -1 && posOnPath[cur] < 0) {
                posOnPath[cur] = pathBuf.size
                pathBuf.add(cur)
                cur = effective[cur]
            }
            if (cur >= 0 && posOnPath[cur] >= 0) {
                val cycleStartIdx = posOnPath[cur]
                for (idx in cycleStartIdx until pathBuf.size) cycleOf[pathBuf[idx]] = cycleId
                cycleId++
            }
            for (k in 0 until pathBuf.size) posOnPath[pathBuf[k]] = -1
        }
        if (cycleId < 2) return
        var swapsAdded = 0
        for (i in 0 until n) {
            if (swapsAdded >= MAX_SWAP_CANDIDATES) break
            if (cycleOf[i] < 0) continue
            for (j in i + 1 until n) {
                if (swapsAdded >= MAX_SWAP_CANDIDATES) break
                if (cycleOf[j] < 0 || cycleOf[j] == cycleOf[i]) continue
                val si = effective[i]
                val sj = effective[j]
                if (si < 0 || sj < 0) continue
                val di = state.problem.intDomains[succ[i]]
                val dj = state.problem.intDomains[succ[j]]
                if (sj !in di.min..di.max || si !in dj.min..dj.max) continue
                sink.addCompound(listOf(Move.IntSet(succ[i], sj), Move.IntSet(succ[j], si)))
                swapsAdded++
            }
        }
    }

    private companion object {
        const val MAX_TARGETS: Int = 4
        const val MAX_SWAP_CANDIDATES: Int = 4
        const val STRUCTURED_ATTEMPT_STRIDE: Int = 6
    }
}
