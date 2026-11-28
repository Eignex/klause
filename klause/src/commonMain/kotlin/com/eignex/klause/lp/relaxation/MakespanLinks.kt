package com.eignex.klause.lp.relaxation

import com.eignex.klause.factor.arithmetic.ArrayMinMax
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.util.CONSTRUCTION_BATCH_SIZE
import com.eignex.util.collections.IntArrayDeque
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.IntHashSet
import com.eignex.util.collections.MutableIntLongMap
import com.eignex.util.collections.MutableIntObjectMap
import com.eignex.util.numbers.CheckedLongOverflowException
import com.eignex.util.numbers.addExact

// Links w ≥ v + c read off actual rows: two-variable Linear rows with a (+1, −1) coefficient pair and
// ArrayMinMax(max) result ≥ operand (c = 0). Nothing is inferred from names or domains, so a missing row
// drops a task from a makespan bound rather than inventing one.
internal class MakespanLinks {
    // geFrom(v) holds pairs (w, c) with w ≥ v + c; geInto(w) the pairs (v, c).
    private val geFrom = MutableIntObjectMap<ArrayList<LongArray>>()

    private val geInto = MutableIntObjectMap<ArrayList<LongArray>>()

    // operand → ArrayMinMax(max) results, and the reverse.
    private val maxResultsOf = MutableIntObjectMap<IntArrayList>()

    private val maxOperandsOf = MutableIntObjectMap<IntArrayList>()

    private var edges = 0L
    private var visits = 0

    private suspend fun SequenceScope<Unit>.tick() {
        if (++visits % CONSTRUCTION_BATCH_SIZE == 0) yield(Unit)
    }

    fun addLinearLinks(f: Linear) {
        val row = f.integerConstants ?: return
        val a = f.vars[0]
        val b = f.vars[1]
        val ca = row.coeff(0)
        val cb = row.coeff(1)
        val bound = row.bound
        // LE is read as GE after negating both sides; EQ gives both.
        if (f.op == LinearOp.GE || f.op == LinearOp.EQ) addGeForm(a, ca, b, cb, bound)
        if (f.op == LinearOp.LE || f.op == LinearOp.EQ) addGeForm(a, -ca, b, -cb, -bound)
    }

    private fun addGeForm(a: Int, ca: Long, b: Int, cb: Long, rhs: Long) {
        if (ca == 1L && cb == -1L) add(w = a, v = b, c = rhs)
        if (ca == -1L && cb == 1L) add(w = b, v = a, c = rhs)
    }

    private fun add(w: Int, v: Int, c: Long) {
        geFrom.getOrPut(v) { ArrayList() }.add(longArrayOf(w.toLong(), c))
        geInto.getOrPut(w) { ArrayList() }.add(longArrayOf(v.toLong(), c))
        edges++
    }

    fun addMax(result: Int, operand: Int) {
        maxResultsOf.getOrPut(operand) { IntArrayList() }.add(result)
        maxOperandsOf.getOrPut(result) { IntArrayList() }.add(operand)
        edges++
    }

    // Direct links w ≥ start + c with c ≥ dur, closed through max edges.
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

    // Largest verified c with target ≥ v + c over chains of links, by label-correcting longest paths. Every
    // recorded offset is witnessed by a chain, so stopping early only leaves offsets smaller. The allowance
    // stops a positive cycle, which only an infeasible model has; overflowing offsets are not recorded.
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
        // Relaxations per harvested link. A FIFO pass relaxes each link at most once per round and a chain
        // of k links settles within k rounds; as a total, only re-improved links spend it.
        const val RELAXATION_ROUNDS: Long = 64L
    }
}

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
