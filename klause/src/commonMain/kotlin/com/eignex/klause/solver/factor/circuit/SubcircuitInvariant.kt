package com.eignex.klause.solver.factor.circuit

import com.eignex.klause.solver.Invariant
import com.eignex.klause.solver.Move
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink

/** LS contract for [Subcircuit]: violation scoring and move proposal for the optional-cycle
 *  constraint. */
interface SubcircuitInvariant : Invariant {

    /** Successor variable id per node. */
    val succ: IntArray

    /** Number of nodes. */
    val n: Int

    /** Cost function: 0 iff the included nodes form a single cycle (or the subcircuit is empty). */
    fun computeCost(state: LocalSearchState, replaceAt: Int, replaceWith: Int): Int

    override val providesImplicitNeighbourhood: Boolean get() = true

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        if (state.intPayload[factorId] == 0) return
        for (i in succ.indices) {
            val v = succ[i]
            val cur = state.assignment.intValue(v)
            val d = state.problem.intDomains[v]
            if (i != cur && i in d) sink.addChannelingIntSet(state, v, i)
            val span = d.size
            if (span <= MAX_TARGETS) {
                d.forEach { target ->
                    if (target != cur) sink.addChannelingIntSet(state, v, target)
                }
            } else {
                if (cur < d.max) sink.addChannelingIntSet(state, v, cur + 1)
                if (cur > d.min) sink.addChannelingIntSet(state, v, cur - 1)
                repeat(MAX_TARGETS) {
                    val target = d.valueAt(state.rng.nextInt(span))
                    if (target != cur) sink.addChannelingIntSet(state, v, target)
                }
            }
        }
    }

    override fun proposeStructuredMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        if (n < 2) return
        val nextOf = IntArray(n) { state.assignment.intValue(succ[it]) }
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
                    if (nv !in state.problem.intDomains[succ[p]]) continue
                    if (v !in state.problem.intDomains[succ[a]]) continue
                    if (b !in state.problem.intDomains[succ[v]]) continue
                    sink.addCompound(
                        listOf(Move.IntSet(succ[p], nv), Move.IntSet(succ[a], v), Move.IntSet(succ[v], b)),
                    )
                    emitted++
                }

                1 -> {
                    if (activeCount < 2) continue
                    val v = activeNodes[state.rng.nextInt(activeCount)]
                    val p = predOf[v]
                    val nv = nextOf[v]
                    if (p < 0) continue
                    if (nv !in state.problem.intDomains[succ[p]]) continue
                    if (v !in state.problem.intDomains[succ[v]]) continue
                    sink.addCompound(listOf(Move.IntSet(succ[p], nv), Move.IntSet(succ[v], v)))
                    emitted++
                }

                else -> {
                    if (activeCount < 2 || excludedNodes.isEmpty()) continue
                    val u = excludedNodes[state.rng.nextInt(excludedNodes.size)]
                    val a = activeNodes[state.rng.nextInt(activeCount)]
                    val b = nextOf[a]
                    if (u !in state.problem.intDomains[succ[a]]) continue
                    if (b !in state.problem.intDomains[succ[u]]) continue
                    sink.addCompound(listOf(Move.IntSet(succ[a], u), Move.IntSet(succ[u], b)))
                    emitted++
                }
            }
        }
    }

    override fun seedFeasible(state: LocalSearchState, factorId: Int): Boolean {
        if (n < 2) return false
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

    private companion object {
        const val MAX_TARGETS: Int = 4
        const val STRUCTURED_MOVE_CAP: Int = 4
        const val MOVE_ATTEMPT_STRIDE: Int = 8
    }
}
