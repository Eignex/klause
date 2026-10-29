package com.eignex.klause.presolve.linear

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.presolve.AffinePivotOrder
import com.eignex.klause.presolve.equivalentLinear
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.LongArrayList

/** Selects the next affine pivot without changing the candidate admissibility rules. */
internal object AffinePivotOrders {

    fun new(
        policy: AffinePivotOrder,
        ws: AffineSingletons.FactorOcc,
        eliminated: BooleanArray,
        objectiveIntVars: IntHashSet,
        capWide: Boolean,
        pivotable: ((Linear, Int) -> Boolean)?,
        cancellation: Cancellation,
    ): AffineSingletons.PivotOrder = when (policy) {
        AffinePivotOrder.STABLE_ID ->
            StableIdOrder(ws, eliminated, objectiveIntVars, capWide, pivotable, cancellation)

        AffinePivotOrder.MARKOWITZ ->
            MarkowitzOrder(ws, eliminated, objectiveIntVars, capWide, pivotable, cancellation)

        AffinePivotOrder.REWRITE_COST ->
            MarkowitzOrder(ws, eliminated, objectiveIntVars, capWide, pivotable, cancellation, minimizeRewrite = true)
    }

    private class StableIdOrder(
        private val ws: AffineSingletons.FactorOcc,
        private val eliminated: BooleanArray,
        private val objectiveIntVars: IntHashSet,
        private val capWide: Boolean,
        private val pivotable: ((Linear, Int) -> Boolean)?,
        private val cancellation: Cancellation,
    ) : AffineSingletons.PivotOrder {
        private var scanFrom = 0

        override fun next(): AffineSingletons.AffineCandidate? = AffineSingletons.findAffineCandidate(
            ws,
            scanFrom,
            eliminated,
            objectiveIntVars,
            capWide,
            pivotable,
            cancellation,
        )

        override fun onFolded(minRewrittenId: Int) {
            scanFrom = minRewrittenId
        }

        override fun onRejected(definitionId: Int) {
            scanFrom = definitionId
        }
    }

    /**
     * Cheapest fill-in first, by the Markowitz count `(degree(x) − 1)·partners` (H. M. Markowitz, "The
     * Elimination Form of the Inverse and Its Application to Linear Programming", Management Science
     * 1957, https://doi.org/10.1287/mnsc.3.3.255). Heap keys are estimates that go stale as folds change
     * degrees, so a popped candidate whose current cost exceeds its key is re-queued instead of taken.
     */
    private class MarkowitzOrder(
        private val ws: AffineSingletons.FactorOcc,
        private val eliminated: BooleanArray,
        private val objectiveIntVars: IntHashSet,
        private val capWide: Boolean,
        private val pivotable: ((Linear, Int) -> Boolean)?,
        private val cancellation: Cancellation,
        private val minimizeRewrite: Boolean = false,
    ) : AffineSingletons.PivotOrder {
        private val heap = PivotHeap()
        private var seeded = false
        private var promotionsQueued = 0

        override fun next(): AffineSingletons.AffineCandidate? {
            if (!seeded) {
                seed()
                seeded = true
            }
            queuePromotions()
            var polled = 0
            var rejected = 0
            while (!heap.isEmpty()) {
                if ((polled++ and CANCEL_POLL_MASK) == 0 && cancellation()) return null
                cancellation.charge(AFFINE_WORK_WEIGHT)
                if (rejected >= AFFINE_SCAN_ABORT) return null
                val key = heap.peekCost()
                val id = heap.popId()
                val cand = AffineSingletons.candidateInFactor(
                    ws,
                    id,
                    eliminated,
                    objectiveIntVars,
                    capWide,
                    pivotable,
                    cancellation,
                    minimizeRewrite,
                )
                if (cand == null) {
                    rejected++
                    continue
                }
                val cost = markowitzCost(ws, cand)
                if (cost > key) {
                    heap.push(cost, id)
                    continue
                }
                rejected = 0
                return cand
            }
            return null
        }

        override fun onFolded(minRewrittenId: Int) = Unit

        override fun onRejected(definitionId: Int) {
            heap.push(estimatedCost(definitionId), definitionId)
        }

        private fun seed() {
            var count = 0
            var id = ws.nextEqId(0)
            while (id < ws.size) {
                count++
                id = ws.nextEqId(id + 1)
            }
            heap.reserve(count)
            id = ws.nextEqId(0)
            while (id < ws.size) {
                heap.push(estimatedCost(id), id)
                id = ws.nextEqId(id + 1)
            }
            promotionsQueued = ws.promotedCount()
        }

        private fun queuePromotions() {
            val n = ws.promotedCount()
            while (promotionsQueued < n) {
                val id = ws.promotedAt(promotionsQueued++)
                heap.push(estimatedCost(id), id)
            }
        }

        private fun estimatedCost(id: Int): Long {
            val f = ws.factorAt(id)?.equivalentLinear() ?: return 0L
            val vars = f.intVars
            if (vars.size < 2) return 0L
            val row = f.integerConstants
            var minDegree = Int.MAX_VALUE
            if (row != null) {
                for (xi in f.vars.indices) {
                    val x = f.vars[xi]
                    if (eliminated[x] || x in objectiveIntVars) continue
                    if (pivotable != null && !pivotable(f, xi)) continue
                    val degree = ws.degreeOf(x)
                    if (degree < minDegree) minDegree = degree
                    val cx = row.coeff(xi)
                    if (cx != 1L && cx != -1L) continue
                    return (degree - 1).toLong() * (f.vars.size - 1)
                }
            }
            if (minDegree == Int.MAX_VALUE) return 0L
            return (minDegree - 1).toLong() * (vars.size - 1)
        }
    }

    private fun markowitzCost(ws: AffineSingletons.FactorOcc, cand: AffineSingletons.AffineCandidate): Long =
        (ws.degreeOf(cand.x) - 1).toLong() * cand.termVars.size

    private class PivotHeap {
        private var entries = LongArrayList(0)

        fun isEmpty(): Boolean = entries.size == 0

        fun reserve(n: Int) {
            if (n > 0 && entries.size == 0) entries = LongArrayList(n)
        }

        fun push(cost: Long, id: Int) {
            entries.add(pack(cost, id))
            var i = entries.size - 1
            while (i > 0) {
                val parent = (i - 1) / 2
                if (entries[parent] <= entries[i]) break
                swap(i, parent)
                i = parent
            }
        }

        fun peekCost(): Long = entries[0] ushr ID_BITS

        fun popId(): Int {
            val top = entries[0]
            val last = entries.size - 1
            entries[0] = entries[last]
            entries.truncateTo(last)
            siftDown()
            return (top and ID_MASK).toInt()
        }

        private fun siftDown() {
            var i = 0
            while (true) {
                val left = 2 * i + 1
                if (left >= entries.size) break
                val right = left + 1
                val child = if (right < entries.size && entries[right] < entries[left]) right else left
                if (entries[i] <= entries[child]) break
                swap(i, child)
                i = child
            }
        }

        private fun swap(a: Int, b: Int) {
            val t = entries[a]
            entries[a] = entries[b]
            entries[b] = t
        }

        private fun pack(cost: Long, id: Int): Long = (cost.coerceIn(0L, MAX_COST) shl ID_BITS) or id.toLong()

        private companion object {
            const val ID_BITS = 32
            const val ID_MASK = 0xFFFFFFFFL
            const val MAX_COST = Int.MAX_VALUE.toLong()
        }
    }

    private const val CANCEL_POLL_MASK = 0x3FF
    private const val AFFINE_SCAN_ABORT = 200_000
}
