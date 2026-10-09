package com.eignex.klause.solver.search

internal class LearnedClauseHandle(val owner: LearnedClauseActivity, var lbd: Int, epoch: Long) {
    var activity = 0.0
    var touched = epoch
}

internal class LearnedClauseActivity(private val policy: SearchLearnedDbPolicy) {
    private var increment = 1.0
    private val handles = ArrayList<LearnedClauseHandle>()
    var epoch = 0L
        private set
    var bumps = 0L
        private set
    var lbdImprovements = 0L
        private set

    fun add(lbd: Int): LearnedClauseHandle = LearnedClauseHandle(this, lbd, epoch).also {
        handles.add(it)
        bump(it)
    }

    fun bump(handle: LearnedClauseHandle?) {
        if (handle == null || handle.owner !== this) return
        bumps++
        handle.activity += increment
        handle.touched = epoch
        if (handle.activity > RESCALE_AT) rescale()
    }

    fun analyzed(explanation: SearchExplanation, lbd: () -> Int) {
        val handle = explanation.learnedHandle ?: return
        if (handle.owner !== this) return
        bump(handle)
        if (policy == SearchLearnedDbPolicy.Tiered) {
            val improved = lbd()
            if (improved < handle.lbd) {
                handle.lbd = improved
                lbdImprovements++
            }
        }
    }

    fun decay() {
        increment /= DECAY
        if (increment > RESCALE_AT) rescale()
    }

    fun reduced(survivors: List<LearnedClauseHandle>) {
        handles.clear()
        handles.addAll(survivors)
        epoch++
    }

    private fun rescale() {
        for (handle in handles) handle.activity *= RESCALE_BY
        increment *= RESCALE_BY
    }

    private companion object {
        const val DECAY = 0.999
        const val RESCALE_AT = 1e20
        const val RESCALE_BY = 1e-20
    }
}
