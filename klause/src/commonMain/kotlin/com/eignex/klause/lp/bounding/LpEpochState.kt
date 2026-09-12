package com.eignex.klause.lp.bounding

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.lp.engine.Basis
import com.eignex.klause.lp.engine.CutRowTransform
import com.eignex.klause.lp.engine.VarStatus
import com.eignex.klause.lp.relaxation.CutColumnSource
import com.eignex.klause.lp.relaxation.LpRelaxation
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.util.Cancellation

internal class LpEpochRoot private constructor(
    private val session: PropagationSession,
    private val domains: List<IntDomain>,
    private val pins: List<Boolean?>,
) {
    fun admitsCurrent(): Boolean = admits(session)

    fun admits(candidate: PropagationSession): Boolean = candidate === session &&
        !candidate.isUnsatAtRoot && domains.indices.all { subset(candidate.intDomain(it), domains[it]) } &&
        pins.indices.all { pins[it] == null || candidate.boolValue(it) == pins[it] }

    fun changed(candidate: PropagationSession): Boolean = candidate !== session || domains.indices.any {
        val domain = candidate.intDomain(it)
        domain.min != domains[it].min || domain.max != domains[it].max ||
            domain.valueCount != domains[it].valueCount || !subset(domain, domains[it])
    } || pins.indices.any { candidate.boolValue(it) != pins[it] }

    companion object {
        fun capture(session: PropagationSession): LpEpochRoot? {
            if (session.decisionLevel != 0 || session.isUnsatAtRoot) return null
            val domains = List(session.problem.numIntVars) { session.intDomain(it) }
            var remaining = LP_EPOCH_MEMBERSHIP_WORK
            for (domain in domains) {
                val work = 1L + minOf(domain.holeCount, domain.valueCount)
                if (work > remaining) return null
                remaining -= work
            }
            return LpEpochRoot(session, domains, List(session.problem.numBoolVars) { session.boolValue(it) })
        }

        private fun subset(candidate: IntDomain, saved: IntDomain): Boolean {
            if (candidate === saved) return true
            if (candidate.min < saved.min || candidate.max > saved.max) return false
            if (saved.holeCount == 0L) return true
            val values = candidate.spanOrNull(LP_EPOCH_MEMBERSHIP_WORK)
            if (values != null && candidate.valueCount < saved.holeCount) {
                return (0 until values.size).all { saved.contains(values.valueAt(it)) }
            }
            if (saved.holeCount > LP_EPOCH_MEMBERSHIP_WORK) return false
            var valid = true
            saved.forEachHoleInRange(candidate.min, candidate.max) { if (candidate.contains(it)) valid = false }
            return valid
        }
    }
}

private const val LP_EPOCH_MEMBERSHIP_WORK = 1_000_000L

internal class LpEpochState(
    val root: LpEpochRoot,
    val relaxation: LpRelaxation,
    val cutKeys: Set<List<Any?>>,
    val number: Long,
) {
    fun admits(session: PropagationSession): Boolean = root.admits(session)

    companion object {
        fun supports(relaxation: LpRelaxation): Boolean = declineReason(relaxation) == null

        fun declineReason(relaxation: LpRelaxation): String? {
            val model = relaxation.model
            val sources = relaxation.sourceMap ?: return "source_map"
            val columns = sources.columns
            return when {
                model.n == 0 -> "empty_model"

                model.hasContinuous || model.doubleView != null -> "continuous_cp_rebind"

                model.exactState != null -> "exact_cp_rebind"

                relaxation.gatedRows.isNotEmpty() -> "gated_owner"

                relaxation.tidyDerivation != null && relaxation.tidyProof == null -> "tidy_proof"

                columns.size != model.n || columns.any { it == null } -> "column_source"

                columns.distinct().size != model.n -> "aliased_source"

                relaxation.colVarId.indices.any {
                    relaxation.colVarId[it] < 0 && relaxation.colPresence[it] == null
                } -> "presence_definition"

                sources.assumptions.isNotEmpty() -> "assumption_scope"

                else -> null
            }
        }

        fun remapBasis(
            previous: LpRelaxation,
            next: LpRelaxation,
            basis: Basis?,
            cancellation: Cancellation = Cancellation.Never,
        ): Basis? {
            if (cancellation()) return null
            if (basis == null || basis.status.size != previous.model.numVars ||
                basis.basicVars.size != previous.model.m ||
                previous.sourceMap?.model !== next.sourceMap?.model
            ) {
                return null
            }
            val oldColumns = previous.sourceMap?.columns ?: return null
            val newColumns = next.sourceMap?.columns ?: return null
            if (oldColumns.any { it == null } || newColumns.any { it == null }) return null
            val oldCounts = oldColumns.groupingBy { it }.eachCount()
            val newCounts = newColumns.groupingBy { it }.eachCount()
            val newIndex = newColumns.withIndex().associate { it.value to it.index }
            val oldDefinitions = previous.sourceMap.auxiliaryDefinitions
            val newDefinitions = next.sourceMap.auxiliaryDefinitions
            val columnMap = oldColumns.map { source ->
                if (cancellation()) return null
                if (oldCounts[source] == 1 && newCounts[source] == 1 &&
                    oldDefinitions[source?.source] == newDefinitions[source?.source]
                ) {
                    newIndex[source] ?: -1
                } else {
                    -1
                }
            }
            val oldRows = rowKeys(previous, cancellation) ?: return null
            val newRows = rowKeys(next, cancellation) ?: return null
            val uniqueOld = oldRows.groupingBy { it }.eachCount()
            val uniqueNew = newRows.groupingBy { it }.eachCount()
            val nextRows = newRows.withIndex().associate { it.value to it.index }
            val model = next.model
            val statuses = Array(model.numVars) { VarStatus.AT_LOWER }
            for ((old, mapped) in columnMap.withIndex()) {
                if (cancellation()) return null
                if (mapped >= 0 && basis.status[old] == VarStatus.AT_UPPER && model.hasUpper[mapped]) {
                    statuses[mapped] = VarStatus.AT_UPPER
                }
            }
            val headings = LinkedHashSet<Int>()
            for (column in basis.basicVars) {
                if (cancellation()) return null
                val mapped = if (column < previous.model.n) {
                    columnMap[column]
                } else {
                    val key = oldRows.getOrNull(column - previous.model.n) ?: continue
                    if (uniqueOld[key] != 1 || uniqueNew[key] != 1) continue
                    model.n + (nextRows[key] ?: continue)
                }
                if (mapped !in statuses.indices || mapped in headings || headings.size == model.m) continue
                headings.add(mapped)
            }
            for (row in 0 until model.m) {
                if (cancellation()) return null
                if (headings.size == model.m) break
                val logical = model.n + row
                if (logical !in headings) headings.add(logical)
            }
            for (column in headings) statuses[column] = VarStatus.BASIC
            return Basis(headings.toIntArray(), statuses)
        }

        private fun rowKeys(relaxation: LpRelaxation, cancellation: Cancellation): List<List<Any?>>? {
            val model = relaxation.model
            val coefficients = Array(model.m) { HashMap<CutColumnSource?, Long>() }
            for (column in 0 until model.n) {
                if (cancellation()) return null
                model.forEachInColumn(column) { row, value ->
                    if (value != 0L) coefficients[row][relaxation.sourceMap?.column(column)] = value
                }
            }
            val keys = ArrayList<List<Any?>>()
            for (row in 0 until model.m) {
                if (cancellation()) return null
                val premise = model.rowPremises[row]
                val parent = relaxation.sourceMap?.parent(row)
                val proof = relaxation.tidyProof?.rowProof(row, cancellation)
                if (relaxation.tidyProof != null && proof == null) return null
                keys.add(
                    listOf(
                        coefficients[row], model.flippedRhs[row], model.hasUpper[model.n + row],
                        model.rowStrict[row], model.rowGlobal[row], premise?.vars?.toList(),
                        premise?.isUpper?.toList(), premise?.thresholds?.toList(), premise?.boolLits?.toList(),
                        parent?.model, parent?.assumptions, parent?.conclusion, parent?.facts,
                        proof?.transformations?.map { transform ->
                            when (transform) {
                                is CutRowTransform.Algebraic -> listOf(
                                    transform.input,
                                    transform.conclusion,
                                    transform.multiplier,
                                    transform.inputStrict,
                                    transform.outputStrict,
                                    transform.fixings,
                                )

                                is CutRowTransform.Lattice -> transform
                            }
                        },
                    ),
                )
            }
            return keys
        }
    }
}

internal class LpEpochMetrics {
    fun counts(): Map<String, Long> = mapOf(
        "attempts" to attempts.toLong(),
        "unchanged" to unchanged.toLong(),
        "root_declines" to rootDeclines.toLong(),
        "model_declines" to modelDeclines.toLong(),
        "preparation_declines" to preparationDeclines.toLong(),
        "regeneration_ns" to regenerationNanos,
        "tidy_ns" to tidyNanos,
        "preparation_ns" to preparationNanos,
    )

    var attempts = 0
    var unchanged = 0
    var rootDeclines = 0
    var modelDeclines = 0
    var preparationDeclines = 0
    var tidyNanos = 0L
    var regenerationNanos = 0L
    var preparationNanos = 0L
    var rows = 0
    var columns = 0
    var nonzeros = 0
}
