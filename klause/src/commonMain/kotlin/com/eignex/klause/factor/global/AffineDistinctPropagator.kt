package com.eignex.klause.factor.global

import com.eignex.klause.config.DEFAULT_DOMAIN_WALK_CAP
import com.eignex.klause.factor.global.internals.computeBoundsAllDifferent
import com.eignex.klause.factor.global.internals.reginHopcroftKarp
import com.eignex.klause.factor.global.internals.reginTarjanScc
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.values
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.propagation.boundLiteral
import com.eignex.klause.propagation.domainAt
import com.eignex.klause.propagation.exclusionLiteral
import com.eignex.klause.propagation.inDomainAt
import com.eignex.klause.propagation.lazyReason
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.MutableLongIntMap
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.fitsLong
import com.eignex.klause.util.minus
import com.eignex.klause.util.plus
import com.eignex.klause.util.toLongExact

internal fun Distinct.affinePropagationView(): Propagator? {
    if (activator != LinearRow.ALWAYS) return null
    val positions = operands
    if (positions.any {
        it.size != 1 || !Term.isInt(it.references.single()) ||
            it.coefficients.single() != BigFraction.ONE || it.constant.den != BIG_ONE
    }) return null
    return AffineDistinctPropagator(
        IntArray(positions.size) { Term.intVar(positions[it].references.single()) },
        positions.map { it.constant.num },
    )
}

internal class AffineDistinctPropagator(private val vars: IntArray, private val offsets: List<BigInt>) : Propagator {
    override val expensiveBake: Boolean get() = true

    private class Cache {
        var domains: Array<IntDomain>? = null
        var matched: LongArray? = null
    }

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? = state.propagatorFailures[this]

    override fun explain(state: PropagationState, factorId: Int, payload: IntArray, atTrail: Int, atLevel: Int): IntArray {
        val out = linkedSetOf<Int>()
        fun add(lit: Int) { if (lit != Lit.NONE) out.add(lit) }
        for (v in payload.distinct()) {
            val d = state.domainAt(v, atTrail)
            val root = state.rootDomains[v]
            add(state.boundLiteral(v, true, d.min, atTrail, atLevel))
            add(state.boundLiteral(v, false, d.max, atTrail, atLevel))
            fun hole(k: Long) {
                if (k in d.min..d.max && k in root && !state.inDomainAt(v, k, atTrail, d)) {
                    add(state.exclusionLiteral(v, k, atTrail, d))
                }
            }
            val members = root.spanOrNull(d.holeCount)
            if (members != null) members.forEach(::hole) else d.forEachHole(::hole)
        }
        return out.toIntArray()
    }

    private fun reason(state: PropagationState, positions: IntArray): IntArray? {
        if (state.currentLevel == 0) return null
        val sources = IntArray(positions.size) { vars[positions[it]] }
        return if (state.undoLogging) state.lazyReason(sources)
        else explain(state, state.currentFactor, sources, state.undo.size, state.currentLevel)
    }

    private fun fail(state: PropagationState, positions: IntArray): Boolean {
        state.propagatorFailures[this] = explain(
            state, state.currentFactor, IntArray(positions.size) { vars[positions[it]] },
            state.undo.size, state.currentLevel,
        )
        return false
    }

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        state.propagatorFailures.remove(this)
        val n = vars.size
        if (n < 2) return true
        val cache = (state.refPayload[factorId] as? Cache) ?: Cache().also { state.refPayload[factorId] = it }
        if (cache.domains?.let { old -> vars.indices.all { old[it] === state.intDomains[vars[it]] } } == true) return true
        val domains = Array(n) { state.intDomains[vars[it]] }
        val lo = List(n) { bigIntOf(domains[it].min) + offsets[it] }
        val hi = List(n) { bigIntOf(domains[it].max) + offsets[it] }
        val origin = lo.reduce { a, b -> if (a < b) a else b }
        val ceiling = hi.reduce { a, b -> if (a > b) a else b }
        val compact = (ceiling - origin).fitsLong()
        val result = if (domains.any { it.spanOrNull(DEFAULT_DOMAIN_WALK_CAP) == null }) {
            bounds(state, lo, hi, origin, compact)
        } else {
            matching(state, cache, domains, lo, origin, compact)
        }
        cache.domains = if (result && vars.indices.all { domains[it] === state.intDomains[vars[it]] }) domains else null
        return result
    }

    private fun bounds(state: PropagationState, lo: List<BigInt>, hi: List<BigInt>, origin: BigInt, compact: Boolean): Boolean {
        val all = IntArray(vars.size) { it }
        if (!compact) return fixed(state, lo, hi)
        val lower = LongArray(vars.size) { (lo[it] - origin).toLongExact() }
        val upper = LongArray(vars.size) { (hi[it] - origin).toLongExact() }
        val newLower = lower.copyOf()
        val newUpper = upper.copyOf()
        if (!computeBoundsAllDifferent(lower, upper, newLower, newUpper)) return fail(state, all)
        for (i in vars.indices) {
            if (newLower[i] > lower[i] && !state.tightenIntMin(vars[i],
                    (origin + bigIntOf(newLower[i]) - offsets[i]).toLongExact(), reason(state, all))) return fail(state, all)
            if (newUpper[i] < upper[i] && !state.tightenIntMax(vars[i],
                    (origin + bigIntOf(newUpper[i]) - offsets[i]).toLongExact(), reason(state, all))) return fail(state, all)
        }
        return fixed(state, lo, hi)
    }

    private fun fixed(state: PropagationState, lo: List<BigInt>, hi: List<BigInt>): Boolean {
        val owners = HashMap<BigInt, Int>()
        for (i in vars.indices) {
            if (lo[i] != hi[i]) continue
            val previous = owners.put(lo[i], i)
            if (previous != null) return fail(state, intArrayOf(previous, i))
        }
        for ((value, owner) in owners) {
            for (i in vars.indices) {
                if (i == owner) continue
                val source = value - offsets[i]
                if (source.fitsLong() && !state.excludeIntValue(vars[i], source.toLongExact(), reason(state, intArrayOf(owner)))) {
                    return fail(state, intArrayOf(owner, i))
                }
            }
        }
        return true
    }

    private fun matching(
        state: PropagationState,
        cache: Cache,
        domains: Array<IntDomain>,
        lo: List<BigInt>,
        origin: BigInt,
        compact: Boolean,
    ): Boolean {
        val n = vars.size
        val sourceValues = Array(n) { i ->
            val span = domains[i].values
            val sources = LongArray(span.size)
            var index = 0
            span.forEach { sources[index++] = it }
            sources
        }
        val words = MutableLongIntMap()
        val wide = HashMap<BigInt, Int>()
        var count = 0
        val values = Array(n) { i ->
            val base = if (compact) (lo[i] - origin).toLongExact() else 0L
            IntArray(sourceValues[i].size) { k ->
                val source = sourceValues[i][k]
                if (compact) {
                    val value = base + (source - domains[i].min)
                    val old = words.getOrDefault(value, -1)
                    if (old >= 0) old else count++.also { words.put(value, it) }
                } else {
                    wide.getOrPut(bigIntOf(source) + offsets[i]) { count++ }
                }
            }
        }
        val all = IntArray(n) { it }
        if (count < n) return fail(state, all)
        val matchVar = IntArray(n) { -1 }
        val matchVal = IntArray(count) { -1 }
        cache.matched?.let { old ->
            for (i in vars.indices) {
                val k = sourceValues[i].indexOf(old[i])
                if (k < 0) continue
                val id = values[i][k]
                if (matchVal[id] >= 0) continue
                matchVar[i] = id
                matchVal[id] = i
            }
        }
        if (!reginHopcroftKarp(n, values, matchVar, matchVal, IntArray(n), IntArray(n))) return fail(state, all)
        val total = n + count
        val adj = Array(total) { IntArrayList() }
        val reverse = Array(total) { IntArrayList() }
        for (i in vars.indices) {
            for (id in values[i]) {
                val from = if (matchVar[i] == id) n + id else i
                val to = if (matchVar[i] == id) i else n + id
                adj[from].add(to)
                reverse[to].add(from)
            }
        }
        val reached = BooleanArray(total)
        val queue = IntArray(total)
        var head = 0
        var tail = 0
        for (id in matchVal.indices) {
            if (matchVal[id] >= 0) continue
            reached[n + id] = true
            queue[tail++] = n + id
        }
        while (head < tail) {
            val neighbors = reverse[queue[head++]]
            for (k in 0 until neighbors.size) {
                val next = neighbors[k]
                if (reached[next]) continue
                reached[next] = true
                queue[tail++] = next
            }
        }
        val scc = reginTarjanScc(adj, total)
        val reasons = HashMap<Int, IntArray?>()
        for (i in vars.indices) {
            for (k in values[i].indices) {
                val id = values[i][k]
                val node = n + id
                if (matchVar[i] == id || reached[node] || scc[i] == scc[node]) continue
                val ant = reasons.getOrPut(scc[node]) { reason(state, hall(adj, node, n)) }
                if (!state.excludeIntValue(vars[i], sourceValues[i][k], ant)) return fail(state, all)
            }
        }
        cache.matched = LongArray(n) { i -> sourceValues[i][values[i].indexOf(matchVar[i])] }
        return true
    }

    private fun hall(adj: Array<IntArrayList>, start: Int, n: Int): IntArray {
        val seen = BooleanArray(adj.size)
        val queue = IntArray(adj.size)
        val positions = IntArrayList()
        var head = 0
        var tail = 1
        queue[0] = start
        seen[start] = true
        while (head < tail) {
            val node = queue[head++]
            if (node < n) positions.add(node)
            val neighbors = adj[node]
            for (k in 0 until neighbors.size) {
                val next = neighbors[k]
                if (seen[next]) continue
                seen[next] = true
                queue[tail++] = next
            }
        }
        return positions.toIntArray()
    }
}
