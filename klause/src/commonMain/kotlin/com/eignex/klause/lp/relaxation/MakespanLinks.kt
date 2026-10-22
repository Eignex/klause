package com.eignex.klause.lp.relaxation

import com.eignex.klause.factor.arithmetic.ArrayMinMax
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.util.CONSTRUCTION_BATCH_SIZE
import com.eignex.klause.util.CheckedLongOverflowException
import com.eignex.klause.util.IntArrayDeque
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.MutableIntLongMap
import com.eignex.klause.util.MutableIntObjectMap
import com.eignex.klause.util.addExact

/**
 * Verified "`w ≥ v + c`" links harvested once from a problem's factors: two-variable `Linear` rows with
 * a `(+1, −1)` coefficient pair imply one directly (any orientation, including each side of an
 * equality), and every `ArrayMinMax(max)` result is ≥ each of its operands (`c = 0`). A link is only
 * ever read off an actual row, never inferred from names or domains, so a missing row drops a task
 * from a makespan bound rather than inventing one.
 */
internal class MakespanLinks {
    /** `geFrom[v]` = pairs `(w, c)` with a proven `w ≥ v + c`. */
    private val geFrom = MutableIntObjectMap<ArrayList<LongArray>>()

    /** `geInto[w]` = pairs `(v, c)` with a proven `w ≥ v + c`; the reverse of [geFrom]. */
    private val geInto = MutableIntObjectMap<ArrayList<LongArray>>()

    /** `maxResultsOf[operand]` = `ArrayMinMax(max)` results, each ≥ that operand. */
    private val maxResultsOf = MutableIntObjectMap<IntArrayList>()

    /** `maxOperandsOf[result]` = that `ArrayMinMax(max)` result's operands; the reverse of [maxResultsOf]. */
    private val maxOperandsOf = MutableIntObjectMap<IntArrayList>()

    private var edges = 0L
    private var visits = 0

    private suspend fun SequenceScope<Unit>.tick() {
        if (++visits % CONSTRUCTION_BATCH_SIZE == 0) yield(Unit)
    }

    /** Record every `w ≥ v + c` implied by a two-variable [Linear] `coeffA·a + coeffB·b op bound`. */
    fun addLinearLinks(f: Linear) {
        // Only ±1-coefficient rows produce a link; a coefficient outside ±1 (including any beyond
        // Int range) matches neither pattern below and adds nothing.
        val row = f.integerConstants ?: return
        val a = f.vars[0]
        val b = f.vars[1]
        val ca = row.coeff(0)
        val cb = row.coeff(1)
        val bound = row.bound
        // "≥ bound" holds for GE/EQ; "≤ bound" (⇔ "≥ −bound" after negating) holds for LE/EQ.
        if (f.op == LinearOp.GE || f.op == LinearOp.EQ) addGeForm(a, ca, b, cb, bound)
        if (f.op == LinearOp.LE || f.op == LinearOp.EQ) addGeForm(a, -ca, b, -cb, -bound)
    }

    /** From `ca·a + cb·b ≥ rhs`, record `w ≥ v + rhs` when the coefficients are a `(+1, −1)` pair. */
    private fun addGeForm(a: Int, ca: Long, b: Int, cb: Long, rhs: Long) {
        if (ca == 1L && cb == -1L) add(w = a, v = b, c = rhs)
        if (ca == -1L && cb == 1L) add(w = b, v = a, c = rhs)
    }

    private fun add(w: Int, v: Int, c: Long) {
        geFrom.getOrPut(v) { ArrayList() }.add(longArrayOf(w.toLong(), c))
        geInto.getOrPut(w) { ArrayList() }.add(longArrayOf(v.toLong(), c))
        edges++
    }

    /** Record `result ≥ operand` for one operand of an `ArrayMinMax(max)`. */
    fun addMax(result: Int, operand: Int) {
        maxResultsOf.getOrPut(operand) { IntArrayList() }.add(result)
        maxOperandsOf.getOrPut(result) { IntArrayList() }.add(operand)
        edges++
    }

    /**
     * Every variable provably ≥ the end `start + dur` of a task: those a direct link proves ≥
     * `start + c` with `c ≥ dur`, closed through max edges (`result = max(…) ≥ operand ≥ end`).
     */
    suspend fun SequenceScope<Unit>.endUpperBoundsOf(start: Int, dur: Long): IntHashSet {
        val result = IntHashSet()
        val queue = IntArrayDeque()
        geFrom[start]?.forEach { wc ->
            tick()
            if (wc[1] >= dur) {
                val w = wc[0].toInt()
                if (result.add(w)) queue.addLast(w)
            }
        }
        while (queue.isNotEmpty()) {
            val v = queue.removeFirst()
            maxResultsOf[v]?.forEach { m ->
                if (result.add(m)) queue.addLast(m)
                tick()
            }
        }
        return result
    }

    /**
     * The largest verified offset `c` with `target ≥ v + c`, for every `v` some chain of links reaches
     * [target] from: two-variable rows add their constants along the chain, max edges add zero. A task
     * with start `s` and duration `d` ends by [target] when its offset is at least `d`.
     *
     * Label-correcting longest paths. Every recorded offset is witnessed by an actual chain of rows, so
     * stopping early only leaves offsets smaller. The relaxation allowance is what stops a positive
     * cycle, which only an infeasible model has; an offset that would overflow is not recorded.
     */
    suspend fun SequenceScope<Unit>.offsetsTo(target: Int): MutableIntLongMap {
        val best = MutableIntLongMap()
        best.put(target, 0L)
        val queue = IntArrayDeque()
        val queued = IntHashSet()
        queue.addLast(target)
        queued.add(target)
        var allowance = RELAXATION_ROUNDS * (edges + 1L)
        fun offer(v: Int, offset: Long) {
            if (offset <= best.getOrDefault(v, Long.MIN_VALUE)) return
            best.put(v, offset)
            if (queued.add(v)) queue.addLast(v)
        }
        while (queue.isNotEmpty() && allowance > 0L) {
            val w = queue.removeFirst()
            queued.remove(w)
            val base = best.getOrDefault(w, Long.MIN_VALUE)
            geInto[w]?.forEach { vc ->
                allowance--
                tick()
                val offset = try {
                    addExact(base, vc[1])
                } catch (_: CheckedLongOverflowException) {
                    null
                }
                if (offset != null) offer(vc[0].toInt(), offset)
            }
            maxOperandsOf[w]?.forEach { x ->
                allowance--
                tick()
                offer(x, base)
            }
        }
        return best
    }

    private companion object {
        /** Relaxations allowed per harvested link in [offsetsTo]. A FIFO label-correcting pass relaxes each
         *  link at most once per round, and a chain of k links settles within k rounds; the allowance is a
         *  total, so only the rare re-improved links spend it, and a positive cycle exhausts it. */
        const val RELAXATION_ROUNDS: Long = 64L
    }
}

/** Harvest the [MakespanLinks] of [problem]'s factors. */
internal suspend fun SequenceScope<Unit>.prepareMakespanLinks(problem: Problem): MakespanLinks {
    val links = MakespanLinks()
    var visits = 0
    for (f in problem.factors) {
        when (f) {
            is Linear -> if (f.integerConstants != null && f.vars.size == 2) links.addLinearLinks(f)
            is ArrayMinMax -> if (f.max) for (x in f.xs) {
                links.addMax(f.result, x)
                if (++visits % CONSTRUCTION_BATCH_SIZE == 0) yield(Unit)
            }
            else -> Unit
        }
        if (++visits % CONSTRUCTION_BATCH_SIZE == 0) yield(Unit)
    }
    return links
}
