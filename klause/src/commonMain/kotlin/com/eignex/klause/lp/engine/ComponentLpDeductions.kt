package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation

internal class ComponentLpDeductions(
    val primary: LpDeductionCertificate,
    val alternatives: List<LpDeductionCertificate>,
)

internal class ComponentLpDeductionCache(size: Int) {
    private class Entry(
        val result: FloatLpResult,
        val policy: LpCertificationPolicy,
        val scaleBits: Int,
        val withAlternatives: Boolean,
        val deductions: ComponentLpDeductions,
        val bytes: Long,
    )

    private val entries = arrayOfNulls<Entry>(size)
    private var retainedBytes = 0L

    fun invalidate(index: Int) {
        retainedBytes -= entries[index]?.bytes ?: 0L
        entries[index] = null
    }

    fun clear() {
        entries.fill(null)
        retainedBytes = 0L
    }

    @Suppress("LongParameterList")
    fun certify(
        model: LpModel,
        parts: List<LpNeighborhood>,
        isolated: IntArray,
        results: List<FloatLpResult?>,
        duals: DoubleArray,
        observer: LpCertificationObserver?,
        policy: LpCertificationPolicy,
        cancellation: Cancellation,
        withAlternatives: Boolean,
        sharedMeter: MixedCertificateMeter?,
    ): ComponentLpDeductions? {
        val state = model.exactState ?: return null
        val meter = sharedMeter ?: MixedCertificateMeter(cancellation)
        val initialWork = meter.work
        val timer = LpPhaseTimer(observer, LpSolvePhase.NODE_DEDUCTION)
        var childWork = 0L
        var accepted = false
        try {
            meter.poll()
            meter.protect(retainedBytes)
            meter.reserve(model.numVars.toLong() * 8L + parts.size * 32L + 256L)
            for (column in 0 until model.numVars) {
                meter.step()
                if (!state.model.column(column).integral) return null
            }
            if (state.model.objective.scale.value != BigFraction.ONE ||
                !state.model.objective.externalConstant.value.isZero || !model.hasIntegralObjective()
            ) return null
            if (duals.size != model.m) return null
            val scaleBits = dualRoundingScaleBits(duals, inspect = meter::step) ?: return null
            meter.poll()
            val children = ArrayList<ComponentLpDeductions>(parts.size)
            for (index in parts.indices) {
                meter.step()
                val part = parts[index]
                val result = results[index] ?: return null
                val cached = entries[index]?.takeIf {
                    it.result === result && it.policy === policy && it.scaleBits == scaleBits &&
                        it.withAlternatives == withAlternatives && it.deductions.primary.belongsTo(part.model)
                }
                if (cached != null) {
                    children += cached.deductions
                    continue
                }
                val beforeAllocation = meter.allocation
                val beforeWork = meter.work
                val alternatives = ArrayList<LpDeductionCertificate>(2)
                var primary = try {
                    nodeDualCertificate(
                        part.model, result.duals, cancellation, observer,
                        onAdditionalCertificate = if (withAlternatives) { { alternatives.add(it) } } else null,
                        sharedMeter = meter, scaleBits = scaleBits, requireIntegerPrimary = true,
                    )
                } finally {
                    childWork += meter.work - beforeWork
                } ?: return null
                for (candidate in alternatives.indices) {
                    if (meter.less(primary.exactObjectiveBound(), alternatives[candidate].exactObjectiveBound())) {
                        val previous = primary
                        primary = alternatives[candidate]
                        alternatives[candidate] = previous
                    }
                }
                meter.reserve(128L + alternatives.size * 8L)
                val deductions = ComponentLpDeductions(primary, alternatives)
                val bytes = meter.allocation - beforeAllocation
                val retained = retainedBytes - (entries[index]?.bytes ?: 0L)
                if (bytes <= MAX_CACHE_BYTES - retained) {
                    entries[index] = Entry(result, policy, scaleBits, withAlternatives, deductions, bytes)
                    retainedBytes = retained + bytes
                }
                children += deductions
            }
            val owners = IntArray(model.numVars) { -1 }
            val locals = IntArray(model.numVars)
            var bound = meter.read(model.exactConstant())
            for (index in parts.indices) {
                val part = parts[index]
                for (column in part.cols.indices) {
                    meter.step()
                    owners[part.cols[column]] = index
                    locals[part.cols[column]] = column
                }
                for (row in part.rows.indices) {
                    meter.step()
                    val column = model.slackCol(part.rows[row])
                    owners[column] = index
                    locals[column] = part.model.slackCol(row)
                }
                bound = meter.add(bound, meter.add(children[index].primary.exactObjectiveBound(),
                    part.model.exactConstant().negated()))
            }
            for (column in isolated) {
                meter.step()
                val cost = meter.read(model.exactCost(column))
                val endpoint = when {
                    cost.signum() > 0 -> model.exactBounds(column).lower ?: return null
                    cost.signum() < 0 -> model.exactBounds(column).upper ?: return null
                    else -> null
                }
                if (endpoint != null) bound = meter.add(bound, meter.multiply(cost, endpoint.number.value))
            }
            val primary = CompositeDeduction(state, parts, children, owners, locals, bound)
            val alternatives = ArrayList<LpDeductionCertificate>()
            for (index in children.indices) for (alternative in children[index].alternatives) {
                meter.reserve(160L)
                val alternativeBound = meter.add(
                    meter.add(bound, children[index].primary.exactObjectiveBound().negated()),
                    alternative.exactObjectiveBound(),
                )
                alternatives += CompositeDeduction(state, parts, children, owners, locals, alternativeBound,
                    index, alternative)
            }
            meter.poll()
            accepted = true
            return ComponentLpDeductions(primary, alternatives)
        } catch (_: LpProjectionStop) {
            return null
        } finally {
            timer.finish("COMPONENT_${if (accepted) "COMPLETE" else "DECLINED"}", meter.work - initialWork - childWork)
            observer?.observe(LpCertifier.INTEGER, accepted,
                LpCertifierCost.Metered(meter.work - initialWork - childWork))
        }
    }

    private companion object {
        const val MAX_CACHE_BYTES = 8L * 1024L * 1024L
    }
}

private class CompositeDeduction(
    private val authority: LpExactState,
    private val parts: List<LpNeighborhood>,
    private val children: List<ComponentLpDeductions>,
    private val owners: IntArray,
    private val locals: IntArray,
    private val bound: BigFraction,
    private val alternativeIndex: Int = -1,
    private val alternative: LpDeductionCertificate? = null,
) : LpDeductionCertificate {
    private fun child(index: Int): LpDeductionCertificate =
        if (index == alternativeIndex) checkNotNull(alternative) else children[index].primary

    override fun belongsTo(model: LpModel, observer: LpCertificationObserver?): Boolean = model.exactState === authority
    override fun usesRationalArithmetic(): Boolean = true
    override fun integralObjectiveRows(): IntArray = IntArray(0)
    override fun fixingColumns(): IntArray? = parts.getOrNull(alternativeIndex)?.cols
    override fun exactMultipliers(): List<BigFraction> = List(authority.model.m, ::exactMultiplier)
    override fun exactMultiplier(row: Int): BigFraction {
        val column = authority.model.n + row
        val index = owners[column]
        return child(index).exactMultiplier(locals[column] - parts[index].model.n)
    }
    override fun exactReducedCost(col: Int): BigFraction = owners[col].let { index ->
        if (index < 0) authority.model.objective.cost(col).value else child(index).exactReducedCost(locals[col])
    }
    override fun exactObjectiveBound(): BigFraction = bound
    override fun dualNonzeroRow(row: Int): Boolean = !exactMultiplier(row).isZero
    override fun reducedCostSign(col: Int): Int = exactReducedCost(col).signum()
    override fun objectiveBoundCeil(extraConstant: Long): Long? = (bound + BigFraction.ofLong(extraConstant)).ceilLong()
    override fun improvingGapNonNegative(improvingMax: Long, sourceConstant: Long): Boolean =
        gap(improvingMax, sourceConstant) >= BigFraction.ZERO
    override fun fixSteps(col: Int, improvingMax: Long, sourceConstant: Long): Long? =
        fixStepsForGap(col, gap(improvingMax, sourceConstant))

    private fun gap(improvingMax: Long, sourceConstant: Long): BigFraction =
        BigFraction.ofLong(improvingMax) - BigFraction.ofLong(sourceConstant) - bound
}
