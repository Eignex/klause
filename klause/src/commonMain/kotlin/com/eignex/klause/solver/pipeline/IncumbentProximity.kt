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
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.bigIntOf
import com.eignex.util.numbers.magnitudeBitLength

internal object IncumbentProximity {
    fun radius(integers: Int): Int = minOf(integers, ((integers + 9) / 10).coerceIn(4, 32))

    class AdvisoryCenter(val sample: Sample, val value: BigFraction)

    fun advisoryCenter(source: SourceProofContext, sample: Sample, budget: SourceLpBudget,
        cancellation: ExecutionControl): AdvisoryCenter? {
        if (sample.numBoolVars != 0 || sample.numIntVars !in 1..4096 || sample.numRealVars == 0 ||
            sample.exactReals != null || sample.algebraicReals != null || sample.symbolicReals != null ||
            source.coordinateCount > 4096 || source.coordinateCount != sample.numIntVars + sample.numRealVars ||
            cancellation()) return null
        val funding = budget.funding ?: return null
        val work = 32L * (source.coordinateCount + 1L)
        val bytes = 256L * (source.coordinateCount + 1L)
        if (funding.availableWork <= work || funding.remainingAllocation <= bytes) return null
        budget.observe(preparation = work)
        funding.charge(0L, bytes)
        val objective = source.objective
        var value = source.objectiveConstant
        for (coordinate in source.coordinates) {
            if (cancellation()) return null
            val scalar = when (coordinate.kind) {
                SourceCoordinate.Kind.Integer -> {
                    if (coordinate.id !in 0 until sample.numIntVars) return null
                    val integer = sample.exactIntValue(coordinate.id)
                    if (integer.signum() < 0 || integer > BIG_ONE) return null
                    BigFraction.of(integer, BIG_ONE)
                }
                SourceCoordinate.Kind.Real -> {
                    if (coordinate.id !in 0 until sample.numRealVars) return null
                    BigFraction.ofDouble(sample.approximateRealValue(coordinate.id)) ?: return null
                }
                else -> return null
            }
            val cost = objective[coordinate] ?: continue
            val bits = scalar.num.magnitudeBitLength().toLong() + scalar.den.magnitudeBitLength() +
                cost.num.magnitudeBitLength() + cost.den.magnitudeBitLength() +
                value.num.magnitudeBitLength() + value.den.magnitudeBitLength()
            val scalarWork = 1L + bits * bits / 64L
            if (bits > 4096L || funding.availableWork <= scalarWork ||
                funding.remainingAllocation <= 128L + bits) return null
            budget.observe(preparation = scalarWork)
            funding.charge(0L, 128L + bits)
            value += cost * scalar
        }
        return AdvisoryCenter(sample, value).takeUnless { cancellation() || funding.exhausted }
    }

    fun candidateStats(stats: SolveStats, privateCandidate: Boolean): SolveStats {
        if (!privateCandidate) return stats
        return stats.copy(smt = stats.smt.copy(completeSearchStages =
            stats.smt.completeSearchStages.orEmpty() + ("incumbentReusePrivateCandidate" to 1L)))
    }

    fun accepts(bounds: IntBounds, integers: Int): Boolean = integers in 1..4096 &&
        (0 until integers).all {
            bounds.hasLower(it) && bounds.hasUpper(it) && bounds.lower(it) >= 0L && bounds.upper(it) <= 1L
        }

    fun prepare(source: SourceProofContext, center: Sample, cutoff: BigFraction,
        budget: SourceLpBudget, cancellation: ExecutionControl,
        relativeImprovement: Boolean = true): SourceProofContext? {
        val funding = budget.funding ?: return null
        if (cancellation() || center.numBoolVars != 0 || center.numIntVars !in 1..4096 ||
            !source.affineObjectiveAvailable || source.scope.objective == null) return null
        val scan = 1L + source.premiseCount
        if (funding.availableWork <= scan) return null
        budget.observe(preparation = scan)
        var entries = 1L + source.coordinateCount + source.premiseCount +
            source.scope.condition.coordinateRestrictionCount
        val scanned = source.allPremises { _, premise ->
            if (cancellation()) false else {
                entries += premise.inequality.supportSize + premise.guardCount + premise.integerPinCount
                true
            }
        }
        if (!scanned) return null
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
            val coordinate = SourceCoordinate(SourceCoordinate.Kind.Integer, id)
            costs[coordinate] =
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
        val originalConstant = if (maximize) source.objectiveConstant.negated() else source.objectiveConstant
        val variableValue = cutoff - originalConstant
        var improvement = requireNotNull(scale) * BigFraction.of(BIG_ONE, bigIntOf(1000))
        if (relativeImprovement) {
            val magnitude = if (variableValue.signum() < 0) variableValue.negated() else variableValue
            val relative = magnitude * BigFraction.of(BIG_ONE, bigIntOf(100))
            if (relative > improvement) improvement = relative
        }
        if (gap != null && gap > improvement) improvement = gap
        val target = variableValue - improvement
        if (target.num.magnitudeBitLength() + target.den.magnitudeBitLength() > 4096 || cancellation()) return null
        val premises = source.premises.toMutableMap()
        val key = "incumbent-proximity-target"
        val radiusKey = "incumbent-proximity-radius"
        if (key in premises || radiusKey in premises) return null
        premises[key] = SourcePremise(SourceInequality(original, target))
        val radius = radius(center.numIntVars)
        premises[radiusKey] =
            SourcePremise(SourceInequality(costs, BigFraction.ofLong(radius.toLong()) - constant))
        val scope = source.scope.copy(
            revision = source.scope.revision.copy(identity = source.scope.revision.identity + "/incumbent-proximity"),
            objective = SourceObjective("${source.scope.objective.identity}/proximity/$bits/$radius/$target",
                source.scope.objective.sense),
        )
        val coordinates = source.coordinates
        if (coordinates.count { it.kind == SourceCoordinate.Kind.Integer } != center.numIntVars ||
            costs.keys.any { it !in coordinates }) return null
        // This objective and improvement row belong only to the primal search, never parent coverage.
        return SourceProofContext(scope, coordinates, premises, source.objective, source.objectiveConstant)
            .takeUnless { cancellation() || funding.exhausted }
    }
}
