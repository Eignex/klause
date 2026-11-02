package com.eignex.klause.propagation

import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.util.CheckedLongOverflowException

internal const val INTEGER_LEARNING_ROWS = 64
private const val INTEGER_LEARNING_STEPS = 16
private const val INTEGER_LEARNING_LEVELS = 64

internal class IntegerConflictLearner(private val state: PropagationState) {
    private var previousSource: IntegerSourceRow? = null
    private var previousUseLo = false
    private var previousRow: IntegerLearningInequality? = null
    private val sourceWork = IntegerSourceWork(state)
    private var previousWideRowSource: IntegerSourceRow? = null
    private var previousWideUseLo = false
    private var previousWideRow: WideIntegerSourceInequality? = null

    fun learn(
        factorId: Int,
        currentLevel: Int,
        fallback: ConflictAnalyzer.AnalysisResult.Learned,
    ): ConflictAnalyzer.AnalysisResult.LearnedIntegerRow? = try {
        derive(factorId, currentLevel, fallback)
    } catch (_: CheckedLongOverflowException) {
        state.arithmeticLearningTrace?.record("integer.decline.overflow")
        null
    } catch (_: IntegerSourceWorkLimit) {
        state.arithmeticLearningTrace?.record("integer.decline.source-work-limit")
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
            val combined = row.resolve(event.row, event.variable, sourceWork)
                ?: return decline("combination-shape-or-guard")
            row = combined
            if (combined.minimum(state) <= combined.bound) return decline("combination-lost-conflict")
            if (state.learned.integerRows?.any { it.row.subsumes(combined) } == true) return decline("duplicate")
            val asserts = combined.assertsAt(state, trail)
            state.arithmeticLearningTrace?.record("integer.assertion", target.toLong(), trail.toLong(),
                if (asserts) 1 else 0)
            if (!asserts) return@repeat
            val levels = combined.variables.indices.map { index ->
                val variable = combined.variables[index]
                val lower = combined.coefficients[index] > 0L
                val domain = state.intDomains[variable]
                state.boundEstablishmentLevel(variable, if (lower) domain.min else domain.max, lower) ?: 0
            }.plus(fallback.decisionLevels.toList()).distinct().sorted().toIntArray()
            return ConflictAnalyzer.AnalysisResult.LearnedIntegerRow(combined, target, levels.size, levels, fallback)
        }
        return decline("step-limit-without-assertion")
    }

    private fun normalizedSource(source: IntegerSourceRow, useLo: Boolean): IntegerLearningInequality? {
        if (source.linear.vars.size > INTEGER_LEARNING_TERMS) {
            sourceWork.visit()
            if (previousWideRowSource?.linear === source.linear && previousWideRowSource?.guard == source.guard &&
                previousWideUseLo == useLo) return previousWideRow
            val row = WideIntegerSourceInequality.create(source, useLo, sourceWork)
            previousWideRowSource = source
            previousWideUseLo = useLo
            previousWideRow = row
            return row
        }
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
    ): IntegerSourceRow? = GuardedIntegerSource(state).source(factorId, trail, forBoundReason, sourceWork)

    private fun reason(row: IntegerLearningInequality, used: Set<Pair<Int, Int>>): BoundEvent? {
        var latest: BoundEvent? = null
        for (index in row.variables.indices) {
            row.visit(state)
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
            if (key in used || (latest != null && marker[2] <= latest.trail)) continue
            val source = sourceFactor(factorId, marker[2], forBoundReason = true)
            if (source == null) {
                state.arithmeticLearningTrace?.record("integer.endpoint-decline.source", factorId.toLong())
                continue
            }
            val extra = marker[LAZY_PAYLOAD + 5] != 0
            if (extra != (source.guard != Lit.NONE) ||
                (extra && marker[LAZY_PAYLOAD + 4] != (source.guard xor 1))) {
                state.arithmeticLearningTrace?.record("integer.endpoint-decline.guard-marker", source.guard.toLong())
                continue
            }
            val position = marker[LAZY_PAYLOAD]
            if (source.linear.vars.getOrNull(position) != variable) {
                state.arithmeticLearningTrace?.record("integer.endpoint-decline.source-position")
                continue
            }
            val useLo = marker[LAZY_PAYLOAD + 1] == 1
            if ((source.linear.op == LinearOp.LE && !useLo) || (source.linear.op == LinearOp.GE && useLo)) continue
            val sourceRow = normalizedSource(source, useLo) ?: continue
            if ((sourceRow.coefficient(variable) > 0L) == lower) continue
            state.arithmeticLearningTrace?.row("integer.endpoint-row", sourceRow, state, marker[2])
            val proposed = sourceRow.boundFor(state, variable, marker[2])
            if (proposed == null) {
                state.arithmeticLearningTrace?.record("integer.endpoint-decline.unavailable-bound")
                continue
            }
            if (!state.rawIntegerEndpointSupported(variable, lower, proposed, marker[2])) {
                val domain = state.intDomains[variable]
                state.arithmeticLearningTrace?.record("integer.endpoint-decline.raw-endpoint-provenance", proposed,
                    domain.min, domain.max, marker[2].toLong())
                continue
            }
            latest = BoundEvent(sourceRow, variable, marker[2], key)
        }
        return latest
    }

    private class BoundEvent(
        val row: IntegerLearningInequality,
        val variable: Int,
        val trail: Int,
        val key: Pair<Int, Int>,
    )
}
