@file:OptIn(ExperimentalAtomicApi::class)

package com.eignex.klause.lp.relaxation

import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.solver.PreparedFactorProjections
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * LP-owned template retention for one selected source view. Compatible views share a namespace token
 * and one atomic publication slot per source/finite scope; [rowScope] additionally proves that the
 * view's ordered factor identities match the view it was inherited from.
 */
internal class LpPreparationCache private constructor(
    private val namespace: LpPreparationNamespace,
    private val rowScope: Any,
    private val sourceSlot: AtomicReference<LpPreparationState?>,
    private val finiteSlot: AtomicReference<LpPreparationState?>,
) {
    constructor() : this(
        LpPreparationNamespace(), Any(),
        AtomicReference<LpPreparationState?>(null), AtomicReference<LpPreparationState?>(null),
    )

    /**
     * Shares this namespace and its publication slots with a compatible view. Only a view whose
     * ordered factor identities were checked against this one keeps the row layout token.
     */
    fun inherited(sameRowLayout: Boolean): LpPreparationCache =
        LpPreparationCache(namespace, if (sameRowLayout) rowScope else Any(), sourceSlot, finiteSlot)

    private fun slot(problem: Problem): AtomicReference<LpPreparationState?> =
        if (problem is BakedProblem) finiteSlot else sourceSlot

    fun storageUnits(problem: Problem): Long = when (val current = slot(problem).load()) {
        is LpPreparationTemplates -> current.storageUnits
        is LpPreparationAdmission -> LpPreparationAdmission.STORAGE_UNITS
        is LpPreparationNamespace -> 1L
        null -> 0L
    }

    fun prepare(
        selection: PreparedFactorProjections,
        objective: LinearObjective?,
        token: Cancellation,
        reuseIndexes: Boolean,
        reuseMetadata: Boolean,
        earned: Boolean,
        compileBoundRows: Boolean,
        onWork: (Long) -> Unit,
    ): LpPreparationTemplates? {
        val problem = selection.problem
        if (earned && (problem.numRealVars != 0 || !selection.builtInRelaxation)) return null
        val cache = slot(problem)
        val previous = cache.load()
        val old = (previous as? LpPreparationTemplates)?.takeIf { it.sharesScope(namespace, problem) }
        val demand = (previous as? LpPreparationAdmission)?.takeIf { it.matches(namespace, problem) }
        var work = 0L
        val next: LpPreparationState? = try {
            if (earned) {
                token.charge(1L)
                work++
            }
            if (token() || (earned && old == null && (demand == null || demand.paidUnits < demand.requiredUnits))) {
                null
            } else {
                val prepared = LpPreparationTemplates.prepare(selection, namespace, rowScope, objective,
                    old, token, { work += it }, reuseIndexes, reuseMetadata,
                    if (earned && old == null) checkNotNull(demand).paidUnits else null, compileBoundRows)
                if (prepared == null && earned && demand != null && !token()) {
                    token.charge(LpPreparationAdmission.STORAGE_UNITS)
                    work += LpPreparationAdmission.STORAGE_UNITS
                    if (token()) null else demand.afterFailure(namespace, problem)
                } else prepared
            }
        } finally {
            onWork(work)
        }
        if (token() || next == null) return null
        cache.compareAndSet(previous, next)
        return next as? LpPreparationTemplates
    }

    fun recordConstruction(
        selection: PreparedFactorProjections,
        completedUnits: Long,
        token: Cancellation,
        onWork: (Long) -> Unit,
    ): Boolean {
        val problem = selection.problem
        if (completedUnits <= 0L || problem.numRealVars != 0 || !selection.builtInRelaxation || token()) return false
        val cache = slot(problem)
        val previous = cache.load()
        if ((previous as? LpPreparationTemplates)?.sharesScope(namespace, problem) == true) return false
        val old = (previous as? LpPreparationAdmission)?.takeIf { it.matches(namespace, problem) }
        if (previous !== namespace && old == null) {
            try {
                token.charge(1L)
            } finally {
                onWork(1L)
            }
            return !token() && cache.compareAndSet(previous, namespace)
        }
        val paid = old?.paidUnits ?: 0L
        val nextPaid = paid + minOf(completedUnits, Long.MAX_VALUE - paid)
        val next = try {
            token.charge(LpPreparationAdmission.STORAGE_UNITS)
            if (token()) null else if (old == null) LpPreparationAdmission(namespace, problem, nextPaid)
            else LpPreparationAdmission(namespace, problem, nextPaid, old.requiredUnits)
        } finally {
            onWork(LpPreparationAdmission.STORAGE_UNITS)
        }
        return !token() && next != null && cache.compareAndSet(previous, next)
    }
}

/** Prepares or revalidates this view's LP source templates in its inherited preparation cache. */
internal fun PreparedFactorProjections.prepareLpTemplates(
    objective: LinearObjective?,
    token: Cancellation,
    reuseIndexes: Boolean = true,
    reuseMetadata: Boolean = false,
    earned: Boolean = false,
    compileBoundRows: Boolean = false,
    onWork: (Long) -> Unit = {},
): LpPreparationTemplates? =
    lpPreparation.prepare(this, objective, token, reuseIndexes, reuseMetadata, earned, compileBoundRows, onWork)

/** Funds later earned preparation with completed construction work on this view. */
internal fun PreparedFactorProjections.recordLpConstruction(
    completedUnits: Long,
    token: Cancellation,
    onWork: (Long) -> Unit,
): Boolean = lpPreparation.recordConstruction(this, completedUnits, token, onWork)

/** Modeled storage retained by this view's current preparation slot. */
internal val PreparedFactorProjections.lpPreparationStorageUnits: Long
    get() = lpPreparation.storageUnits(problem)
