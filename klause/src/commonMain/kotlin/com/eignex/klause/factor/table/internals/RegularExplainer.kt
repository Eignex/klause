package com.eignex.klause.factor.table.internals

import com.eignex.klause.ir.Lit
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.exclusionLiteral
import com.eignex.klause.propagation.inDomainAt
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet

/**
 * Reasons for [com.eignex.klause.factor.table.Regular] deductions as cuts of the layered automaton graph over the
 * domains at a past undo-log position. Layer `i` holds the states after `i` symbols; an edge `q -s-> q'` from
 * layer `i` is live while `s` is in `seq(i)`'s domain. A symbol leaves `seq(i)` when each of its edges starts at
 * a state the start cannot reach or ends at one that cannot reach acceptance. Unreachability is explained by the
 * state's edges on that side: an edge whose symbol had left its domain cites that one literal, else the state at
 * its far end is unreachable in turn. Each state is explained once per reason.
 */
internal class RegularExplainer(
    private val seq: IntArray,
    private val numStates: Int,
    private val alphabetSize: Int,
    private val transitions: LongArray,
    private val q0: Int,
    private val accepting: IntArray,
) {
    private val n = seq.size

    // Per target state, its incoming edges packed as `source * (alphabetSize + 1) + symbol`.
    private val incoming: Array<IntArray> by lazy {
        val lists = Array(numStates + 1) { IntArrayList() }
        for (p in 1..numStates) {
            for (s in 1..alphabetSize) {
                val q = next(p, s)
                if (q != 0) lists[q].add(p * (alphabetSize + 1) + s)
            }
        }
        Array(numStates + 1) { lists[it].toIntArray() }
    }

    private fun next(q: Int, s: Int): Int {
        val t = transitions[(q - 1) * alphabetSize + (s - 1)].toInt()
        return if (t in 1..numStates) t else 0
    }

    /** Reason for the symbols position [pos] lost as of [atTrail]. */
    fun prune(state: PropagationState, pos: Int, atTrail: Int): IntArray {
        val cut = Cut(state, atTrail)
        val v = seq[pos]
        for (s in 1..alphabetSize) {
            if (!state.inDomainAt(v, s.toLong(), atTrail)) continue
            var live = false
            for (q in 1..numStates) {
                val t = next(q, s)
                if (t != 0 && cut.fwd[pos][q] && cut.bwd[pos + 1][t]) {
                    live = true
                    break
                }
            }
            if (live) continue
            for (q in 1..numStates) {
                val t = next(q, s)
                if (t == 0) continue
                if (!cut.fwd[pos][q]) cut.unreachable(pos, q) else cut.dead(pos + 1, t)
            }
        }
        return cut.literals()
    }

    /** Reason for no accepted word remaining as of [atTrail]: no accepting state is reachable. */
    fun conflict(state: PropagationState, atTrail: Int): IntArray {
        val cut = Cut(state, atTrail)
        for (q in accepting) if (q in 1..numStates) cut.unreachable(n, q)
        return cut.literals()
    }

    private inner class Cut(private val state: PropagationState, private val atTrail: Int) {
        val fwd = Array(n + 1) { BooleanArray(numStates + 1) }
        val bwd = Array(n + 1) { BooleanArray(numStates + 1) }
        private val explainedFwd = Array(n + 1) { BooleanArray(numStates + 1) }
        private val explainedBwd = Array(n + 1) { BooleanArray(numStates + 1) }
        private val seen = IntHashSet()
        private val out = IntArrayList()

        init {
            markReachable()
            markCanAccept()
        }

        // The two sweeps are separate methods so each compiles apart: in the initializer every loop entered on-stack
        // recompiled the whole of it.
        private fun markReachable() {
            if (q0 in 1..numStates) fwd[0][q0] = true
            for (i in 0 until n) {
                for (s in 1..alphabetSize) {
                    if (!state.inDomainAt(seq[i], s.toLong(), atTrail)) continue
                    for (q in 1..numStates) {
                        if (fwd[i][q]) next(q, s).let { if (it != 0) fwd[i + 1][it] = true }
                    }
                }
            }
        }

        private fun markCanAccept() {
            for (q in accepting) if (q in 1..numStates) bwd[n][q] = true
            for (i in n - 1 downTo 0) {
                for (s in 1..alphabetSize) {
                    if (!state.inDomainAt(seq[i], s.toLong(), atTrail)) continue
                    for (q in 1..numStates) {
                        val t = next(q, s)
                        if (t != 0 && bwd[i + 1][t]) bwd[i][q] = true
                    }
                }
            }
        }

        /** Explain why [q] at [layer] is unreachable from the start: each incoming edge is cut. */
        fun unreachable(layer: Int, q: Int) {
            val stack = IntArrayList()
            push(stack, explainedFwd, layer, q)
            while (stack.size > 0) {
                val packed = stack.last()
                stack.truncateTo(stack.size - 1)
                val i = packed / (numStates + 1)
                val t = packed % (numStates + 1)
                if (i == 0) continue // only the start state begins reachable
                for (edge in incoming[t]) {
                    val p = edge / (alphabetSize + 1)
                    val s = edge % (alphabetSize + 1)
                    if (!fwd[i - 1][p]) {
                        push(stack, explainedFwd, i - 1, p)
                    } else {
                        cite(i - 1, s)
                    }
                }
            }
        }

        /** Explain why [q] at [layer] cannot reach acceptance: each outgoing edge is cut. */
        fun dead(layer: Int, q: Int) {
            val stack = IntArrayList()
            push(stack, explainedBwd, layer, q)
            while (stack.size > 0) {
                val packed = stack.last()
                stack.truncateTo(stack.size - 1)
                val i = packed / (numStates + 1)
                val p = packed % (numStates + 1)
                if (i == n) continue // a non-accepting state at the end accepts nothing
                for (s in 1..alphabetSize) {
                    val t = next(p, s)
                    if (t == 0) continue
                    if (!bwd[i + 1][t]) push(stack, explainedBwd, i + 1, t) else cite(i, s)
                }
            }
        }

        private fun push(stack: IntArrayList, explained: Array<BooleanArray>, layer: Int, q: Int) {
            if (explained[layer][q]) return
            explained[layer][q] = true
            stack.add(layer * (numStates + 1) + q)
        }

        // The edge's far end is reachable on its side, so its symbol had left position [pos]'s domain.
        private fun cite(pos: Int, s: Int) {
            val lit = state.exclusionLiteral(seq[pos], s.toLong(), atTrail)
            if (lit != Lit.NONE && seen.add(lit)) out.add(lit)
        }

        fun literals(): IntArray = out.toIntArray()
    }
}
