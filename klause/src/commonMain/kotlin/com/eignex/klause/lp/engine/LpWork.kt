package com.eignex.klause.lp.engine

// Operations are counted rather than timed, so a budget depends only on the model and the pivot path and behaves
// the same on a loaded machine. Each kernel charges the entries it touches (nnz for a solve through the
// factorization, m per eta application or dense pass), a reproducible proxy proportional to real cost.
internal class LpWork {
    private var limit = 0L

    var ops: Long = 0L
        private set

    fun add(n: Int) {
        add(n.toLong())
    }

    fun add(n: Long) {
        require(n >= 0)
        if (limit > 0L && n > limit - ops) throw LpWorkExhausted()
        ops = if (n > Long.MAX_VALUE - ops) Long.MAX_VALUE else ops + n
    }

    fun reset(limit: Long = 0L) {
        require(limit >= 0L)
        ops = 0L
        this.limit = limit
    }

    companion object {
        // Scales an operation count to the order of a second.
        const val SECONDS_PER_OP: Double = 2e-9
    }
}

internal class LpWorkExhausted : RuntimeException()
