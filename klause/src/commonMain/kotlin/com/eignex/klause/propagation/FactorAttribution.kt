package com.eignex.klause.propagation

import com.eignex.klause.factor.arithmetic.LinearPropagator
import com.eignex.klause.factor.arithmetic.ReifiedLinearPropagator
import com.eignex.klause.factor.arithmetic.WideLinearPropagator
import com.eignex.klause.factor.arithmetic.WideReifiedLinearPropagator
import com.eignex.klause.factor.bool.ClausePropagator
import com.eignex.klause.factor.bool.PseudoBooleanPropagator
import com.eignex.klause.factor.global.AffineDistinctPropagator
import com.eignex.klause.factor.global.AllDifferentPropagator
import com.eignex.klause.factor.scheduling.CumulativePropagator
import com.eignex.klause.factor.scheduling.DiffnPropagator
import com.eignex.klause.factor.scheduling.DisjunctivePropagator
import com.eignex.klause.factor.table.MddPropagator
import com.eignex.klause.factor.table.TablePropagator

internal enum class FactorFamily { Clause, Pb, Linear, Table, Mdd, Distinct, Scheduling, Integer, Other, Unattributed }

internal fun Propagator.propagationFamily(): FactorFamily = when (this) {
    is ClausePropagator -> FactorFamily.Clause
    is PseudoBooleanPropagator -> FactorFamily.Pb
    is LinearPropagator, is ReifiedLinearPropagator, is WideLinearPropagator,
    is WideReifiedLinearPropagator -> FactorFamily.Linear
    is TablePropagator -> FactorFamily.Table
    is MddPropagator -> FactorFamily.Mdd
    is AllDifferentPropagator, is AffineDistinctPropagator -> FactorFamily.Distinct
    is CumulativePropagator, is DiffnPropagator, is DisjunctivePropagator -> FactorFamily.Scheduling
    is LearnedIntegerPropagator -> FactorFamily.Integer
    else -> FactorFamily.Other
}

internal class FactorAttribution(private val capacity: Int = 1024) {
    private val counts = Array(FactorFamily.entries.size) { LongArray(Metric.entries.size) }
    private val reasons = HashMap<IntArray, Int>()
    private val reasonRing = arrayOfNulls<IntArray>(capacity)
    private var nextReason = 0
    private val learned = HashMap<Propagator, Int>()
    var analysisFamilies = 0
        private set
    var admissionFamilies = 0

    enum class Metric {
        Fires, Work, Nanos, Deductions, Conflicts, Interrupted,
        EagerReasons, ConflictReasonAttempts, ConflictReasonUnavailable,
        LazyAttempts, LazyCompleted, LazyInterrupted, ReasonWork, ReasonNanos,
        Support, MaxSupport, MemoHits, UipReasons, UipSupport, Learned, Consumed, UseEvents, LaterUseEvents, Untracked,
        RefinementAttempts, RefinementWork, RefinementNanos, RefinementSupport, RefinementAccepted,
    }

    init { require(capacity in 1..1024) }

    fun note(mask: Int, metric: Metric, value: Long = 1) {
        for (family in FactorFamily.entries) if (mask and (1 shl family.ordinal) != 0) {
            val row = counts[family.ordinal]
            val i = metric.ordinal
            row[i] = if (metric == Metric.MaxSupport) maxOf(row[i], value)
            else row[i] + value.coerceIn(0, Long.MAX_VALUE - row[i])
        }
    }

    fun family(propagator: Propagator): Int = learned[propagator] ?: (1 shl propagator.propagationFamily().ordinal)

    fun remember(reason: IntArray?, mask: Int, eager: Boolean = false) {
        if (reason == null) return
        if (eager) support(mask, Metric.EagerReasons, reason.size)
        if (reason.size > 64) {
            note(mask, Metric.Untracked)
            return
        }
        if (reasons.containsKey(reason)) return
        reasonRing[nextReason]?.let { reasons.remove(it) }
        reasonRing[nextReason] = reason
        nextReason = (nextReason + 1) % capacity
        reasons[reason] = mask
    }

    fun reasonFamily(reason: IntArray): Int = reasons[reason] ?: (1 shl FactorFamily.Unattributed.ordinal)

    fun support(mask: Int, metric: Metric, size: Int) {
        note(mask, metric)
        note(mask, Metric.Support, size.toLong())
        note(mask, Metric.MaxSupport, size.toLong())
    }

    fun beginAnalysis() { analysisFamilies = 0 }

    fun selectFamilies(mask: Int) { analysisFamilies = mask }

    fun participate(mask: Int, size: Int) {
        analysisFamilies = analysisFamilies or mask
        note(mask, Metric.UipReasons)
        note(mask, Metric.UipSupport, size.toLong())
    }

    fun admit(propagator: Propagator) {
        if (admissionFamilies == 0) return
        if (learned.size == capacity) {
            note(admissionFamilies, Metric.Untracked)
            return
        }
        learned[propagator] = admissionFamilies
        note(admissionFamilies, Metric.Learned)
    }

    fun consumed(propagator: Propagator) {
        note(learned[propagator] ?: (1 shl FactorFamily.Unattributed.ordinal), Metric.Consumed)
    }

    fun used(mask: Int) {
        note(mask, Metric.UseEvents)
        if (admissionFamilies == 0) note(mask, Metric.LaterUseEvents)
    }

    fun used(propagator: Propagator) {
        used(learned[propagator] ?: (1 shl FactorFamily.Unattributed.ordinal))
    }

    fun retire(propagator: Propagator) { learned.remove(propagator) }

    fun snapshot(): Map<String, Long> = buildMap {
        for (family in FactorFamily.entries) for (metric in Metric.entries) {
            val value = counts[family.ordinal][metric.ordinal]
            if (value != 0L) put("factor${family.name}${metric.name}", value)
        }
        put("factorTrackedReasons", reasons.size.toLong())
        put("factorTrackedLearning", learned.size.toLong())
    }
}

internal fun PropagationState.attributionFamily(fid: Int): Int =
    if (nativeEngine != null) 1 shl FactorFamily.Clause.ordinal
    else if (fid >= 0) factorAttribution?.family(factorAt(fid)) ?: 0
    else 1 shl FactorFamily.Unattributed.ordinal

internal fun PropagationState.attributeReason(reason: IntArray?) {
    factorAttribution?.let { it.remember(reason, attributionFamily(currentFactor), !isLazyReason(reason)) }
}
