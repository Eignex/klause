package com.eignex.klause.factor.global

import com.eignex.klause.factor.arithmetic.internals.collectHoleAndBoundAntecedents
import com.eignex.klause.factor.arithmetic.internals.collectLinearTightenAntecedents
import com.eignex.klause.ir.Lit
import com.eignex.klause.propagation.IntEvent
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.propagation.boundLiteral
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet

/**
 * CP propagation logic for `sort` — bound-consistency via the Mehlhorn–Thiel algorithm
 * ("Faster Algorithms for Bound-Consistency of the Sortedness and the Alldifferent Constraint",
 * CP'00). The relation is `ys = sorted(xs)`: `ys` is non-decreasing and shares `xs`'s multiset.
 *
 * The filter (a) normalizes `ys` to a non-decreasing bound chain, (b) builds two perfect
 * matchings `f` / `f'` between sorted positions and the `xs` (smallest-upper-bound and
 * largest-lower-bound greedy matchings) to tighten each `ys` bound, then (c) condenses the
 * `xy`-intersection graph into strongly connected components and tightens each `xs` bound to the
 * range its component's `ys` can take. Steps (a)/(b) alone subsume endpoint-only reasoning; the SCC
 * step (c) is what lets a middle `xs` learn bounds from its sorted position.
 *
 * The propagator itself holds only the immutable constraint scope. All per-search working state —
 * the matchings, the SCC scratch, the priority queue and the two stacks — lives in a [SortWork]
 * held in `state.refPayload[factorId]`, because a [Propagator] instance is cached once per
 * `Problem` and shared across every [PropagationState], including the
 * concurrently-running arms of a parallel portfolio. Scratch as propagator fields would race across
 * those arms; keyed off `refPayload` each search owns its own copy (mirrors
 * [com.eignex.klause.factor.table.internals.ElementConstState] and `VpState`).
 */
internal class SortPropagator(
    val boolVars: IntArray,
    val intVars: IntArray,
    private val xs: IntArray,
    private val ys: IntArray,
) : Propagator {

    /**
     * Advisor subscription: the sort propagator reads only each variable's `min`/`max` and
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
        (state.refPayload[factorId] as? SortWork)?.failure
            ?: collectLinearTightenAntecedents(state, intVars, excludeIdx = -1, extraLit = 0)

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        val work = (state.refPayload[factorId] as? SortWork)
            ?: SortWork(xs, ys, intVars).also { state.refPayload[factorId] = it }
        return work.propagate(state)
    }
}

/**
 * Per-search working state and filtering loop for one [SortPropagator]. Allocated lazily on the first
 * fire and reused across fires of the same [PropagationState] — the scratch is fully reset at the top
 * of each pass, so nothing survives between fires and no trail participation is needed. Held in
 * `refPayload` (never as propagator fields) so parallel portfolio arms don't share it.
 */
internal class SortWork(private val xs: IntArray, private val ys: IntArray, private val intVars: IntArray) {

    private val n = xs.size

    // Scratch state, allocated once and reused across fires of the owning search.
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

    // (root, rightMost, maxX) triples read from Stack2: slots 0/1 are node ids, slot 2 is a value.
    private val recup = LongArray(3)
    private val recup2 = LongArray(3)
    private var currentScc = 0

    private fun xlb(i: Int) = state.intDomains[xs[i]].min
    private fun xub(i: Int) = state.intDomains[xs[i]].max
    private fun ylb(i: Int) = state.intDomains[ys[i]].min
    private fun yub(i: Int) = state.intDomains[ys[i]].max

    // The active state, set per propagate() so the bound accessors above stay terse.
    private lateinit var state: PropagationState

    /** The reason of the failure the last [propagate] hit, read by the propagator's conflict reason. */
    var failure: IntArray? = null
        private set

    private class Lits {
        private val seen = IntHashSet()
        private val out = IntArrayList()

        fun add(lit: Int) {
            if (lit != Lit.NONE && seen.add(lit)) out.add(lit)
        }

        fun toArray(): IntArray = out.toIntArray()
    }

    private fun Lits.bound(v: Int, lower: Boolean, need: Long) =
        add(state.boundLiteral(v, lower, need, state.undo.size, state.currentLevel))

    // `ys` is `xs` sorted, so y(i) is the (i+1)-th smallest x: it is at most [b] once a later y is, or once
    // i+1 of the xs are.
    private fun Lits.yAtMost(i: Int, b: Long): Boolean {
        for (k in i + 1 until n) {
            if (yub(k) <= b) {
                bound(ys[k], false, b)
                return true
            }
        }
        val under = (0 until n).filter { xub(it) <= b }.sortedBy { xub(it) }
        if (under.size < i + 1) return false
        for (c in 0..i) bound(xs[under[c]], false, b)
        return true
    }

    private fun Lits.yAtLeast(i: Int, l: Long): Boolean {
        for (k in 0 until i) {
            if (ylb(k) >= l) {
                bound(ys[k], true, l)
                return true
            }
        }
        val over = (0 until n).filter { xlb(it) >= l }.sortedByDescending { xlb(it) }
        if (over.size < n - i) return false
        for (c in 0 until n - i) bound(xs[over[c]], true, l)
        return true
    }

    // x(j) is at least [l] once the ys that may lie below l are all taken by other xs that must.
    private fun Lits.xAtLeast(j: Int, l: Long): Boolean {
        val k0 = (0 until n).firstOrNull { ylb(it) >= l } ?: return false
        val below = (0 until n).filter { it != j && xub(it) <= l - 1 }
        if (below.size < k0) return false
        bound(ys[k0], true, l)
        for (c in 0 until k0) bound(xs[below[c]], false, l - 1)
        return true
    }

    private fun Lits.xAtMost(j: Int, u: Long): Boolean {
        val k1 = (n - 1 downTo 0).firstOrNull { yub(it) <= u } ?: return false
        val above = (0 until n).filter { it != j && xlb(it) >= u + 1 }
        if (above.size < n - 1 - k1) return false
        bound(ys[k1], false, u)
        for (c in 0 until n - 1 - k1) bound(xs[above[c]], true, u + 1)
        return true
    }

    // The reason [witness] finds, or every variable's bounds when it finds none.
    private fun reason(witness: Lits.() -> Boolean): IntArray? {
        if (state.currentLevel == 0) return null
        val lits = Lits()
        return if (lits.witness()) lits.toArray() else collectLinearTightenAntecedents(state, intVars, -1, 0)
    }

    private fun tightenMin(v: Int, bound: Long, witness: Lits.() -> Boolean): Boolean {
        if (bound <= state.intDomains[v].min) return true
        val ant = reason(witness)
        if (state.tightenIntMin(v, bound, ant)) return true
        failure = (ant ?: IntArray(0)) + (collectHoleAndBoundAntecedents(state, intArrayOf(v)) ?: IntArray(0))
        return false
    }

    private fun tightenMax(v: Int, bound: Long, witness: Lits.() -> Boolean): Boolean {
        if (bound >= state.intDomains[v].max) return true
        val ant = reason(witness)
        if (state.tightenIntMax(v, bound, ant)) return true
        failure = (ant ?: IntArray(0)) + (collectHoleAndBoundAntecedents(state, intArrayOf(v)) ?: IntArray(0))
        return false
    }

    fun propagate(state: PropagationState): Boolean {
        this.state = state
        failure = null

        for (i in 0 until n) {
            xyGraph[i].fill(-1)
            sccSequences[i].fill(-1)
        }

        // (a) Normalize ys into a non-decreasing bound chain.
        for (i in 1 until n) {
            val b = ylb(i - 1)
            if (!tightenMin(ys[i], b) { true.also { bound(ys[i - 1], true, b) } }) return false
        }
        for (i in n - 2 downTo 0) {
            val b = yub(i + 1)
            if (!tightenMax(ys[i], b) { true.also { bound(ys[i + 1], false, b) } }) return false
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
            val b = xub(f[i])
            if (!tightenMax(ys[i], b) { yAtMost(i, b) }) return false
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
            val b = xlb(fPrime[i])
            if (!tightenMin(ys[i], b) { yAtLeast(i, b) }) return false
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
                val b = ylb(sccSequences[c][k])
                if (!tightenMin(xs[jprime], b) { xAtLeast(jprime, b) }) return false
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
                val b = yub(sccSequences[c][k])
                if (!tightenMax(xs[jprime], b) { xAtMost(jprime, b) }) return false
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
            } while (s1.size > 0 && i != recup[0].toInt())
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
                while (i != recup[0].toInt()) {
                    sccNumbers[i] = currentScc
                    i = s1.pop()
                }
                sccNumbers[i] = currentScc
                s2.pop()
                currentScc++
            }
            s1.push(node)
            recup[0] = node.toLong()
            recup[1] = node.toLong()
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
        while (!s2.isEmpty() && yub(recup2[1].toInt()) >= xlb(f[node])) {
            recup[0] = recup2[0]
            recup[1] = node.toLong()
            recup[2] = if (recup[2] > recup2[2]) recup[2] else recup2[2]
            s2.pop()
            s2.peek(recup2)
        }
        s2.push(recup[0].toInt(), recup[1].toInt(), recup[2])
    }

    /** Min-key priority queue over element ids; `pop` returns the element with the smallest key. */
    private class MinHeap(capacity: Int) {
        private val elems = IntArray(capacity)
        private val keys = LongArray(capacity)
        private var size = 0

        fun clear() {
            size = 0
        }

        fun isEmpty() = size == 0

        fun add(elem: Int, key: Long) {
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

    /** Stack of tentative SCCs as `(root, rightMost, maxX)` triples. */
    private class Stack2(capacity: Int) {
        private val roots = IntArray(capacity)
        private val rightMosts = IntArray(capacity)
        private val maxXs = LongArray(capacity)
        private var size = 0

        fun clear() {
            size = 0
        }

        fun isEmpty() = size == 0

        fun push(root: Int, rightMost: Int, maxX: Long) {
            roots[size] = root
            rightMosts[size] = rightMost
            maxXs[size] = maxX
            size++
        }

        fun pop() {
            if (size > 0) size--
        }

        /** Writes `(root, rightMost, maxX)` into [out]; slots 0/1 are node ids, slot 2 a value. */
        fun peek(out: LongArray): Boolean {
            if (size == 0) return false
            out[0] = roots[size - 1].toLong()
            out[1] = rightMosts[size - 1].toLong()
            out[2] = maxXs[size - 1]
            return true
        }
    }
}
