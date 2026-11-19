package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceInequality
import com.eignex.klause.solver.evidence.SourceObjective
import com.eignex.klause.solver.evidence.SourcePremise
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.objective.gapAllowance
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.bigIntOf
import com.eignex.util.numbers.magnitudeBitLength

internal object IncumbentProximity {
    fun accepts(bounds: IntBounds, integers: Int): Boolean = integers in 1..4096 &&
        (0 until integers).all { bounds.hasLower(it) && bounds.hasUpper(it) &&
            bounds.lower(it) >= 0L && bounds.upper(it) <= 1L }

    fun prepare(source: SourceProofContext, center: Sample, cutoff: BigFraction,
        budget: SourceLpBudget, cancellation: ExecutionControl): SourceProofContext? {
        val funding = budget.funding ?: return null
        if (cancellation() || center.numBoolVars != 0 || center.numIntVars !in 1..4096 ||
            !source.affineObjectiveAvailable || source.scope.objective == null) return null
        val scan = 1L + source.premiseCount
        if (funding.availableWork <= scan) return null
        budget.observe(preparation = scan)
        var entries = 1L + source.coordinateCount + source.premiseCount
        var interrupted = false
        source.forEachPremise { _, premise ->
            if (cancellation()) interrupted = true
            if (!interrupted) entries += premise.inequality.supportSize + premise.guardCount + premise.integerPinCount
        }
        if (interrupted) return null
        val work = 16L * entries
        val bytes = 256L * entries
        if (funding.availableWork <= work || funding.remainingAllocation <= bytes) return null
        budget.observe(preparation = work)
        funding.charge(0L, bytes)
        val costs = linkedMapOf<SourceCoordinate, BigFraction>()
        val bits = StringBuilder(center.numIntVars)
        var constant = BigFraction.ZERO
        for (id in 0 until center.numIntVars) {
            if (cancellation()) return null
            val value = center.exactIntValue(id)
            if (value.signum() < 0 || value > BIG_ONE) return null
            val one = value.signum() > 0
            costs[SourceCoordinate(SourceCoordinate.Kind.Integer, id)] =
                if (one) BigFraction.ONE.negated() else BigFraction.ONE
            bits.append(if (one) '1' else '0')
            if (one) constant += BigFraction.ONE
        }
        val maximize = source.scope.objective.sense == ObjectiveSense.MAXIMIZE
        val original: Map<SourceCoordinate, BigFraction> = source.objective.mapValues { (_, value) ->
            if (maximize) value.negated() else value
        }
        if (original.isEmpty()) return null
        var scale: BigFraction? = null
        for (value in original.values + listOf(cutoff, source.objectiveConstant)) {
            if (cancellation()) return null
            val width = value.num.magnitudeBitLength().toLong() + value.den.magnitudeBitLength()
            val scalarWork = 1L + width * width / 64L
            val scalarBytes = 64L + 8L * width
            if (width > 4096 || funding.availableWork <= scalarWork ||
                funding.remainingAllocation <= scalarBytes) return null
            budget.observe(preparation = scalarWork)
            funding.charge(0L, scalarBytes)
        }
        for (value in original.values) {
            val magnitude: BigFraction = if (value.signum() < 0) value.negated() else value
            if (scale == null || magnitude < scale) scale = magnitude
        }
        val gap = source.semantics?.effective?.optimalityTolerance?.gapAllowance(cutoff)
        val improvement = gap?.takeIf { it.signum() > 0 }
            ?: (requireNotNull(scale) * BigFraction.of(BIG_ONE, bigIntOf(1000)))
        val originalConstant = if (maximize) source.objectiveConstant.negated() else source.objectiveConstant
        val target = cutoff - improvement - originalConstant
        if (target.num.magnitudeBitLength() + target.den.magnitudeBitLength() > 4096 || cancellation()) return null
        val premises = source.premises.toMutableMap()
        val key = "incumbent-proximity-target"
        if (key in premises) return null
        premises[key] = SourcePremise(SourceInequality(original, target))
        val scope = source.scope.copy(
            revision = source.scope.revision.copy(identity = source.scope.revision.identity + "/incumbent-proximity"),
            objective = SourceObjective("${source.scope.objective.identity}/proximity/$bits/$target",
                ObjectiveSense.MINIMIZE),
        )
        val coordinates = source.coordinates
        if (coordinates.count { it.kind == SourceCoordinate.Kind.Integer } != center.numIntVars ||
            costs.keys.any { it !in coordinates }) return null
        // This objective and improvement row belong only to the primal search, never parent coverage.
        return SourceProofContext(scope, coordinates, premises, costs, constant)
            .takeUnless { cancellation() || funding.exhausted }
    }
}
