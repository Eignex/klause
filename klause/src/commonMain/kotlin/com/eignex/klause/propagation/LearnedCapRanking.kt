package com.eignex.klause.propagation

import com.eignex.klause.util.IntArrayList

internal interface LearnedCapPolicy {
    fun lbd(index: Int): Int
    fun prepare(index: Int): Boolean
    fun keep(index: Int): Boolean
    fun retire(index: Int): Boolean
    fun visited()
}

// Processed handles belong to the heap/survivor prefix or pending retirement; unread handles stay in the suffix.
// Admissions append, while compaction resets every suspended operation before handles change.
internal class LearnedCapRanking(private val candidates: IntArrayList) {
    private var read = 0
    private var write = 0
    private var heapSize = 0
    private var floor = 0L
    private var sift = 0
    private var pending = -1
    private var prepared = -1
    private var stage = Stage.Collect
    private var afterSift = Stage.Trim
    var passes = 0L
        private set

    fun reset() {
        read = 0
        write = 0
        heapSize = 0
        floor = 0L
        pending = -1
        prepared = -1
        sift = 0
        stage = Stage.Collect
        afterSift = Stage.Trim
    }

    fun advance(cap: Int, fixedFloor: Int, budget: ReclamationPreparation, policy: LearnedCapPolicy) {
        prepared = -1
        while (true) {
            val limit = (cap.toLong() - fixedFloor - floor).coerceAtLeast(0L).toInt()
            when (stage) {
                Stage.Collect -> {
                    if (heapSize > limit) {
                        stage = Stage.Trim
                    } else if (read == candidates.size) {
                        budget.visit(8L)
                        budget.visit(0L)
                        candidates.truncateTo(write)
                        reset()
                        passes++
                        return
                    } else collect(limit, budget, policy)
                }
                Stage.Up -> siftUp(budget, policy)
                Stage.Down -> siftDown(budget, policy)
                Stage.Retire -> {
                    budget.visit(if (pending == prepared) 0L else 8L)
                    budget.visit(0L)
                    if (!policy.retire(pending)) {
                        candidates[write++] = pending
                        floor++
                    }
                    pending = -1
                    stage = Stage.Trim
                }
                Stage.Trim -> trim(limit, budget)
            }
        }
    }

    private fun collect(limit: Int, budget: ReclamationPreparation, policy: LearnedCapPolicy) {
        val index = candidates[read]
        val funded = policy.prepare(index)
        budget.visit(0L)
        prepared = if (funded) index else -1
        if (!funded) {
            candidates[write++] = index
            floor++
            stage = Stage.Trim
        } else if (policy.keep(index)) {
            candidates[write++] = index
            floor++
            stage = Stage.Trim
        } else if (heapSize < limit) {
            if (heapSize < write) candidates[write] = candidates[heapSize]
            candidates[heapSize] = index
            sift = heapSize++
            write++
            stage = Stage.Up
        } else if (heapSize > 0 && better(index, candidates[0], policy)) {
            pending = candidates[0]
            candidates[0] = index
            sift = 0
            afterSift = Stage.Retire
            stage = Stage.Down
        } else {
            pending = index
            stage = Stage.Retire
        }
        read++
        policy.visited()
    }

    private fun siftUp(budget: ReclamationPreparation, policy: LearnedCapPolicy) {
        if (sift == 0) {
            stage = Stage.Trim
            return
        }
        budget.visit(8L)
        budget.visit(0L)
        val parent = (sift - 1) / 2
        if (!better(candidates[parent], candidates[sift], policy)) stage = Stage.Trim else {
            swap(sift, parent)
            sift = parent
        }
    }

    private fun siftDown(budget: ReclamationPreparation, policy: LearnedCapPolicy) {
        budget.visit(12L)
        budget.visit(0L)
        var child = sift * 2 + 1
        if (child >= heapSize) {
            stage = afterSift
            return
        }
        if (child + 1 < heapSize && better(candidates[child], candidates[child + 1], policy)) child++
        if (!better(candidates[sift], candidates[child], policy)) stage = afterSift else {
            swap(sift, child)
            sift = child
        }
    }

    private fun trim(limit: Int, budget: ReclamationPreparation) {
        if (heapSize <= limit) {
            stage = Stage.Collect
            return
        }
        budget.visit(12L)
        budget.visit(0L)
        pending = candidates[0]
        heapSize--
        if (heapSize > 0) candidates[0] = candidates[heapSize]
        write--
        if (write > heapSize) candidates[heapSize] = candidates[write]
        sift = 0
        afterSift = Stage.Retire
        stage = if (heapSize > 0) Stage.Down else Stage.Retire
    }

    private fun better(left: Int, right: Int, policy: LearnedCapPolicy): Boolean {
        val a = policy.lbd(left)
        val b = policy.lbd(right)
        return a < b || (a == b && left < right)
    }

    private fun swap(left: Int, right: Int) {
        val value = candidates[left]
        candidates[left] = candidates[right]
        candidates[right] = value
    }

    private enum class Stage { Collect, Up, Down, Retire, Trim }
}
