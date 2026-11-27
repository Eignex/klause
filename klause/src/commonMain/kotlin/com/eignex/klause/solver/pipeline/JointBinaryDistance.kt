package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.optimization.LinearAuthority
import com.eignex.klause.lp.optimization.withLinearAuthority
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceObjective
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BigFraction
import kotlin.math.abs

internal class JointBinaryDistance(
    private val source: SourceProofContext,
    private val budget: SourceLpBudget,
) {
    data class Projection(val condition: ExactNeighborhoodCondition, val binaryPose: LongArray?)

    fun expand(
        condition: ExactNeighborhoodCondition, center: List<BigFraction>, stop: ExecutionControl, direct: Boolean,
    ): Projection? {
        val funding = budget.funding ?: return null
        if (stop() || condition.bools.isNotEmpty() || condition.lower != condition.upper ||
            center.size !in 1..256 || condition.lower.size !in (center.size - MAX_FREED + 1)..center.size) return null
        val work = 1L + 4L * center.size + source.coordinateCount + source.premiseCount
        val bytes = 256L + 192L * center.size + 128L * (source.coordinateCount + source.premiseCount)
        val previousGrant = funding.availableWork
        val before = funding.spent
        val allocated = funding.allocated
        if (funding.remainingWork <= work || funding.remainingAllocation <= bytes) return null
        funding.grant(MAX_WORK)
        try {
            budget.observe(preparation = work)
            funding.charge(0L, bytes)
            val costs = LinkedHashMap<SourceCoordinate, BigFraction>()
            var ones = 0
            for ((id, value) in center.withIndex()) {
                if (stop() || (value != BigFraction.ZERO && value != BigFraction.ONE)) return null
                costs[SourceCoordinate(SourceCoordinate.Kind.Integer, id)] =
                    if (value == BigFraction.ZERO) BigFraction.ONE else BigFraction.MINUS_ONE.also { ones++ }
            }
            // The distance objective owns its proof scope; only the point steers the regional query.
            val revision = SourceRevision("${source.scope.revision.identity}/distance", "joint-binary-distance-v1")
            val scope = source.scope.copy(revision = revision,
                objective = SourceObjective("${revision.identity}/objective", ObjectiveSense.MINIMIZE))
            val auxiliary = SourceProofContext(scope, source.coordinates, source.premises, costs,
                BigFraction.ofLong(ones.toLong())).withLinearAuthority()
            val token = stop or ExecutionControl {
                funding.allocated - allocated >= MAX_ALLOCATION || funding.exhausted
            }
            val point = LinearAuthority(budget, retainLp = true).use {
                it.advance(auxiliary, token).takeUnless { result -> result.paused }?.relaxedPoint
            } ?: return null
            if (token() || funding.paused) return null
            val pins = condition.lower.toMutableMap()
            val poseBytes = 64L + Long.SIZE_BYTES * center.size.toLong()
            val pose = if (direct && funding.availableWork > center.size &&
                funding.remainingAllocation > poseBytes && MAX_ALLOCATION - (funding.allocated - allocated) > poseBytes) {
                budget.observe(preparation = center.size.toLong())
                funding.charge(0L, poseBytes)
                LongArray(center.size)
            } else null
            for ((id, target) in center.withIndex()) {
                if (token()) return null
                val value = point[SourceCoordinate(SourceCoordinate.Kind.Integer, id)] ?: return null
                if (!value.isFinite() || value !in -MOVEMENT..(1 + MOVEMENT)) return null
                val moved = abs(value - target.toDouble()) > MOVEMENT
                if (moved) pins.remove(id)
                if (pose != null) pose[id] = if ((target == BigFraction.ONE) != moved) 1L else 0L
            }
            if (pins.size == condition.lower.size || center.size - pins.size > MAX_FREED) return null
            return Projection(condition.copy(lower = pins, upper = pins), pose)
        } finally {
            funding.grant((previousGrant - (funding.spent - before)).coerceAtLeast(0L))
        }
    }

    private companion object {
        const val MOVEMENT = 1e-7
        const val MAX_FREED = 32
        const val MAX_WORK = 20_000_000L
        const val MAX_ALLOCATION = 32L * 1024 * 1024
    }
}
