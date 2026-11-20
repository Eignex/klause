package com.eignex.klause.propagation

import com.eignex.klause.factor.arithmetic.internals.linearLazyReason
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.util.numbers.BIG_ZERO
import com.eignex.util.numbers.CheckedLongOverflowException
import com.eignex.util.numbers.abs
import com.eignex.util.numbers.compareTo

internal class IntegerConflictLearner(private val state: PropagationState) {
    private val budget = ArithmeticLearningBudget(state)
    private val sources = GuardedIntegerSource(state, budget)
    private val normalized = mutableMapOf<Pair<Any, Boolean>, IntegerConflictRow?>()
    private val eagerMarkers = mutableMapOf<Pair<Int, Boolean>, IntArray?>()

    fun learn(
        factorId: Int,
        currentLevel: Int,
        fallback: ConflictAnalyzer.AnalysisResult.Learned,
    ): ConflictAnalyzer.AnalysisResult.LearnedIntegerRow? = try {
        derive(factorId, currentLevel, fallback)
    } catch (stop: ArithmeticLearningDeclined) {
        decline(stop.reason)
    } catch (_: CheckedLongOverflowException) {
        state.arithmeticLearningTrace?.record("integer.decline.overflow")
        null
    }

    private fun decline(cause: String): Nothing? {
        state.arithmeticLearningTrace?.record("integer.decline.$cause")
        return null
    }

    private fun derive(
        factorId: Int,
        currentLevel: Int,
        fallback: ConflictAnalyzer.AnalysisResult.Learned,
    ): ConflictAnalyzer.AnalysisResult.LearnedIntegerRow? {
        if (currentLevel <= 0) return decline("root")
        if (!fallback.asserting) return decline("fallback-nonasserting")
        if (fallback.literals.isEmpty()) return decline("fallback-empty")
        if (fallback.backjumpLevel !in 0 until currentLevel) return decline("fallback-target")
        val target = fallback.backjumpLevel
        val trail = state.integerLearningTrailAtLevel?.invoke(target) ?: return decline("assertion-unavailable")
        state.arithmeticLearningTrace?.record("integer.fallback-target", target.toLong(), trail.toLong())
        val source = sourceFactor(factorId) ?: return decline("seed-source")
        var row = when (source.op) {
            LinearOp.LE -> normalizedSource(source, useLo = true)
            LinearOp.GE -> normalizedSource(source, useLo = false)
            LinearOp.EQ -> {
                val lower = normalizedSource(source, useLo = true) ?: return decline("seed-normalization")
                if (lower.minimum(state, budget = budget) > lower.bound) lower else
                    normalizedSource(source, useLo = false)
            }
            LinearOp.NE -> null
        } ?: return decline("seed-normalization")
        state.arithmeticLearningTrace?.row("integer.seed-row", row, state)
        if (row.minimum(state, budget = budget) <= row.bound) return decline("seed-not-conflicting")
        val used = mutableSetOf<Pair<Int, Int>>()
        while (true) {
            budget.spend()
            val event = reason(row, used) ?: return decline("no-qualified-endpoint")
            budget.spend(bytes = 48)
            used.add(event.key)
            state.arithmeticLearningTrace?.row("integer.pivot-row", event.row, state, event.trail)
            row = event.combination.reduced(state, budget = budget) ?: event.combination
            if (row.minimum(state, budget = budget) <= row.bound) return decline("combination-lost-conflict")
            val duplicate = state.learned.integerRows?.any {
                budget.spend()
                if (it.row.directionHash == row.directionHash) budget.spend(row.variables.size.toLong())
                it.row.subsumes(row)
            } == true
            if (duplicate) continue
            val asserts = row.assertsAt(state, trail, budget)
            state.arithmeticLearningTrace?.record("integer.assertion", target.toLong(), trail.toLong(),
                if (asserts) 1 else 0)
            if (!asserts) continue
            val metadataSize = row.variables.size.toLong() + fallback.decisionLevels.size
            budget.spend(work = metadataSize * (Long.SIZE_BITS - metadataSize.countLeadingZeroBits()),
                bytes = metadataSize * 40)
            val levels = row.variables.indices.map { index ->
                budget.spend()
                val variable = row.variables[index]
                val lower = row.coefficients[index] > BIG_ZERO
                val domain = state.intDomains[variable]
                state.boundEstablishmentLevel(variable, if (lower) domain.min else domain.max, lower) ?: 0
            }.plus(fallback.decisionLevels.toList()).distinct().sorted().toIntArray()
            return ConflictAnalyzer.AnalysisResult.LearnedIntegerRow(row, target, levels.size, levels, fallback)
        }
    }

    private fun normalizedSource(source: IntegerSourceRow, useLo: Boolean): IntegerConflictRow? {
        val key = source.identity to useLo
        if (normalized.containsKey(key)) return normalized[key]
        budget.spend(bytes = 80)
        val row = source.row(useLo, budget)
        normalized[key] = row
        return row
    }

    private fun sourceFactor(
        factorId: Int,
        trail: Int = state.undo.size,
        forBoundReason: Boolean = false,
    ): IntegerSourceRow? = sources.source(factorId, trail, forBoundReason)

    private fun reason(row: IntegerConflictRow, used: Set<Pair<Int, Int>>): BoundEvent? {
        var candidates: LongArray? = null
        var count = 0
        for (index in row.variables.indices) {
            budget.spend()
            val variable = row.variables[index]
            val lower = row.coefficients[index] > BIG_ZERO
            val factorId = if (lower) state.intMinReason[variable] else state.intMaxReason[variable]
            if (!state.hasIntegerArithmeticSource(factorId)) continue
            val trail = endpointTrail(variable, lower, factorId) ?: continue
            val key = variable to if (lower) trail else trail.inv()
            if (key in used) continue
            val priorities = candidates ?: run {
                budget.spend(bytes = row.variables.size.toLong() * Long.SIZE_BYTES)
                LongArray(row.variables.size).also { candidates = it }
            }
            // Signed high words order trails; complemented low words prefer the earlier coordinate on ties.
            priorities[count++] = (trail.toLong() shl Int.SIZE_BITS) or (index.toLong().inv() and 0xFFFF_FFFFL)
        }
        val priorities = candidates ?: return null
        fun later(left: Long, right: Long): Boolean {
            budget.spend()
            return left > right
        }
        fun sift(start: Int, size: Int) {
            var root = start
            while (root * 2 + 1 < size) {
                budget.spend()
                var child = root * 2 + 1
                if (child + 1 < size && later(priorities[child + 1], priorities[child])) child++
                if (!later(priorities[child], priorities[root])) return
                val swap = priorities[root]
                priorities[root] = priorities[child]
                priorities[child] = swap
                root = child
            }
        }
        for (root in count / 2 - 1 downTo 0) sift(root, count)
        var remaining = count
        while (remaining > 0) {
            val priority = priorities[0]
            val index = priority.toInt().inv()
            remaining--
            priorities[0] = priorities[remaining]
            sift(0, remaining)
            budget.spend()
            qualifiedReason(row, index, (priority shr Int.SIZE_BITS).toInt())?.let { return it }
        }
        return null
    }

    private fun endpointTrail(variable: Int, lower: Boolean, factorId: Int): Int? {
        val recorded = if (lower) state.intMinAntecedents[variable] else state.intMaxAntecedents[variable]
        if (recorded == null) return null
        if (isLazyReason(recorded)) {
            if (recorded.size != LAZY_PAYLOAD + 6 || recorded[1] != factorId) {
                state.arithmeticLearningTrace?.record("integer.endpoint-decline.marker-shape")
                return null
            }
            return recorded[2]
        }
        budget.spend(bytes = 48)
        val key = variable to lower
        if (eagerMarkers.containsKey(key)) return eagerMarkers[key]?.get(2)
        return eagerEndpointTrail(variable, lower)
    }

    private fun endpointMarker(row: IntegerConflictRow, index: Int, trail: Int): IntArray? {
        val variable = row.variables[index]
        val lower = row.coefficients[index] > BIG_ZERO
        val recorded = if (lower) state.intMinAntecedents[variable] else state.intMaxAntecedents[variable]
        if (isLazyReason(recorded)) return recorded
        if (recorded == null) return null
        val key = variable to lower
        if (eagerMarkers.containsKey(key)) return eagerMarkers[key]
        budget.spend(bytes = 96)
        val marker = eagerEndpoint(variable, lower, trail)
        eagerMarkers[key] = marker
        return marker
    }

    private fun eagerEndpointTrail(variable: Int, lower: Boolean): Int? {
        val moves = state.boundMoves[variable] ?: return null
        val previous =
            (if (lower) state.boundMovePriorMin[variable] else state.boundMovePriorMax[variable]) ?: return null
        val live = state.intDomains[variable].let { if (lower) it.min else it.max }
        var lo = 0
        var hi = moves.size
        while (lo < hi) {
            budget.spend()
            val mid = (lo + hi) ushr 1
            if (if (lower) previous[mid] < live else previous[mid] > live) lo = mid + 1 else hi = mid
        }
        if (lo == 0) return null
        return moves[lo - 1]
    }

    private fun eagerEndpoint(variable: Int, lower: Boolean, trail: Int): IntArray? {
        val factorId = if (lower) state.intMinReason[variable] else state.intMaxReason[variable]
        if (factorId < 0) return null
        val source = sourceFactor(factorId, trail, forBoundReason = true) ?: return null
        budget.spend(source.variables.size.toLong())
        val position = source.variables.indexOf(variable)
        if (position < 0) return null
        val positive = normalizedSource(source, source.op != LinearOp.GE)?.coefficient(variable) ?: return null
        val useLo = if (source.op == LinearOp.GE) false else (positive > BIG_ZERO) != lower
        return linearLazyReason(state, factorId, position, useLo, 0,
            source.guard xor 1, source.guard != Lit.NONE).also {
            it[2] = trail
        }
    }

    private fun qualifiedReason(row: IntegerConflictRow, index: Int, trail: Int): BoundEvent? {
        val variable = row.variables[index]
        val lower = row.coefficients[index] > BIG_ZERO
        val marker = endpointMarker(row, index, trail) ?: return null
        state.arithmeticLearningTrace?.marker(variable, lower, marker)
        val factorId = marker[1]
        val key = variable to if (lower) marker[2] else marker[2].inv()
        val source = sourceFactor(factorId, marker[2], forBoundReason = true)
        if (source == null) {
            state.arithmeticLearningTrace?.record("integer.endpoint-decline.source", factorId.toLong())
            return null
        }
        val extra = marker[LAZY_PAYLOAD + 5] != 0
        if (extra != (source.guard != Lit.NONE) ||
            (extra && marker[LAZY_PAYLOAD + 4] != (source.guard xor 1))) {
            state.arithmeticLearningTrace?.record("integer.endpoint-decline.guard-marker", source.guard.toLong())
            return null
        }
        if (row.guard != Lit.NONE && source.guard != Lit.NONE && row.guard != source.guard) {
            state.arithmeticLearningTrace?.record("integer.endpoint-decline.incompatible-guard")
            return null
        }
        val position = marker[LAZY_PAYLOAD]
        if (source.variables.getOrNull(position) != variable) {
            state.arithmeticLearningTrace?.record("integer.endpoint-decline.source-position")
            return null
        }
        val useLo = marker[LAZY_PAYLOAD + 1] == 1
        if ((source.op == LinearOp.LE && !useLo) || (source.op == LinearOp.GE && useLo)) return null
        val sourceRow = normalizedSource(source, useLo) ?: return null
        if ((sourceRow.coefficient(variable) > BIG_ZERO) == lower) return null
        state.arithmeticLearningTrace?.row("integer.endpoint-row", sourceRow, state, marker[2])
        val proposed = sourceRow.boundFor(state, variable, marker[2], budget)
        if (proposed == null) {
            state.arithmeticLearningTrace?.record("integer.endpoint-decline.unavailable-bound")
            return null
        }
        if (!state.rawIntegerEndpointSupported(variable, lower, proposed, marker[2])) {
            val domain = state.intDomains[variable]
            state.arithmeticLearningTrace?.record("integer.endpoint-decline.raw-endpoint-provenance", proposed,
                domain.min, domain.max, marker[2].toLong())
            return null
        }
        var combination = row.resolve(sourceRow, variable, budget) ?: run {
            state.arithmeticLearningTrace?.record("integer.endpoint-decline.combination-shape-or-guard")
            return null
        }
        var roundedReason = false
        if (combination.minimum(state, budget = budget) <= combination.bound) {
            val reduced = sourceRow.reduced(state, sourceRow.coefficient(variable).abs(), budget)
            reduced?.let { row.resolve(it, variable, budget) }?.let {
                combination = it
                roundedReason = true
            }
        }
        if (combination.minimum(state, budget = budget) <= combination.bound) return null
        state.arithmeticLearningTrace?.record("integer.eliminate", variable.toLong(), factorId.toLong(),
            marker[2].toLong(), if (roundedReason) 1L else 0L)
        return BoundEvent(sourceRow, marker[2], key, combination)
    }

    private class BoundEvent(
        val row: IntegerConflictRow,
        val trail: Int,
        val key: Pair<Int, Int>,
        val combination: IntegerConflictRow,
    )
}
