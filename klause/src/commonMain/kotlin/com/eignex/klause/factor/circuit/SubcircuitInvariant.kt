package com.eignex.klause.factor.circuit

import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.solver.values

/** LS implementation for [Circuit]: violation scoring and move proposal for the optional-cycle
 *  constraint. */
internal class SubcircuitInvariant(succ: IntArray, n: Int, computeCost: (LocalSearchState, Int, Long) -> Int) :
    SuccessorCycleInvariant(succ, n, computeCost) {

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        if (state.intPayload[factorId] == 0) return
        for (i in succ.indices) {
            val v = succ[i]
            val cur = state.assignment.intValue(v)
            val d = state.rootDomains[v]
            if (i.toLong() != cur && i.toLong() in d) sink.addChannelingIntSet(state, v, i.toLong())
            val span = d.values.size
            if (span <= MAX_TARGETS) {
                d.values.forEach { target ->
                    if (target != cur) sink.addChannelingIntSet(state, v, target)
                }
            } else {
                if (cur < d.max) sink.addChannelingIntSet(state, v, cur + 1)
                if (cur > d.min) sink.addChannelingIntSet(state, v, cur - 1)
                repeat(MAX_TARGETS) {
                    val target = d.values.valueAt(state.rng.nextInt(span))
                    if (target != cur) sink.addChannelingIntSet(state, v, target)
                }
            }
        }
    }

    override fun proposeStructuredMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        if (n < 2) return
        val nextOf = IntArray(n) { state.assignment.intValue(succ[it]).toInt() }
        val active = BooleanArray(n)
        var activeCount = 0
        for (i in 0 until n) {
            val s = nextOf[i]
            if (s !in 0 until n) return
            if (s != i) {
                active[i] = true
                activeCount++
            }
        }
        val predOf = IntArray(n) { -1 }
        for (i in 0 until n) if (active[i]) predOf[nextOf[i]] = i
        val activeNodes = IntArray(activeCount)
        val excludedNodes = IntArray(n - activeCount)
        var ai = 0
        var ei = 0
        for (i in 0 until n) if (active[i]) activeNodes[ai++] = i else excludedNodes[ei++] = i
        var emitted = 0
        var attempts = 0
        while (emitted < STRUCTURED_MOVE_CAP && attempts < STRUCTURED_MOVE_CAP * MOVE_ATTEMPT_STRIDE) {
            attempts++
            when (state.rng.nextInt(3)) {
                0 -> {
                    if (activeCount < 3) continue
                    val v = activeNodes[state.rng.nextInt(activeCount)]
                    val p = predOf[v]
                    val nv = nextOf[v]
                    if (p < 0) continue
                    val a = activeNodes[state.rng.nextInt(activeCount)]
                    if (a == v || a == p) continue
                    val b = nextOf[a]
                    if (b == v) continue
                    if (nv.toLong() !in state.rootDomains[succ[p]]) continue
                    if (v.toLong() !in state.rootDomains[succ[a]]) continue
                    if (b.toLong() !in state.rootDomains[succ[v]]) continue
                    sink.addCompound(
                        listOf(
                            Move.IntSet(succ[p], nv.toLong()),
                            Move.IntSet(succ[a], v.toLong()),
                            Move.IntSet(succ[v], b.toLong()),
                        ),
                    )
                    emitted++
                }

                1 -> {
                    if (activeCount < 2) continue
                    val v = activeNodes[state.rng.nextInt(activeCount)]
                    val p = predOf[v]
                    val nv = nextOf[v]
                    if (p < 0) continue
                    if (nv.toLong() !in state.rootDomains[succ[p]]) continue
                    if (v.toLong() !in state.rootDomains[succ[v]]) continue
                    sink.addCompound(listOf(Move.IntSet(succ[p], nv.toLong()), Move.IntSet(succ[v], v.toLong())))
                    emitted++
                }

                else -> {
                    if (activeCount < 2 || excludedNodes.isEmpty()) continue
                    val u = excludedNodes[state.rng.nextInt(excludedNodes.size)]
                    val a = activeNodes[state.rng.nextInt(activeCount)]
                    val b = nextOf[a]
                    if (u.toLong() !in state.rootDomains[succ[a]]) continue
                    if (b.toLong() !in state.rootDomains[succ[u]]) continue
                    sink.addCompound(listOf(Move.IntSet(succ[a], u.toLong()), Move.IntSet(succ[u], b.toLong())))
                    emitted++
                }
            }
        }
    }

    override fun proposeExtendedStructuredMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        proposeActiveReversals(state, sink)
    }

    /** 2-opt reversals over the active sub-tour, when the active nodes form a single cycle. Materializes
     *  the active cycle into an order array and delegates to the shared reversal generator. */
    private fun proposeActiveReversals(state: LocalSearchState, sink: MoveSink) {
        if (n < 2) return
        val nextOf = IntArray(n) { state.assignment.intValue(succ[it]).toInt() }
        var firstActive = -1
        var activeCount = 0
        for (i in 0 until n) {
            val s = nextOf[i]
            if (s !in 0 until n) return
            if (s != i) {
                activeCount++
                if (firstActive < 0) firstActive = i
            }
        }
        if (activeCount < 4) return
        val order = IntArray(activeCount)
        val seen = BooleanArray(n)
        var cur = firstActive
        for (k in 0 until activeCount) {
            if (cur < 0 || cur >= n || nextOf[cur] == cur || seen[cur]) return
            seen[cur] = true
            order[k] = cur
            cur = nextOf[cur]
        }
        if (cur == firstActive) proposeReversals(state, order, sink, STRUCTURED_MOVE_CAP, MOVE_ATTEMPT_STRIDE)
    }

    override fun seedFeasible(state: LocalSearchState, factorId: Int): Boolean {
        if (n < 2) return false
        for (i in 0 until n) {
            val target = (i + 1) % n
            if (target.toLong() !in state.rootDomains[succ[i]]) return false
            if (state.assumptions.isFrozenInt(
                    succ[i],
                ) && state.assignment.intValue(succ[i]) != target.toLong()
            ) {
                return false
            }
        }
        for (i in 0 until n) {
            if (!state.assumptions.isFrozenInt(succ[i])) state.assignment.setInt(succ[i], ((i + 1) % n).toLong())
        }
        return true
    }

    private companion object {
        const val MAX_TARGETS: Int = 4
        const val STRUCTURED_MOVE_CAP: Int = 4
        const val MOVE_ATTEMPT_STRIDE: Int = 8
    }
}
