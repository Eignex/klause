package com.eignex.klause.bench.profile

import com.eignex.klause.ir.Lit

internal data class SeparatorBranch(val value: Boolean, val components: List<List<Int>>, val conflict: Boolean)
internal data class BooleanSeparator(val variable: Int, val branches: List<SeparatorBranch>, val score: Int)

internal object BooleanSeparators {
    fun choose(
        clauses: List<List<Int>>,
        variables: Int,
        fixed: Map<Int, Boolean>,
        visit: () -> Boolean,
        maxCandidates: Int = 32,
        retain: (Long) -> Boolean = { true },
    ): BooleanSeparator? {
        require(variables >= 0 && maxCandidates >= 0)
        require(fixed.keys.all { it in 0 until variables })
        var best: BooleanSeparator? = null
        var candidates = 0
        for (v in 0 until variables) {
            if (!visit()) return null
            if (v in fixed) continue
            if (candidates++ >= maxCandidates) break
            val branches = ArrayList<SeparatorBranch>(2)
            for (value in listOf(false, true)) {
                val branch = partition(clauses, variables, fixed + (v to value), visit, retain) ?: return null
                branches += SeparatorBranch(value, branch.components, branch.conflict)
            }
            val score = branches.maxOf { branch ->
                val sizes = branch.components.map { it.size }
                if (branch.conflict || sizes.size < 2) 0 else sizes.sum() - sizes.max()
            }
            if (score > (best?.score ?: 0)) best = BooleanSeparator(v, branches, score)
        }
        return best
    }

    internal data class Partition(val components: List<List<Int>>, val clauses: List<List<Int>>, val conflict: Boolean)

    fun partition(
        clauses: List<List<Int>>,
        variables: Int,
        fixed: Map<Int, Boolean>,
        visit: () -> Boolean,
        retain: (Long) -> Boolean = { true },
    ): Partition? {
        if (!visit() || !retain(variables * 24L + clauses.sumOf { it.size * 8L + 32L })) return null
        val parent = IntArray(variables) { it }
        val live = BooleanArray(variables)
        fun root(v: Int): Int {
            var r = v
            while (parent[r] != r) {
                if (!visit()) return -1
                r = parent[r]
            }
            return r
        }
        val residual = ArrayList<List<Int>>()
        var conflict = false
        for (clause in clauses) {
            if (!visit()) return null
            val free = ArrayList<Int>()
            var satisfied = false
            for (lit in clause) {
                if (!visit()) return null
                require(lit >= 0 && Lit.variable(lit) in 0 until variables)
                val value = fixed[Lit.variable(lit)]
                if (value == null) free += lit else if (Lit.evaluate(lit, value)) satisfied = true
            }
            if (satisfied) continue
            if (free.isEmpty()) conflict = true
            residual += free
            if (free.isNotEmpty()) {
                val representative = root(Lit.variable(free.first()))
                if (representative < 0) return null
                for (lit in free) {
                    if (!visit()) return null
                    val v = Lit.variable(lit)
                    live[v] = true
                    val previous = root(v)
                    if (previous < 0) return null
                    parent[previous] = representative
                }
            }
        }
        val groups = linkedMapOf<Int, MutableList<Int>>()
        for (v in 0 until variables) {
            if (!visit()) return null
            if (live[v]) {
                val representative = root(v)
                if (representative < 0) return null
                groups.getOrPut(representative) { ArrayList() } += v
            }
        }
        return Partition(groups.values.map { it.toList() }, residual, conflict)
    }
}
