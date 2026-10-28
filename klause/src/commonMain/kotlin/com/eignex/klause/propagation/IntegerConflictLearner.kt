package com.eignex.klause.propagation

import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.util.CheckedLongOverflowException

internal const val INTEGER_LEARNING_ROWS = 64
private const val INTEGER_LEARNING_STEPS = 16
private const val INTEGER_LEARNING_LEVELS = 64

internal class IntegerConflictLearner(private val state: PropagationState) {
    fun learn(
        factorId: Int,
        currentLevel: Int,
        fallback: ConflictAnalyzer.AnalysisResult.Learned,
    ): ConflictAnalyzer.AnalysisResult.LearnedIntegerRow? = try {
        derive(factorId, currentLevel, fallback)
    } catch (_: CheckedLongOverflowException) {
        null
    }

    private fun derive(
        factorId: Int,
        currentLevel: Int,
        fallback: ConflictAnalyzer.AnalysisResult.Learned,
    ): ConflictAnalyzer.AnalysisResult.LearnedIntegerRow? {
        if (currentLevel !in 1..INTEGER_LEARNING_LEVELS || !fallback.asserting || fallback.literals.isEmpty()) {
            return null
        }
        if ((state.learned.integerRows?.size ?: 0) >= INTEGER_LEARNING_ROWS) return null
        val source = sourceFactor(factorId) ?: return null
        var row = when (source.linear.op) {
            LinearOp.LE -> source.row(useLo = true)
            LinearOp.GE -> source.row(useLo = false)
            LinearOp.EQ -> {
                val lower = source.row(useLo = true) ?: return null
                if (lower.minimum(state) > lower.bound) lower else source.row(useLo = false)
            }
            LinearOp.NE -> null
        } ?: return null
        if (!row.holeFree(state) || row.minimum(state) <= row.bound) return null
        val used = mutableSetOf<Pair<Int, Int>>()
        repeat(INTEGER_LEARNING_STEPS) {
            state.checkFiniteWork()
            val event = reason(row, used) ?: return null
            used.add(event.key)
            row = row.resolve(event.row, event.variable) ?: return null
            if (!row.holeFree(state) || row.minimum(state) <= row.bound) return null
            if (state.learned.integerRows?.any { it.row.sameAs(row) } == true) return null
            for (level in 0 until currentLevel) {
                val trail = state.integerLearningTrailAtLevel?.invoke(level) ?: continue
                if (!row.assertsAt(state, trail)) continue
                val levels = row.variables.indices.map { index ->
                    val variable = row.variables[index]
                    val lower = row.coefficients[index] > 0L
                    val domain = state.intDomains[variable]
                    state.boundEstablishmentLevel(variable, if (lower) domain.min else domain.max, lower) ?: 0
                }.plus(fallback.decisionLevels.toList()).distinct().sorted().toIntArray()
                return ConflictAnalyzer.AnalysisResult.LearnedIntegerRow(row, level, levels.size, levels, fallback)
            }
        }
        return null
    }

    private fun sourceFactor(
        factorId: Int,
        trail: Int = state.undo.size,
        forBoundReason: Boolean = false,
    ): IntegerSourceRow? = GuardedIntegerSource(state).source(factorId, trail, forBoundReason)

    private fun reason(row: IntegerConflictRow, used: Set<Pair<Int, Int>>): BoundEvent? {
        var latest: BoundEvent? = null
        for (index in row.variables.indices) {
            state.checkFiniteWork()
            val variable = row.variables[index]
            val lower = row.coefficients[index] > 0L
            val factorId = if (lower) state.intMinReason[variable] else state.intMaxReason[variable]
            val marker = if (lower) state.intMinAntecedents[variable] else state.intMaxAntecedents[variable]
            if (!isLazyReason(marker)) continue
            marker ?: continue
            if (marker.size != LAZY_PAYLOAD + 6 || marker[1] != factorId) continue
            val key = variable to if (lower) marker[2] else marker[2].inv()
            if (key in used || (latest != null && marker[2] <= latest.trail)) continue
            val source = sourceFactor(factorId, marker[2], forBoundReason = true) ?: continue
            val extra = marker[LAZY_PAYLOAD + 5] != 0
            if (extra != (source.guard != Lit.NONE)) continue
            if (extra && marker[LAZY_PAYLOAD + 4] != (source.guard xor 1)) continue
            val position = marker[LAZY_PAYLOAD]
            if (source.linear.vars.getOrNull(position) != variable) continue
            val useLo = marker[LAZY_PAYLOAD + 1] == 1
            if ((source.linear.op == LinearOp.LE && !useLo) || (source.linear.op == LinearOp.GE && useLo)) continue
            val sourceRow = source.row(useLo) ?: continue
            if ((sourceRow.coefficient(variable) > 0L) == lower) continue
            if (!sourceRow.holeFree(state, marker[2])) continue
            val proposed = sourceRow.boundFor(state, variable, marker[2]) ?: continue
            val domain = state.intDomains[variable]
            if ((lower && proposed < domain.min) || (!lower && proposed > domain.max)) continue
            latest = BoundEvent(sourceRow, variable, marker[2], key)
        }
        return latest
    }

    private class BoundEvent(val row: IntegerConflictRow, val variable: Int, val trail: Int, val key: Pair<Int, Int>)
}
