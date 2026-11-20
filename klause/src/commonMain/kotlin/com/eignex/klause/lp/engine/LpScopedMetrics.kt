package com.eignex.klause.lp.engine

import com.eignex.util.numbers.saturatedAdd

internal data class LpScopedMetrics(
    val editAttempts: Long,
    val editSuccesses: Long,
    val preparationAttempts: Long,
    val preparationSuccesses: Long,
    val preparationWork: Long,
    val preparationRefactorizations: Long,
    val createdOwners: Long,
    val closedOwners: Long,
    val peakOwners: Long,
    val retainedRows: Int,
    val activeRows: Int,
    val appendBasisWork: Long,
    val appendUnknownWork: Long,
    // [preparationWork] charges each owner's construction at its reserved bound, the allowance preparation spends;
    // the ledger swaps that bound for the work the owners reported building their scaling views.
    val reservedConstructionWork: Long = 0L,
    val constructionWork: Long = 0L,
    val componentScalarStages: Long = 0L,
    val componentRowStages: Long = 0L,
    val componentJoinStages: Long = 0L,
) {
    val editDeclines: Long get() = editAttempts - editSuccesses
    val preparationDeclines: Long get() = preparationAttempts - preparationSuccesses
    val currentOwners: Long get() = createdOwners - closedOwners
    val measuredPreparationWork: Long
        get() = if (preparationWork == Long.MAX_VALUE || reservedConstructionWork == Long.MAX_VALUE ||
            constructionWork == Long.MAX_VALUE
        ) Long.MAX_VALUE else saturatedAdd((preparationWork - reservedConstructionWork).coerceAtLeast(0L),
            constructionWork)
}

internal fun basisWorkUnits(work: com.eignex.klause.simplex.basis.BasisOperationWork?): Long? =
    work?.takeUnless { it.saturated }?.units

internal fun basisWorkComplete(work: com.eignex.klause.simplex.basis.BasisOperationWork?): Boolean =
    work != null && work.complete && !work.saturated
