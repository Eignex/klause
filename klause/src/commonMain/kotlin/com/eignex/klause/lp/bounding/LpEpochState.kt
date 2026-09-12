package com.eignex.klause.lp.bounding

import com.eignex.klause.lp.engine.Basis
import com.eignex.klause.lp.engine.VarStatus
import com.eignex.klause.lp.relaxation.LpRelaxation
import com.eignex.klause.propagation.PropagationSession

internal class LpEpochRoot private constructor(
    private val session: PropagationSession,
    private val lower: LongArray,
    private val upper: LongArray,
    private val pins: List<Boolean?>,
) {
    fun admits(candidate: PropagationSession): Boolean = candidate === session &&
        lower.indices.all {
            val domain = candidate.intDomain(it)
            domain.min >= lower[it] && domain.max <= upper[it]
        } && pins.indices.all { pins[it] == null || candidate.boolValue(it) == pins[it] }

    fun changed(candidate: PropagationSession): Boolean = candidate !== session ||
        lower.indices.any {
            val domain = candidate.intDomain(it)
            domain.min != lower[it] || domain.max != upper[it] || domain.holeCount != 0L
        } || pins.indices.any { candidate.boolValue(it) != pins[it] }

    companion object {
        fun capture(session: PropagationSession): LpEpochRoot? {
            if (session.decisionLevel != 0 || session.isUnsatAtRoot ||
                session.problem.numIntVars + session.problem.numBoolVars > LP_EPOCH_MAX_COLUMNS ||
                (0 until session.problem.numIntVars).any { session.intDomain(it).holeCount != 0L }
            ) {
                return null
            }
            return LpEpochRoot(
                session,
                LongArray(session.problem.numIntVars) { session.intDomain(it).min },
                LongArray(session.problem.numIntVars) { session.intDomain(it).max },
                List(session.problem.numBoolVars) { session.boolValue(it) },
            )
        }
    }
}

internal class LpEpochState(
    val root: LpEpochRoot,
    val relaxation: LpRelaxation,
    val cutKeys: Set<List<Any?>>,
    val number: Long,
) {
    fun admits(session: PropagationSession): Boolean = root.admits(session)

    companion object {
        fun supports(relaxation: LpRelaxation): Boolean {
            val model = relaxation.model
            val sources = relaxation.sourceMap ?: return false
            val columns = sources.columns
            return model.n in 1..LP_EPOCH_MAX_COLUMNS && model.m <= LP_EPOCH_MAX_ROWS &&
                !model.hasContinuous && model.doubleView == null && model.exactState == null &&
                relaxation.gatedRows.isEmpty() && relaxation.tidyDerivation == null &&
                columns.size == model.n && columns.all { it != null } &&
                columns.distinct().size == model.n &&
                relaxation.colVarId.all { it >= 0 } && sources.assumptions.isEmpty()
        }

        fun remapBasis(previous: LpRelaxation, next: LpRelaxation, basis: Basis?): Basis? {
            if (basis == null || basis.status.size != previous.model.numVars ||
                basis.basicVars.size != previous.model.m ||
                previous.sourceMap?.model !== next.sourceMap?.model
            ) {
                return null
            }
            val oldColumns = previous.sourceMap?.columns ?: return null
            val newColumns = next.sourceMap?.columns ?: return null
            if (oldColumns != newColumns || oldColumns.any { it == null }) return null
            val oldRows = rowKeys(previous)
            val newRows = rowKeys(next)
            val uniqueOld = oldRows.groupingBy { it }.eachCount()
            val uniqueNew = newRows.groupingBy { it }.eachCount()
            val nextRows = newRows.withIndex().associate { it.value to it.index }
            val model = next.model
            val statuses = Array(model.numVars) { j ->
                if (j < model.n && basis.status[j] == VarStatus.AT_UPPER && model.hasUpper[j]) {
                    VarStatus.AT_UPPER
                } else {
                    VarStatus.AT_LOWER
                }
            }
            val headings = ArrayList<Int>()
            for (column in basis.basicVars) {
                val mapped = if (column < previous.model.n) {
                    column
                } else {
                    val key = oldRows.getOrNull(column - previous.model.n) ?: continue
                    if (uniqueOld[key] != 1 || uniqueNew[key] != 1) continue
                    model.n + (nextRows[key] ?: continue)
                }
                if (mapped !in statuses.indices || mapped in headings || headings.size == model.m) continue
                headings.add(mapped)
            }
            for (row in 0 until model.m) {
                if (headings.size == model.m) break
                val logical = model.n + row
                if (logical !in headings) headings.add(logical)
            }
            for (column in headings) statuses[column] = VarStatus.BASIC
            return Basis(headings.toIntArray(), statuses)
        }

        private fun rowKeys(relaxation: LpRelaxation): List<List<Any?>> {
            val model = relaxation.model
            val coefficients = Array(model.m) { LongArray(model.n) }
            for (column in 0 until model.n) {
                model.forEachInColumn(column) { row, value -> coefficients[row][column] = value }
            }
            return List(model.m) { row ->
                val premise = model.rowPremises[row]
                val parent = relaxation.sourceMap?.parent(row)
                listOf(
                    coefficients[row].toList(), model.flippedRhs[row], model.hasUpper[model.n + row],
                    model.rowStrict[row], model.rowGlobal[row], premise?.vars?.toList(),
                    premise?.isUpper?.toList(), premise?.thresholds?.toList(), premise?.boolLits?.toList(),
                    parent?.model, parent?.assumptions, parent?.conclusion, parent?.facts,
                )
            }
        }
    }
}

internal const val LP_EPOCH_MAX_COLUMNS = 256
internal const val LP_EPOCH_MAX_ROWS = 256

internal class LpEpochMetrics {
    var attempts = 0
    var unchanged = 0
    var rootDeclines = 0
    var modelDeclines = 0
    var preparationDeclines = 0
    var regenerationNanos = 0L
    var preparationNanos = 0L
    var rows = 0
    var columns = 0
    var nonzeros = 0
}
