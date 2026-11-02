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
        val source = if (factorId in 0 until state.baseFactorCount) sourceFactor(factorId) else null
        val retained = if (source == null) retainedAmoSource(factorId) else null
        if (source == null && retained == null) return decline("seed-source")
        val allowWideSources = source != null && source.linear.vars.size > INTEGER_LEARNING_TERMS
        var retainedSources = if (retained == null) 0 else 1
        if (retained != null) {
            state.integerChainedSources++
            state.arithmeticLearningTrace?.record("integer.retained-seed", factorId.toLong())
        }
        var row: IntegerLearningInequality = if (source == null) requireNotNull(retained) else when (source.linear.op) {
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
        val used = mutableSetOf<PivotKey>()
        var transientSteps = 0
        repeat(INTEGER_LEARNING_STEPS) {
            state.checkFiniteWork()
            val event = reason(row, used, allowWideSources) ?: return decline("no-qualified-endpoint")
            used.add(event.key)
            if (event.key.retained) {
                retainedSources++
                state.integerChainedSources++
                state.arithmeticLearningTrace?.row("integer.retained-parent-row", event.row, state)
            } else state.arithmeticLearningTrace?.row("integer.pivot-row", event.row, state, event.trail)
            val combined = if (allowWideSources) {
                row.resolveTransient(event.row, event.variable, sourceWork)
            } else row.resolve(event.row, event.variable, sourceWork)
            combined ?: return decline("combination-shape-or-guard")
            row = combined
            if (combined.minimum(state) <= combined.bound) return decline("combination-lost-conflict")
            if (combined !is IntegerConflictRow) {
                transientSteps++
                return@repeat
            }
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
            if (retainedSources > 0) state.integerChainedProduced++
            return ConflictAnalyzer.AnalysisResult.LearnedIntegerRow(
                combined.derivedThroughTransient(transientSteps).derivedFromRetainedSources(retainedSources),
                target, levels.size, levels, fallback)
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
        allowWideSources: Boolean = true,
    ): IntegerSourceRow? = GuardedIntegerSource(state).source(factorId, trail, forBoundReason,
        if (allowWideSources) sourceWork else null)

    private fun reason(
        row: IntegerLearningInequality,
        used: Set<PivotKey>,
        allowWideSources: Boolean,
    ): BoundEvent? {
        var candidates: IntArray? = null
        var count = 0
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
            val key = PivotKey(variable, marker[2], lower, retained = false)
            if (key in used) continue
            val indices = candidates ?: IntArray(row.variables.size).also { candidates = it }
            var position = count
            while (position > 0) {
                row.visit(state)
                val previous = checkNotNull(endpointMarker(row, indices[position - 1]))
                if (previous[2] >= marker[2]) break
                indices[position] = indices[position - 1]
                position--
            }
            indices[position] = index
            count++
        }
        val indices = candidates ?: return retainedReason(row, used)
        for (position in 0 until count) {
            row.visit(state)
            qualifiedReason(row, indices[position], allowWideSources)?.let { return it }
        }
        return retainedReason(row, used)
    }

    private fun retainedAmoSource(factorId: Int): IntegerConflictRow? {
        sourceWork.visit()
        if (state.incremental || factorId !in state.baseFactorCount until state.totalFactorCount) return null
        val propagator = state.factorAt(factorId) as? LearnedIntegerPropagator ?: return null
        sourceWork.visit()
        val cache = state.learned.integerRows ?: return null
        val slot = propagator.retentionSlot
        if (slot !in cache.indices || cache[slot] !== propagator) return null
        val row = propagator.row
        if (row.guard != Lit.NONE || row.variables.size < 2) return null
        var negative = 0L
        val bounds = state.problem.intBounds
        for (index in row.variables.indices) {
            sourceWork.visit()
            val coefficient = row.coefficients[index]
            val variable = row.variables[index]
            if ((coefficient != 1L && coefficient != -1L) || !bounds.hasLower(variable) ||
                !bounds.hasUpper(variable) || bounds.lower(variable) != 0L || bounds.upper(variable) != 1L) return null
            if (coefficient < 0L) negative++
        }
        return row.takeIf { it.bound == 1L - negative }
    }

    private fun retainedReason(row: IntegerLearningInequality, used: Set<PivotKey>): BoundEvent? {
        for (index in row.variables.indices) {
            sourceWork.visit()
            val variable = row.variables[index]
            val lower = row.coefficients[index] > 0L
            val factorId = if (lower) state.intMinReason[variable] else state.intMaxReason[variable]
            val key = PivotKey(variable, factorId, lower, retained = true)
            if (key in used) continue
            val parent = retainedAmoSource(factorId) ?: continue
            var coefficient = 0L
            for (parentIndex in parent.variables.indices) {
                sourceWork.visit()
                if (parent.variables[parentIndex] == variable) {
                    coefficient = parent.coefficients[parentIndex]
                    break
                }
            }
            if (coefficient == 0L || (coefficient > 0L) == lower) continue
            // The owned inequality is a source in this state; the reason id only selects it.
            // No eager support is treated as a historical lazy endpoint.
            return BoundEvent(parent, variable, state.undo.size, key)
        }
        return null
    }

    private fun endpointMarker(row: IntegerLearningInequality, index: Int): IntArray? {
        val variable = row.variables[index]
        return if (row.coefficients[index] > 0L) state.intMinAntecedents[variable]
        else state.intMaxAntecedents[variable]
    }

    private fun qualifiedReason(
        row: IntegerLearningInequality,
        index: Int,
        allowWideSources: Boolean,
    ): BoundEvent? {
        val variable = row.variables[index]
        val lower = row.coefficients[index] > 0L
        val marker = checkNotNull(endpointMarker(row, index))
        val factorId = marker[1]
        val source = sourceFactor(
            factorId, marker[2], forBoundReason = true, allowWideSources = allowWideSources,
        )
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
        return BoundEvent(sourceRow, variable, marker[2], PivotKey(variable, marker[2], lower, retained = false))
    }

    private data class PivotKey(val variable: Int, val source: Int, val lower: Boolean, val retained: Boolean)

    private class BoundEvent(
        val row: IntegerLearningInequality,
        val variable: Int,
        val trail: Int,
        val key: PivotKey,
    )
}
