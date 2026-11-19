package com.eignex.klause.propagation

import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.util.numbers.CheckedLongOverflowException

internal const val INTEGER_LEARNING_ROWS = 64
private const val INTEGER_LEARNING_STEPS = 16
private const val INTEGER_LEARNING_LEVELS = 64

internal class IntegerConflictLearner(private val state: PropagationState) {
    private var previousSource: IntegerSourceRow? = null
    private var previousUseLo = false
    private var previousRow: IntegerConflictRow? = null
    private var combinations = 0

    fun learn(
        factorId: Int,
        currentLevel: Int,
        fallback: ConflictAnalyzer.AnalysisResult.Learned,
    ): ConflictAnalyzer.AnalysisResult.LearnedIntegerRow? = try {
        derive(factorId, currentLevel, fallback)
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
        if (currentLevel !in 1..INTEGER_LEARNING_LEVELS) {
            state.arithmeticLearningTrace?.record("integer.level-limit", currentLevel.toLong(), factorId.toLong(),
                state.baseFactorCount.toLong(), fallback.backjumpLevel.toLong(),
                if (fallback.asserting) 1L else 0L, fallback.literals.size.toLong())
            val originalSeed = factorId in 0 until state.baseFactorCount
            return decline(if (originalSeed) "level-limit-original" else "level-limit-learned")
        }
        if (!fallback.asserting) return decline("fallback-nonasserting")
        if (fallback.literals.isEmpty()) return decline("fallback-empty")
        if (fallback.backjumpLevel !in 0 until currentLevel) return decline("fallback-target")
        if ((state.learned.integerRows?.size ?: 0) >= INTEGER_LEARNING_ROWS) return decline("store-capacity")
        val target = fallback.backjumpLevel
        val trail = state.integerLearningTrailAtLevel?.invoke(target) ?: return decline("assertion-unavailable")
        state.arithmeticLearningTrace?.record("integer.fallback-target", target.toLong(), trail.toLong())
        val source = sourceFactor(factorId) ?: return decline("seed-source")
        var row = when (source.linear.op) {
            LinearOp.LE -> normalizedSource(source, useLo = true)
            LinearOp.GE -> normalizedSource(source, useLo = false)
            LinearOp.EQ -> {
                val lower = normalizedSource(source, useLo = true) ?: return decline("seed-normalization")
                if (lower.minimum(state) > lower.bound) lower else normalizedSource(source, useLo = false)
            }
            LinearOp.NE -> null
        } ?: return decline("seed-normalization")
        state.arithmeticLearningTrace?.row("integer.seed-row", row, state)
        if (row.minimum(state) <= row.bound) return decline("seed-not-conflicting")
        val used = mutableSetOf<Pair<Int, Int>>()
        repeat(INTEGER_LEARNING_STEPS) {
            state.checkFiniteWork()
            val event = reason(row, used) ?: return decline("no-qualified-endpoint")
            used.add(event.key)
            state.arithmeticLearningTrace?.row("integer.pivot-row", event.row, state, event.trail)
            row = event.combination
            if (row.minimum(state) <= row.bound) return decline("combination-lost-conflict")
            if (state.learned.integerRows?.any { it.row.subsumes(row) } == true) return decline("duplicate")
            val asserts = row.assertsAt(state, trail)
            state.arithmeticLearningTrace?.record("integer.assertion", target.toLong(), trail.toLong(),
                if (asserts) 1 else 0)
            if (!asserts) return@repeat
            val levels = row.variables.indices.map { index ->
                val variable = row.variables[index]
                val lower = row.coefficients[index] > 0L
                val domain = state.intDomains[variable]
                state.boundEstablishmentLevel(variable, if (lower) domain.min else domain.max, lower) ?: 0
            }.plus(fallback.decisionLevels.toList()).distinct().sorted().toIntArray()
            return ConflictAnalyzer.AnalysisResult.LearnedIntegerRow(row, target, levels.size, levels, fallback)
        }
        return decline("step-limit-without-assertion")
    }

    private fun normalizedSource(source: IntegerSourceRow, useLo: Boolean): IntegerConflictRow? {
        val previous = previousSource
        if (previous != null && previous.linear === source.linear && previous.guard == source.guard &&
            previousUseLo == useLo) {
            return previousRow
        }
        val row = source.row(useLo)
        previousSource = source
        previousUseLo = useLo
        previousRow = row
        return row
    }

    private fun sourceFactor(
        factorId: Int,
        trail: Int = state.undo.size,
        forBoundReason: Boolean = false,
    ): IntegerSourceRow? = GuardedIntegerSource(state).source(factorId, trail, forBoundReason)

    private fun reason(row: IntegerConflictRow, used: Set<Pair<Int, Int>>): BoundEvent? {
        if (combinations == INTEGER_LEARNING_STEPS) return null
        var candidates: IntArray? = null
        var count = 0
        for (index in row.variables.indices) {
            state.checkFiniteWork()
            val variable = row.variables[index]
            val lower = row.coefficients[index] > 0L
            val factorId = if (lower) state.intMinReason[variable] else state.intMaxReason[variable]
            val marker = if (lower) state.intMinAntecedents[variable] else state.intMaxAntecedents[variable]
            state.arithmeticLearningTrace?.marker(variable, lower, marker)
            if (!isLazyReason(marker)) {
                state.arithmeticLearningTrace?.record("integer.endpoint-decline.eager-or-decision")
                continue
            }
            marker ?: continue
            if (marker.size != LAZY_PAYLOAD + 6 || marker[1] != factorId) {
                state.arithmeticLearningTrace?.record("integer.endpoint-decline.marker-shape")
                continue
            }
            val key = variable to if (lower) marker[2] else marker[2].inv()
            if (key in used) continue
            val indices = candidates ?: IntArray(row.variables.size).also { candidates = it }
            var position = count
            while (position > 0) {
                state.checkFiniteWork()
                val previous = checkNotNull(endpointMarker(row, indices[position - 1]))
                if (previous[2] >= marker[2]) break
                indices[position] = indices[position - 1]
                position--
            }
            indices[position] = index
            count++
        }
        val indices = candidates ?: return null
        for (position in 0 until count) {
            state.checkFiniteWork()
            if (combinations == INTEGER_LEARNING_STEPS) return null
            qualifiedReason(row, indices[position])?.let { return it }
        }
        return null
    }

    private fun endpointMarker(row: IntegerConflictRow, index: Int): IntArray? {
        val variable = row.variables[index]
        return if (row.coefficients[index] > 0L) state.intMinAntecedents[variable]
        else state.intMaxAntecedents[variable]
    }

    private fun qualifiedReason(row: IntegerConflictRow, index: Int): BoundEvent? {
        val variable = row.variables[index]
        val lower = row.coefficients[index] > 0L
        val marker = checkNotNull(endpointMarker(row, index))
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
        if (source.linear.vars.getOrNull(position) != variable) {
            state.arithmeticLearningTrace?.record("integer.endpoint-decline.source-position")
            return null
        }
        val useLo = marker[LAZY_PAYLOAD + 1] == 1
        if ((source.linear.op == LinearOp.LE && !useLo) || (source.linear.op == LinearOp.GE && useLo)) return null
        val sourceRow = normalizedSource(source, useLo) ?: return null
        if ((sourceRow.coefficient(variable) > 0L) == lower) return null
        state.arithmeticLearningTrace?.row("integer.endpoint-row", sourceRow, state, marker[2])
        val proposed = sourceRow.boundFor(state, variable, marker[2])
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
        combinations++
        val combination = row.resolve(sourceRow, variable)
        if (combination == null) {
            state.arithmeticLearningTrace?.record("integer.endpoint-decline.combination-shape-or-guard")
            return null
        }
        return BoundEvent(sourceRow, marker[2], key, combination)
    }

    private class BoundEvent(
        val row: IntegerConflictRow,
        val trail: Int,
        val key: Pair<Int, Int>,
        val combination: IntegerConflictRow,
    )
}
