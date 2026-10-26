package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.AlgebraicReal
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.arithmetic.polynomial.PolynomialLimits
import com.eignex.klause.arithmetic.polynomial.PolynomialResourceLimit
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter

internal data class PolynomialCellFeedbackStats(
    val admitted: Long,
    val lookups: Long,
    val uses: Long,
    val reuses: Long,
    val crossRegionUses: Long,
    val evicted: Long,
    val snapshotReplacements: Long,
    val scopeDeclines: Long,
    val metadataVisits: Long,
    val candidateUses: Long,
    val candidateWork: Long,
    val candidateDeclines: Long,
)

internal class PolynomialCellFeedbackUse(
    val cell: CheckedPolynomialCell,
    val origin: PolynomialCellScope.Region,
    val current: PolynomialCellScope.Region,
    val value: AlgebraicReal,
)

internal class PolynomialCellFeedback(
    private val source: PolynomialRealFormula,
    private val condition: PolynomialCondition,
    private val maximumCells: Int = MAX_FEEDBACK_CELLS,
    parent: QfNraWork? = null,
    limits: PolynomialLimits = PolynomialLimits(),
    private val maximumCandidateWork: Long = MAX_CANDIDATE_WORK,
) {
    init {
        require(maximumCells in 1..MAX_FEEDBACK_CELLS)
        require(maximumCandidateWork in 1..MAX_CANDIDATE_WORK)
    }

    private val entries = ArrayList<Entry>()
    private var snapshot: PolynomialFormula? = null
    private var active: PolynomialCellScope? = null
    private var rebuild = true
    private var availableCount = 0
    private var admitted = 0L
    private var lookups = 0L
    private var uses = 0L
    private var reuses = 0L
    private var crossRegionUses = 0L
    private var evicted = 0L
    private var snapshotReplacements = 0L
    private var scopeDeclines = 0L
    private var metadataVisits = 0L
    private var candidateUses = 0L
    private var candidateWork = 0L
    private var candidateDeclines = 0L
    private var candidateParentStopped = false
    private val candidateArithmetic = parent?.let { caller ->
        val meter = WorkMeter { units -> candidateWork += units; caller.charge(units) }
        PolynomialArithmetic(object : Cancellation {
            override fun isCancelled(): Boolean {
                if (caller()) candidateParentStopped = true
                return candidateParentStopped || candidateWork >= maximumCandidateWork
            }
            override fun deadline() = caller.deadline()
            override fun workMeter(): WorkMeter = meter
        }, limits)
    }
    private val candidateAlgebraic = candidateArithmetic?.let(::AlgebraicArithmetic)
    var firstCrossRegionUse: PolynomialCellFeedbackUse? = null
        private set
    var firstRegionUse: PolynomialCellFeedbackUse? = null
        private set
    val stats: PolynomialCellFeedbackStats get() = PolynomialCellFeedbackStats(
        admitted, lookups, uses, reuses, crossRegionUses, evicted,
        snapshotReplacements, scopeDeclines, metadataVisits,
        candidateUses, candidateWork, candidateDeclines,
    )

    fun candidateScope(
        snapshot: PolynomialFormula,
        premises: PolynomialBooleanRegion,
        producer: PolynomialCellScope.Region?,
        arithmetic: PolynomialArithmetic,
    ): PolynomialCellScope.Region? {
        step(arithmetic)
        if (candidateWork >= maximumCandidateWork) return null
        if (premises.atoms.size > MAX_FEEDBACK_PREMISES ||
            premises.bools.size > MAX_FEEDBACK_PREMISES - premises.atoms.size) return null
        return producer ?: PolynomialCellScope.Region(source, condition, snapshot,
            PolynomialBooleanRegion(premises.atoms.mapValues { step(arithmetic); it.value },
                premises.bools.mapValues { step(arithmetic); it.value }), Any())
    }

    fun candidateRejection(
        required: PolynomialAtom,
        scope: PolynomialCellScope.Region,
        coordinates: Map<Int, AlgebraicReal>,
        available: () -> List<CheckedPolynomialCell>,
    ): CheckedPolynomialCell? {
        if (candidateParentStopped) throw PolynomialInterrupted()
        if (candidateWork >= maximumCandidateWork) return null
        val arithmetic = checkNotNull(candidateArithmetic)
        val algebraic = checkNotNull(candidateAlgebraic)
        return try {
            step(arithmetic)
            val cells = available()
            repeat(cells.size) { step(arithmetic) }
            rejection(required, scope, cells, arithmetic, algebraic, coordinates::get)?.also { candidateUses++ }
        } catch (stop: PolynomialInterrupted) {
            if (candidateParentStopped) throw stop
            candidateDeclines++
            null
        } catch (_: PolynomialResourceLimit) {
            candidateDeclines++
            null
        }
    }

    fun replaceSnapshot(replacement: PolynomialFormula, arithmetic: PolynomialArithmetic) {
        step(arithmetic)
        if (snapshot === replacement) return
        if (snapshot != null) snapshotReplacements++
        discard(arithmetic)
        snapshot = replacement
    }

    fun rejection(
        variable: Int,
        required: PolynomialAtom,
        scope: PolynomialCellScope,
        value: AlgebraicReal,
        available: List<CheckedPolynomialCell>,
        arithmetic: PolynomialArithmetic,
        algebraic: AlgebraicArithmetic,
    ): CheckedPolynomialCell? = rejection(required, scope, available, arithmetic, algebraic) { id ->
        value.takeIf { id == variable }
    }

    private fun rejection(
        required: PolynomialAtom,
        scope: PolynomialCellScope,
        available: List<CheckedPolynomialCell>,
        arithmetic: PolynomialArithmetic,
        algebraic: AlgebraicArithmetic,
        valueOf: (Int) -> AlgebraicReal?,
    ): CheckedPolynomialCell? {
        step(arithmetic)
        lookups++
        if (!enter(scope, arithmetic)) return null
        if (rebuild || availableCount != available.size) {
            for (index in 0 until minOf(MAX_FEEDBACK_CELLS, available.size)) {
                step(arithmetic)
                admit(available[index], arithmetic)
            }
            rebuild = false
            availableCount = available.size
        }
        for (entry in entries) {
            step(arithmetic)
            val cell = entry.cell
            val value = valueOf(cell.variable) ?: continue
            if (!samePredicate(cell.predicate, required, arithmetic)) continue
            // Active predicates keep source-coordinate cells away from reconstructed placeholder slots.
            if (!cell.contains(value, algebraic)) continue
            step(arithmetic)
            uses++
            if (entry.uses > 0) reuses++
            entry.uses++
            val current = scope as? PolynomialCellScope.Region
            val origin = entry.firstRegion
            if (current != null && origin == null) entry.firstRegion = current
            if (current != null && firstRegionUse == null) {
                firstRegionUse = PolynomialCellFeedbackUse(cell, origin ?: current, current, value)
            }
            if (origin != null && current != null && origin.reduction !== current.reduction) {
                crossRegionUses++
                if (firstCrossRegionUse == null) {
                    firstCrossRegionUse = PolynomialCellFeedbackUse(cell, origin, current, value)
                }
            }
            return cell
        }
        return null
    }

    fun admit(cell: CheckedPolynomialCell, arithmetic: PolynomialArithmetic) {
        step(arithmetic)
        val context = active ?: return
        if (cell.scope.source !== source) return
        val regional = cell.scope as? PolynomialCellScope.Region
        if (regional != null && (regional.premises.atoms.size > MAX_FEEDBACK_PREMISES ||
                regional.premises.bools.size > MAX_FEEDBACK_PREMISES - regional.premises.atoms.size)) return
        val entry = Entry(cell, context)
        if (!valid(entry, context)) return
        for (retained in entries) {
            step(arithmetic)
            if (retained.cell === cell) return
        }
        if (entries.size == maximumCells) evict(0, arithmetic)
        step(arithmetic)
        entries.add(entry)
        admitted++
    }

    private fun enter(scope: PolynomialCellScope, arithmetic: PolynomialArithmetic): Boolean {
        step(arithmetic)
        val requested = when (scope) {
            is PolynomialCellScope.Source -> null
            is PolynomialCellScope.Query -> scope.snapshot.takeIf { scope.condition === condition }
            is PolynomialCellScope.Region -> scope.snapshot.takeIf {
                scope.condition === condition && scope.premises.atoms.size <= MAX_FEEDBACK_PREMISES &&
                    scope.premises.bools.size <= MAX_FEEDBACK_PREMISES - scope.premises.atoms.size
            }
        }
        if (scope.source !== source || requested == null) {
            scopeDeclines++
            discard(arithmetic)
            snapshot = null
            return false
        }
        if (scope === active) return true
        replaceSnapshot(requested, arithmetic)
        var index = 0
        while (index < entries.size) {
            step(arithmetic)
            if (valid(entries[index], scope)) index++ else evict(index, arithmetic)
        }
        active = scope
        return true
    }

    private fun valid(entry: Entry, context: PolynomialCellScope): Boolean {
        val guard = entry.cell.scope
        if (guard.source !== source) return false
        return when (guard) {
            is PolynomialCellScope.Source -> true
            is PolynomialCellScope.Query -> guard.condition === condition && guard.snapshot === snapshot
            is PolynomialCellScope.Region -> guard === context
        }
    }

    private fun samePredicate(
        left: PolynomialAtom,
        right: PolynomialAtom,
        arithmetic: PolynomialArithmetic,
    ): Boolean {
        step(arithmetic)
        if (left === right) return true
        if (left.relation != right.relation || left.polynomial.termCount != right.polynomial.termCount) return false
        repeat(left.polynomial.termCount) { step(arithmetic) }
        return left == right
    }

    private fun evict(index: Int, arithmetic: PolynomialArithmetic) {
        step(arithmetic)
        entries.removeAt(index)
        evicted++
    }

    private fun discard(arithmetic: PolynomialArithmetic) {
        while (entries.isNotEmpty()) evict(entries.lastIndex, arithmetic)
        active = null
        rebuild = true
        availableCount = 0
        firstCrossRegionUse = null
        firstRegionUse = null
    }

    private fun step(arithmetic: PolynomialArithmetic) {
        metadataVisits++
        arithmetic.step()
    }

    private class Entry(
        val cell: CheckedPolynomialCell,
        origin: PolynomialCellScope,
        var uses: Long = 0,
    ) {
        var firstRegion: PolynomialCellScope.Region? = origin as? PolynomialCellScope.Region
    }
}

private const val MAX_FEEDBACK_CELLS = 8
private const val MAX_FEEDBACK_PREMISES = 64
private const val MAX_CANDIDATE_WORK = 8192L
