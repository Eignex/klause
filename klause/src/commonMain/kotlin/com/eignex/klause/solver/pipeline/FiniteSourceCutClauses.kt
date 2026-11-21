package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.optimization.atScope
import com.eignex.klause.portfolio.SharedClausePool
import com.eignex.klause.presolve.SourceMapping
import com.eignex.klause.propagation.SharedClause
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceCondition
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceDerivation
import com.eignex.klause.solver.evidence.SourceEvidence
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.evidence.SourceEvidenceVerifier
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.arrays.emptyIntArray
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.magnitudeBitLength

internal class FiniteSourceCutBinding private constructor(
    private val model: Problem,
    private val objective: LinearObjective,
    private val authority: LinearToleranceAuthority,
) {
    private var context: SourceProofContext? = null

    fun capture(working: Problem, costs: LinearObjective, stop: ExecutionControl): SourceProofContext? {
        if (working !== model || costs !== objective || stop()) return null
        return context ?: authority.capture(working, costs, stop)?.also { context = it }
    }

    fun matches(working: Problem, source: SourceProofContext): Boolean =
        working === model && source === context && source.scope.condition == SourceCondition()

    companion object {
        fun admit(authority: LinearToleranceAuthority, mapping: SourceMapping?, model: Problem,
            objective: LinearObjective, stop: ExecutionControl): FiniteSourceCutBinding? =
            if (authority.compatible(mapping, model, objective, stop) &&
                requireNotNull(mapping).target.retainsCutCoordinates(model, stop))
                FiniteSourceCutBinding(model, objective, authority) else null

        private fun Problem.retainsCutCoordinates(working: Problem, stop: ExecutionControl): Boolean {
            if (numBoolVars != working.numBoolVars || numIntVars != working.numIntVars ||
                numRealVars != working.numRealVars || factors.size > working.factors.size) return false
            for (id in factors.indices) {
                stop.charge(1L)
                if (stop() || factors[id] !== working.factors[id]) return false
            }
            for (id in 0 until numIntVars) {
                stop.charge(1L)
                if (stop() || (intBounds.hasLower(id) && (!working.intBounds.hasLower(id) ||
                        working.intBounds.lower(id) < intBounds.lower(id))) ||
                    (intBounds.hasUpper(id) && (!working.intBounds.hasUpper(id) ||
                        working.intBounds.upper(id) > intBounds.upper(id)))) return false
            }
            for (id in 0 until numRealVars) {
                stop.charge(1L)
                if (stop() || working.realLower[id] < realLower[id] || working.realUpper[id] > realUpper[id]) {
                    return false
                }
            }
            return !stop()
        }
    }
}

internal class FiniteSourceCutClauses(
    private val binding: FiniteSourceCutBinding,
    private val model: Problem,
    private val source: SourceProofContext,
    private val budget: SourceLpBudget,
    private val stop: ExecutionControl,
    private val pool: SharedClausePool,
    private val origin: Int,
) {
    private val observations = linkedMapOf<String, Long>()
    private var work = 0L
    private var allocation = 0L
    private var proved = 0

    private fun observe(stage: String, amount: Long = 1L) {
        val key = "SourceCutClause$stage"
        observations[key] = (observations[key] ?: 0L) + amount
    }

    private fun pay(units: Long, bytes: Long = 0L): Boolean {
        val funding = budget.funding ?: return false
        if (stop() || units > MAX_WORK - work || bytes > MAX_BYTES - allocation ||
            units >= funding.availableWork || bytes >= funding.remainingAllocation) return false
        work += units
        allocation += bytes
        budget.observe(conflictCertification = units)
        funding.charge(0L, bytes)
        return !stop() && !funding.paused
    }

    private fun scalar(value: BigFraction): Boolean {
        val bits = value.num.magnitudeBitLength().toLong() + value.den.magnitudeBitLength()
        return value.den == BIG_ONE && bits <= 64L && pay(1L + bits * bits / 64L, 64L + (bits + 7L) / 8L)
    }

    fun publish(cuts: List<SourceEvidence>): Map<String, Long> {
        if (!binding.matches(model, source) || model.numBoolVars != 0 || !pay(1L, 256L)) return emptyMap()
        for (cut in cuts.take(2)) {
            if (proved == MAX_CLAUSES || !pay(1L, 128L)) break
            if (cut.scope != source.scope) continue
            val row = (cut.claim as? SourceClaim.Consequence)?.inequality ?: continue
            if (row.supportSize !in 1..8 || !scalar(row.rhs) ||
                !pay(row.supportSize.toLong(), 128L + row.supportSize * 128L)) continue
            val entries = row.coefficients.entries.toList()
            if (entries.any { (coordinate, coefficient) ->
                    coordinate.kind != SourceCoordinate.Kind.Integer ||
                        coordinate.id !in 0 until model.numIntVars || !scalar(coefficient) ||
                        !binary(coordinate)
                }) continue
            // Eight 63-bit coefficients keep every activity within 67 bits, including rejected covers.
            if (!pay(entries.size * 32L, 256L + entries.size * 1_024L)) break
            var baseline = BigFraction.ZERO
            for ((_, coefficient) in entries) if (coefficient < BigFraction.ZERO) baseline += coefficient
            val weights = entries.map { (_, coefficient) ->
                if (coefficient < BigFraction.ZERO) coefficient.negated() else coefficient
            }
            if (!pay(MAX_CLAUSES.toLong(), 128L + MAX_CLAUSES * Int.SIZE_BYTES.toLong())) break
            val provedMasks = IntArray(MAX_CLAUSES)
            var coverCount = 0
            masks@ for (mask in ((1 shl entries.size) - 1) downTo 1) {
                if (proved == MAX_CLAUSES || !pay(1L, 256L)) break
                observe("ScannedMasks")
                var covered = false
                for (id in 0 until coverCount) {
                    if (!pay(1L)) break@masks
                    if (mask and provedMasks[id] == provedMasks[id]) {
                        covered = true
                        break
                    }
                }
                if (covered) continue
                if (!pay(entries.size * 32L, 256L + entries.size * 1_024L)) break
                var activity = baseline
                for (id in entries.indices) if (mask and (1 shl id) != 0) activity += weights[id]
                fun conflicts(value: BigFraction): Boolean = value > row.rhs || (row.strict && value == row.rhs)
                if (!conflicts(activity)) continue
                var cover = mask
                for (id in entries.indices) if (cover and (1 shl id) != 0) {
                    val without = activity - weights[id]
                    if (conflicts(without)) {
                        cover = cover and (1 shl id).inv()
                        activity = without
                    }
                }
                if (cover == 0) break
                val pins = entries.indices.filter { cover and (1 shl it) != 0 }
                if (!pay(entries.size * 16L + pins.size, 1_024L + entries.size * 1_024L)) break
                var condition = SourceCondition()
                val terms = arrayListOf(SourceDerivation.Combination.Term(BigFraction.ONE,
                    SourceDerivation.Import(cut)))
                val atoms = LongArray(pins.size * SharedClause.QUAD)
                var atom = 0
                for ((id, entry) in entries.withIndex()) {
                    val (coordinate, coefficient) = entry
                    val positive = coefficient > BigFraction.ZERO
                    val pinned = cover and (1 shl id) != 0
                    val proof = if (pinned) {
                        condition = condition.bound(coordinate,
                            if (positive) BigFraction.ONE else BigFraction.ZERO, upper = !positive)
                        atoms[atom++] = coordinate.id.toLong()
                        atoms[atom++] = if (positive) 1L else 0L
                        atoms[atom++] = if (positive) 0L else 1L
                        atoms[atom++] = 0L
                        SourceDerivation.Assumption(coordinate, upper = !positive)
                    } else SourceDerivation.Premise("int/${coordinate.id}/${if (positive) "lower" else "upper"}")
                    terms += SourceDerivation.Combination.Term(weights[id], proof)
                }
                val scope = source.scope.copy(condition = condition)
                // Every published literal discharges exactly one of these freshly checked restrictions.
                val evidence = SourceEvidence(scope, SourceClaim.Refutation, SourceDerivation.Combination(terms))
                val contextBytes = 256L + 128L * (source.coordinateCount + source.premiseCount)
                if (!pay(1L + source.coordinateCount + source.premiseCount, contextBytes)) break
                val checking = ExecutionControl { !pay(1L, 64L) }
                observe("RefutationChecks")
                if (SourceEvidenceVerifier(source.atScope(scope)).verify(evidence, checking) !=
                    SourceEvidenceCheck.Checked || checking()) continue
                provedMasks[coverCount++] = cover
                proved++
                observe("Proved")
                if (!pay(2L + pins.size * 8L, 8_448L + atoms.size * Long.SIZE_BYTES + pins.size * 8L)) break
                val clause = SharedClause(emptyIntArray(), atoms, pins.size)
                val funding = requireNotNull(budget.funding)
                val published = pool.publish(listOf(clause), origin = origin, isGlobal = true,
                    maxWork = minOf(MAX_WORK - work, funding.availableWork), maxScratchEntries = 1024,
                    cancelled = { stop() || funding.paused }, charge = {
                        work++
                        budget.observe(conflictCertification = 1L)
                    })
                observe("Published", published.toLong())
                observe("DuplicatesOrDeclined", (1 - published).toLong())
            }
        }
        observe("Work", work)
        observe("ModeledAllocation", allocation)
        return observations.toMap()
    }

    private fun binary(coordinate: SourceCoordinate): Boolean {
        if (!pay(2L)) return false
        for ((upper, value) in listOf(false to BigFraction.ZERO, true to BigFraction.ONE)) {
            val premise = source.premise("int/${coordinate.id}/${if (upper) "upper" else "lower"}") ?: return false
            val sign = if (upper) BigFraction.ONE else BigFraction.MINUS_ONE
            if (premise.guards.isNotEmpty() || premise.integerPins.isNotEmpty() || premise.inequality.strict ||
                premise.inequality.supportSize != 1 || premise.inequality.coefficient(coordinate) != sign ||
                premise.inequality.rhs != value * sign) return false
        }
        return true
    }

    private companion object {
        const val MAX_WORK = 65_536L
        const val MAX_BYTES = 1_048_576L
        const val MAX_CLAUSES = 16
    }
}
