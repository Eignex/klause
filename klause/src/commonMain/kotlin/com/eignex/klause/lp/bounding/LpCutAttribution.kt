package com.eignex.klause.lp.bounding

import com.eignex.klause.lp.cut.CutContext
import com.eignex.klause.lp.cut.CutSeparator
import com.eignex.klause.lp.cut.SharedCut
import com.eignex.klause.lp.engine.Cut
import com.eignex.klause.lp.engine.LpDeductionCertificate
import com.eignex.klause.lp.engine.hasIntegralObjective
import com.eignex.klause.lp.relaxation.LpRelaxation
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.result.LpPhaseStats

internal fun LpEngine.constructCuts(separator: CutSeparator, context: CutContext): List<Cut> =
    sink.lp.cutLifecycle.measure(separator.family, "CONSTRUCTION") {
        separator.separate(context).map { it.withFamily(separator.family) }.also { cuts ->
            sink.lp.cutLifecycle.event(separator.family, "CONSTRUCTION", "CANDIDATE", cuts.size.toLong())
        }
    }

internal fun LpEngine.mapGlobalCut(cut: Cut, relaxation: LpRelaxation): Cut? =
    sink.lp.cutLifecycle.measure(cut.family, "VERIFICATION") {
        SharedCut.fromCut(cut, relaxation)?.toCut(relaxation).also {
            sink.lp.cutLifecycle.event(cut.family, "VERIFICATION", if (it == null) "DECLINED" else "MAPPED")
        }
    }

internal fun LpEngine.activateCutAttribution(relaxation: LpRelaxation) {
    val families = relaxation.sourceMap?.parentFamilies().orEmpty()
    sink.lp.cutLifecycle.families = families.values.toSet()
    noteNodeOverhead(families.size.toLong())
}

internal fun LpEngine.observeCutUse(
    relaxation: LpRelaxation,
    certificate: LpDeductionCertificate?,
    outcome: String,
) {
    if (certificate == null) return
    val families = relaxation.sourceMap?.parentFamilies() ?: return
    for ((row, family) in families) {
        if (params.cancellation()) break
        if (certificate.dualNonzeroRow(row)) sink.lp.cutLifecycle.event(family, "CHECKED_USE", outcome)
    }
    noteNodeOverhead(families.size.toLong())
}

internal fun LpEngine.observeCutRayUse(relaxation: LpRelaxation, ray: LongArray) {
    val families = relaxation.sourceMap?.parentFamilies() ?: return
    for ((row, family) in families) {
        if (params.cancellation()) break
        if (row in ray.indices && ray[row] != 0L) sink.lp.cutLifecycle.event(family, "CHECKED_USE", "CONFLICT_PRUNE")
    }
    noteNodeOverhead(families.size.toLong())
}

internal fun LpEngine.observeAcceptedCutBoundUse(
    relaxation: LpRelaxation,
    certificate: LpDeductionCertificate?,
    duals: DoubleArray,
    required: BigFraction,
    outcome: String,
) {
    val certified = certificate?.let {
        if (relaxation.model.hasIntegralObjective()) {
            it.objectiveBoundCeil(relaxation.objectiveConstant)?.let(BigFraction::ofLong)
        } else it.exactObjectiveBound() + BigFraction.ofLong(relaxation.objectiveConstant)
    }
    if (certified != null && certified >= required) {
        observeCutUse(relaxation, certificate, outcome)
        return
    }
    val families = relaxation.sourceMap?.parentFamilies() ?: return
    for ((row, family) in families) {
        if (params.cancellation()) break
        if (row in duals.indices && duals[row] != 0.0) {
            sink.lp.cutLifecycle.event(family, "CHECKED_USE", "SAFE_$outcome")
        }
    }
    noteNodeOverhead(families.size.toLong())
}

internal fun LpEngine.observeCutFill(fill: Double, rows: Int) {
    sink.lp.cutLifecycle.shared("FILL", LpPhaseStats(calls = 1L,
        outcomes = mapOf("SOLVE_MAX_RATIO_MICROS_SUM" to (fill * 1_000_000.0).toLong(), "ROWS" to rows.toLong())))
}
