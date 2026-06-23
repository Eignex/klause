package com.eignex.klause.solver.factor.global

import com.eignex.klause.solver.Propagator
import com.eignex.klause.solver.factor.arithmetic.internals.collectLinearTightenAntecedents
import com.eignex.klause.solver.propagation.IntEvent
import com.eignex.klause.solver.propagation.PropagationState

/**
 * CP propagation logic for `sort` — bound-consistency via the Mehlhorn–Thiel algorithm
 * ("Faster Algorithms for Bound-Consistency of the Sortedness and the Alldifferent Constraint",
 * CP'00). The relation is `ys = sorted(xs)`: `ys` is non-decreasing and shares `xs`'s multiset.
 *
 * The filter (a) normalizes `ys` to a non-decreasing bound chain, (b) builds two perfect
 * matchings `f` / `f'` between sorted positions and the `xs` (smallest-upper-bound and
 * largest-lower-bound greedy matchings) to tighten each `ys` bound, then (c) condenses the
 * `xy`-intersection graph into strongly connected components and tightens each `xs` bound to the
 * range its component's `ys` can take. Steps (a)/(b) alone subsume the previous endpoint-only
 * reasoning; the SCC step (c) is what lets a middle `xs` learn bounds from its sorted position.
 */
internal class SortPropagator(
    val boolVars: IntArray,
    val intVars: IntArray,
    private val xs: IntArray,
    private val ys: IntArray,
) : Propagator {

    private val n = xs.size

    /**
     * Advisor subscription (#623): the sort propagator reads only each variable's `min`/`max` and
     * never inspects interior holes, so it subscribes to [IntEvent.LB_RAISED] / [IntEvent.UB_LOWERED]
     * per variable and skips interior `VALUE_REMOVED` wakes.
     */
    override val initialIntEventWatches: IntArray = run {
        val distinct = intVars.toHashSet()
        val out = IntArray(distinct.size * 2)
        var w = 0
        for (v in distinct) {
            out[w++] = IntEvent.pack(v, IntEvent.LB_RAISED)
            out[w++] = IntEvent.pack(v, IntEvent.UB_LOWERED)
        }
        out
    }

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        collectLinearTightenAntecedents(state, intVars, excludeIdx = -1, extraLit = 0)

    // Scratch state, allocated once and reused across fires (single-threaded propagation).
    private val f = IntArray(n)
    private val fPrime = IntArray(n)
    private val xyGraph = Array(n) { IntArray(n) }
    private val sccSequences = Array(n) { IntArray(n) }
    private val dfsNodes = IntArray(n)
    private val sccNumbers = IntArray(n)
    private val tmpArray = IntArray(n)
    private val pq = MinHeap(n)
    private val s1 = IntStack(n)
    private val s2 = Stack2(n)
    private val recup = IntArray(3)
    private val recup2 = IntArray(3)
    private var currentScc = 0

    private fun xlb(i: Int) = state.intDomains[xs[i]].min
    private fun xub(i: Int) = state.intDomains[xs[i]].max
    private fun ylb(i: Int) = state.intDomains[ys[i]].min
    private fun yub(i: Int) = state.intDomains[ys[i]].max

    // The active state, set per propagate() so the bound accessors above stay terse.
    private lateinit var state: PropagationState

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        this.state = state
        // A single coarse-but-sound antecedent for every narrowing this pass emits: the whole pass
        // is a deterministic consequence of the pre-pass domains, so citing their tightened bounds
        // justifies each deduction. Reason minimization is a separate concern (issue #7).
        val ant = state.composeIntVarAtomAntecedents(intVars)

        for (i in 0 until n) {
            xyGraph[i].fill(-1)
            sccSequences[i].fill(-1)
        }

        // (a) Normalize ys into a non-decreasing bound chain.
        for (i in 1 until n) {
            if (!state.tightenIntMin(ys[i], ylb(i - 1), ant)) return false
        }
        for (i in n - 2 downTo 0) {
            if (!state.tightenIntMax(ys[i], yub(i + 1), ant)) return false
        }

        // (b1) Greedy matching f: assign each ys[j] (ascending) the available xs of smallest UB.
        pq.clear()
        for (i in 0 until n) {
            if (intersect(0, i)) pq.add(i, xub(i))
        }
        f[0] = popF(0) ?: return false
        for (j in 1 until n) {
            for (i in 0 until n) {
                if (xlb(i) > yub(j - 1) && xlb(i) <= yub(j)) pq.add(i, xub(i))
            }
            f[j] = popF(j) ?: return false
        }
        for (i in 0 until n) {
            if (!state.tightenIntMax(ys[i], xub(f[i]), ant)) return false
        }

        // (b2) Greedy matching f': assign each ys[j] (descending) the available xs of largest LB.
        pq.clear()
        for (i in 0 until n) {
            if (intersect(n - 1, i)) pq.add(i, -xlb(i))
        }
        fPrime[n - 1] = popFPrime(n - 1) ?: return false
        for (j in n - 2 downTo 0) {
            for (i in 0 until n) {
                if (xub(i) < ylb(j + 1) && xub(i) >= ylb(j)) pq.add(i, -xlb(i))
            }
            fPrime[j] = popFPrime(j) ?: return false
        }
        for (i in 0 until n) {
            if (!state.tightenIntMin(ys[i], xlb(fPrime[i]), ant)) return false
        }

        // (c) Condense the xy-intersection graph into SCCs, then tighten each xs to the range its
        // component's ys span.
        for (j in 0 until n) {
            var tmp = 0
            val jprime = f[j]
            for (i in 0 until n) {
                if (j != i && intersect(i, jprime)) {
                    xyGraph[j][tmp] = i
                    tmp++
                }
            }
        }
        dfs()

        tmpArray.fill(0)
        for (i in 0 until n) {
            sccSequences[sccNumbers[i]][tmpArray[sccNumbers[i]]] = i
            tmpArray[sccNumbers[i]]++
        }
        var c = 0
        while (c < n && sccSequences[c][0] != -1) {
            var j = 0
            while (j < n && sccSequences[c][j] != -1) {
                val jprime = f[sccSequences[c][j]]
                var k = 0
                while (k < n && sccSequences[c][k] != -1 && xlb(jprime) > yub(sccSequences[c][k])) k++
                if (k >= n || sccSequences[c][k] == -1) return false
                if (!state.tightenIntMin(xs[jprime], ylb(sccSequences[c][k]), ant)) return false
                j++
            }
            c++
        }

        tmpArray.fill(0)
        for (i in n - 1 downTo 0) {
            sccSequences[sccNumbers[i]][tmpArray[sccNumbers[i]]] = i
            tmpArray[sccNumbers[i]]++
        }
        c = 0
        while (c < n && sccSequences[c][0] != -1) {
            var j = 0
            while (j < n && sccSequences[c][j] != -1) {
                val jprime = f[sccSequences[c][j]]
                var k = 0
                while (k < n && sccSequences[c][k] != -1 && xub(jprime) < ylb(sccSequences[c][k])) k++
                if (k >= n || sccSequences[c][k] == -1) return false
                if (!state.tightenIntMax(xs[jprime], yub(sccSequences[c][k]), ant)) return false
                j++
            }
            c++
        }
        return true
    }

    /** Whether domains of `xs[x]` and `ys[y]` overlap. */
    private fun intersect(y: Int, x: Int): Boolean {
        val xl = xlb(x)
        val xu = xub(x)
        val yl = ylb(y)
        val yu = yub(y)
        return (xl in yl..yu) || (xu in yl..yu) || (yl in xl..xu) || (yu in xl..xu)
    }

    /** Pop the smallest-UB candidate for `ys[j]`; null (⇒ fail) if none can reach `ys[j].min`. */
    private fun popF(j: Int): Int? {
        if (pq.isEmpty()) return null
        val i = pq.pop()
        if (xub(i) < ylb(j)) return null
        return i
    }

    /** Pop the largest-LB candidate for `ys[j]`; null (⇒ fail) if none fits below `ys[j].max`. */
    private fun popFPrime(j: Int): Int? {
        if (pq.isEmpty()) return null
        val i = pq.pop()
        if (xlb(i) > yub(j)) return null
        return i
    }

    private fun dfs() {
        dfsNodes.fill(0)
        s1.clear()
        s2.clear()
        currentScc = 0
        for (i in 0 until n) {
            if (dfsNodes[i] == 0) dfsVisit(i)
        }
        while (s1.size > 0 && !s2.isEmpty()) {
            s2.peek(recup)
            var i: Int
            do {
                i = s1.pop()
                sccNumbers[i] = currentScc
            } while (s1.size > 0 && i != recup[0])
            currentScc++
            s2.pop()
        }
    }

    private fun dfsVisit(node: Int) {
        dfsNodes[node] = 1
        if (s2.isEmpty()) {
            s1.push(node)
            s2.push(node, node, xub(f[node]))
            var i = 0
            while (xyGraph[node][i] != -1) {
                if (dfsNodes[xyGraph[node][i]] == 0) dfsVisit(xyGraph[node][i])
                i++
            }
        } else {
            while (s2.peek(recup) && recup[2] < ylb(node)) {
                var i = s1.pop()
                while (i != recup[0]) {
                    sccNumbers[i] = currentScc
                    i = s1.pop()
                }
                sccNumbers[i] = currentScc
                s2.pop()
                currentScc++
            }
            s1.push(node)
            recup[0] = node
            recup[1] = node
            recup[2] = xub(f[node])
            mergeStack(node)
            var i = 0
            while (xyGraph[node][i] != -1) {
                if (dfsNodes[xyGraph[node][i]] == 0) dfsVisit(xyGraph[node][i])
                i++
            }
        }
        dfsNodes[node] = 2
    }

    private fun mergeStack(node: Int) {
        s2.peek(recup2)
        while (!s2.isEmpty() && yub(recup2[1]) >= xlb(f[node])) {
            recup[0] = recup2[0]
            recup[1] = node
            recup[2] = if (recup[2] > recup2[2]) recup[2] else recup2[2]
            s2.pop()
            s2.peek(recup2)
        }
        s2.push(recup[0], recup[1], recup[2])
    }

    /** Min-key priority queue over element ids; `pop` returns the element with the smallest key. */
    private class MinHeap(capacity: Int) {
        private val elems = IntArray(capacity)
        private val keys = IntArray(capacity)
        private var size = 0

        fun clear() {
            size = 0
        }

        fun isEmpty() = size == 0

        fun add(elem: Int, key: Int) {
            var i = size++
            elems[i] = elem
            keys[i] = key
            while (i > 0) {
                val parent = (i - 1) / 2
                if (keys[parent] <= keys[i]) break
                swap(parent, i)
                i = parent
            }
        }

        fun pop(): Int {
            val top = elems[0]
            size--
            if (size > 0) {
                elems[0] = elems[size]
                keys[0] = keys[size]
                var i = 0
                while (true) {
                    val l = 2 * i + 1
                    val r = 2 * i + 2
                    var smallest = i
                    if (l < size && keys[l] < keys[smallest]) smallest = l
                    if (r < size && keys[r] < keys[smallest]) smallest = r
                    if (smallest == i) break
                    swap(i, smallest)
                    i = smallest
                }
            }
            return top
        }

        private fun swap(a: Int, b: Int) {
            val e = elems[a]
            elems[a] = elems[b]
            elems[b] = e
            val k = keys[a]
            keys[a] = keys[b]
            keys[b] = k
        }
    }

    /** A bounded LIFO stack of ints. */
    private class IntStack(capacity: Int) {
        private val data = IntArray(capacity)
        var size = 0
            private set

        fun clear() {
            size = 0
        }

        fun push(v: Int) {
            data[size++] = v
        }

        fun pop(): Int = data[--size]
    }

    /** Stack of tentative SCCs as `(root, rightMost, maxX)` triples (Mehlhorn–Thiel). */
    private class Stack2(capacity: Int) {
        private val roots = IntArray(capacity)
        private val rightMosts = IntArray(capacity)
        private val maxXs = IntArray(capacity)
        private var size = 0

        fun clear() {
            size = 0
        }

        fun isEmpty() = size == 0

        fun push(root: Int, rightMost: Int, maxX: Int) {
            roots[size] = root
            rightMosts[size] = rightMost
            maxXs[size] = maxX
            size++
        }

        fun pop() {
            if (size > 0) size--
        }

        fun peek(out: IntArray): Boolean {
            if (size == 0) return false
            out[0] = roots[size - 1]
            out[1] = rightMosts[size - 1]
            out[2] = maxXs[size - 1]
            return true
        }
    }
}
