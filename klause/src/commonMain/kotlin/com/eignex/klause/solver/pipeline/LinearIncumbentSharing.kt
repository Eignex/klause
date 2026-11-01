package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.EffectiveSourceSemantics
import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.magnitudeBitLength

internal class LinearIncumbentSharing {
    class Key(
        private val source: SourceProofContext,
        offered: Sample,
        private val originalChecker: Any?,
    ) {
        private val semantics = offered.semantics
        private val frame = offered.coordinateFrame
        private val seed = offered.copy().also { it.witnessCertificate = null }
        val hasOriginalCheck: Boolean get() = originalChecker != null

        fun matches(source: SourceProofContext, offered: Sample, checker: Any?,
            token: Cancellation, budget: SourceLpBudget): Boolean {
            budget.observe(preparation = offered.numBoolVars.toLong() + offered.numIntVars + offered.numRealVars)
            return !token() && originalChecker === checker && sameSource(source, token, budget) &&
                samePoint(offered, offered.semantics, offered.coordinateFrame)
        }

        fun matches(other: Key, token: Cancellation, budget: SourceLpBudget): Boolean {
            budget.observe(preparation =
                other.seed.numBoolVars.toLong() + other.seed.numIntVars + other.seed.numRealVars)
            return !token() && originalChecker === other.originalChecker && sameSource(other.source, token, budget) &&
                samePoint(other.seed, other.semantics, other.frame)
        }

        private fun samePoint(offered: Sample, semantics: EffectiveSourceSemantics?, frame: Any?): Boolean =
            seed == offered && this.semantics == semantics && this.frame === frame

        private fun sameSource(right: SourceProofContext, token: Cancellation, budget: SourceLpBudget): Boolean {
            val left = source
            if (left === right) return true
            budget.observe(preparation = 2L * left.coordinateCount + 2L * right.coordinateCount +
                left.premiseCount + right.premiseCount +
                listOf(left.scope.condition, right.scope.condition).sumOf {
                    it.bools.size.toLong() + it.lower.size + it.upper.size + it.integerRows.size
                })
            return !token() && left.scope == right.scope && left.coordinates == right.coordinates &&
                left.premises == right.premises &&
                left.affineObjectiveAvailable == right.affineObjectiveAvailable && left.objective == right.objective &&
                left.objectiveConstant == right.objectiveConstant && left.semantics == right.semantics &&
                left.semanticsChecker === right.semanticsChecker
        }
    }

    private class Entry(val owner: Any, val key: Key, val point: Sample, val bytes: Long)
    private val entries = ArrayList<Entry>()

    fun find(key: Key, budget: SourceLpBudget, token: Cancellation): Sample? {
        val previous = entries.firstOrNull { it.key.matches(key, token, budget) } ?: return null
        return snapshot(previous.point, previous.bytes, budget, token)
    }

    fun remember(owner: Any, key: Key, point: Sample, budget: SourceLpBudget, token: Cancellation): Boolean {
        if (!key.hasOriginalCheck || entries.size >= MAX_POINTS) return false
        val bytes = retainedBytes(point, budget, token) ?: return false
        val retained = snapshot(point, bytes, budget, token) ?: return false
        entries += Entry(owner, key, retained, bytes)
        return true
    }

    fun release(owner: Any): Int {
        val work = entries.size
        entries.removeAll { it.owner === owner }
        return work
    }

    private fun retainedBytes(point: Sample, budget: SourceLpBudget, token: Cancellation): Long? {
        var bytes = 256L + 16L * (point.numBoolVars.toLong() + point.numIntVars + point.numRealVars)
        for (value in point.exactInts.orEmpty()) {
            if (token()) return null
            val bits = value.magnitudeBitLength().toLong()
            budget.observe(preparation = 1L + bits / 64L)
            bytes += 64L + bits / 4L
        }
        for (value in point.exactReals.orEmpty()) {
            if (token()) return null
            val bits = value.num.magnitudeBitLength().toLong() + value.den.magnitudeBitLength()
            budget.observe(preparation = 1L + bits / 64L)
            bytes += 64L + bits / 4L
        }
        return bytes.takeUnless { token() }
    }

    private fun snapshot(point: Sample, bytes: Long, budget: SourceLpBudget, token: Cancellation): Sample? {
        if (token()) return null
        val work = 1L + point.numBoolVars + point.numIntVars + point.numRealVars
        // Consumers can retain immutable scalar payloads after the producing owner has closed.
        if (budget.funding?.let { it.availableWork <= work + 2L || it.remainingAllocation <= bytes } == true) {
            return null
        }
        budget.observe(preparation = work)
        budget.funding?.charge(0L, bytes)
        val interpreted = point.semantics?.let(point::withSourceSemantics) ?: point
        return interpreted.copy().also { it.witnessCertificate = null }.takeUnless { token() }
    }

    private companion object {
        const val MAX_POINTS = 16
    }
}
