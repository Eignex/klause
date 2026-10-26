package com.eignex.klause.localsearch

import com.eignex.klause.solver.Sample

internal enum class RepairEndpoint {
    PolicyLocalOptimum, FeasibleStall, Restarted, Repaired, Unresolved, Censored,
}

internal class RepairTrace(
    val descentRule: String,
    val maxAttempts: Int = 32,
    val maxCoordinates: Int = 4096,
) {
    init {
        require(descentRule.isNotBlank())
        require(maxAttempts in 1..64 && maxCoordinates in 1..4096)
    }

    data class Frame(val stage: String, val sample: Sample, val cost: Long?)

    class Attempt(
        val id: Int,
        val owner: String,
        val seed: Long?,
        val center: Sample?,
        val requestedBools: IntArray,
        val requestedInts: IntArray,
        val effectiveBools: IntArray,
        val effectiveInts: IntArray,
    ) {
        val frames = mutableListOf<Frame>()
        var endpoint: RepairEndpoint? = null
            internal set
    }

    private val retained = mutableListOf<Attempt>()
    val attempts: List<Attempt> get() = retained.toList()
    var declined = 0L
        private set
    var coordinateReads = 0L
        private set

    fun admits(bools: Int, ints: Int, reals: Int): Boolean {
        if (retained.size < maxAttempts && bools.toLong() + ints + reals <= maxCoordinates) return true
        declined++
        return false
    }

    fun begin(
        owner: String,
        seed: Long?,
        before: Sample,
        center: Sample?,
        requestedBools: IntArray = intArrayOf(),
        requestedInts: IntArray = intArrayOf(),
        effectiveBools: IntArray = intArrayOf(),
        effectiveInts: IntArray = intArrayOf(),
    ): Attempt? {
        val coordinates = before.numBoolVars.toLong() + before.numIntVars + before.numRealVars
        val scopeSize = requestedBools.size.toLong() + requestedInts.size + effectiveBools.size + effectiveInts.size
        val centerSize = center?.let { it.numBoolVars.toLong() + it.numIntVars + it.numRealVars } ?: 0L
        if (retained.size >= maxAttempts || coordinates > maxCoordinates || centerSize > maxCoordinates ||
            scopeSize > 4L * maxCoordinates
        ) {
            declined++
            return null
        }
        return Attempt(retained.size, owner, seed, center, requestedBools.copyOf(), requestedInts.copyOf(),
            effectiveBools.copyOf(), effectiveInts.copyOf()).also {
            retained.add(it)
            record(it, "before", before)
        }
    }

    fun record(attempt: Attempt?, stage: String, sample: Sample, cost: Long? = null) {
        if (attempt == null || attempt.endpoint != null) return
        check(attempt.frames.size < 6) { "repair trace stage limit exceeded" }
        require(sample.numBoolVars.toLong() + sample.numIntVars + sample.numRealVars <= maxCoordinates)
        attempt.frames.add(Frame(stage, sample, cost))
        coordinateReads += sample.numBoolVars.toLong() + sample.numIntVars + sample.numRealVars
    }

    fun finish(attempt: Attempt?, endpoint: RepairEndpoint, sample: Sample? = null, cost: Long? = null) {
        if (attempt == null || attempt.endpoint != null) return
        if (sample != null) record(attempt, "endpoint", sample, cost)
        attempt.endpoint = endpoint
    }
}
