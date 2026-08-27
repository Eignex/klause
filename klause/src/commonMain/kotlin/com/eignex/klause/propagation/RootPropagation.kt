package com.eignex.klause.propagation

import com.eignex.klause.config.KlauseConfig
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.solver.Problem
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.LongArrayList

/**
 * Runs the propagation engine over an immutable `Problem` and reports deductions beyond [assumptions].
 *
 * This belongs to propagation rather than `Problem`: creating a [PropagationState], scheduling
 * propagators, and extracting conflicts are propagation-engine work. The model owns only the data
 * used to build that projection.
 */
internal fun runRootPropagation(
    problem: Problem,
    assumptions: Assumptions,
    cancellation: Cancellation,
    skipExpensiveBake: Boolean,
): PropagationResult {
    val state = PropagationState(PropagationProblem(problem), assumptions)
    if (!state.seeded) {
        val levels = state.conflictLevels ?: EmptyIntArray
        return PropagationResult.Unsat(
            state.extractConflictBools(levels),
            state.extractConflictInts(levels),
            levels,
        )
    }
    val conflict = state.runToFixpoint(
        allFactors = true,
        cancellation = cancellation,
        skipExpensiveBake = skipExpensiveBake,
    )
    if (conflict != null) {
        return PropagationResult.Unsat(
            state.extractConflictBools(conflict),
            state.extractConflictInts(conflict),
            conflict,
            state.extractConflictFactors(),
        )
    }

    val boolKeys = IntArrayList(initialCapacity = 8)
    val boolValues = ArrayList<Boolean>()
    for (variable in 0 until problem.numBoolVars) {
        val value = state.boolValues[variable] ?: continue
        if (assumptions.boolValueOrNull(variable) == value) continue
        boolKeys.add(variable)
        boolValues.add(value)
    }
    val intKeys = IntArrayList(initialCapacity = 8)
    val intValues = LongArrayList(initialCapacity = 8)
    val intMinKeys = IntArrayList(initialCapacity = 8)
    val intMinValues = LongArrayList(initialCapacity = 8)
    val intMaxKeys = IntArrayList(initialCapacity = 8)
    val intMaxValues = LongArrayList(initialCapacity = 8)
    val intHoleIds = IntArrayList(initialCapacity = 8)
    val intHoleValues = LongArrayList(initialCapacity = 8)
    val intSetKeys = IntArrayList(initialCapacity = 4)
    val intSetOffsets = IntArrayList(initialCapacity = 4).also { it.add(0) }
    val intSetValues = LongArrayList(initialCapacity = 8)
    for (variable in 0 until problem.numIntVars) {
        val domain = state.intDomains[variable]
        if (domain.min == domain.max) {
            if (assumptions.intValueOrNull(variable) == domain.min) continue
            intKeys.add(variable)
            intValues.add(domain.min)
            continue
        }
        val span = domain.max - domain.min + 1
        val values = if (span > KlauseConfig.current.bitsetThreshold) domain.spanOrNull() else null
        if (values != null && values.size <= domain.holeCount) {
            intSetKeys.add(variable)
            values.forEach { intSetValues.add(it) }
            intSetOffsets.add(intSetValues.size)
            continue
        }
        val original = problem.requireFiniteIntDomains()[variable]
        val seedMin = maxOf(original.min, assumptions.deductions.intMinOrNull(variable) ?: Long.MIN_VALUE)
        val seedMax = minOf(original.max, assumptions.deductions.intMaxOrNull(variable) ?: Long.MAX_VALUE)
        if (domain.min > seedMin) {
            intMinKeys.add(variable)
            intMinValues.add(domain.min)
        }
        if (domain.max < seedMax) {
            intMaxKeys.add(variable)
            intMaxValues.add(domain.max)
        }
        domain.forEachHole { value ->
            if (value in original) {
                var alreadyAssumed = false
                for (i in 0 until assumptions.deductions.intHoleVarIds.size) {
                    if (assumptions.deductions.intHoleVarIds[i] == variable &&
                        assumptions.deductions.intHoleValues[i] == value
                    ) {
                        alreadyAssumed = true
                        break
                    }
                }
                if (!alreadyAssumed) {
                    intHoleIds.add(variable)
                    intHoleValues.add(value)
                }
            }
        }
    }
    return PropagationResult.Implied(
        boolKeys = boolKeys.toIntArray(),
        boolValues = BooleanArray(boolValues.size) { boolValues[it] },
        intKeys = intKeys.toIntArray(),
        intValues = intValues.toLongArray(),
        intMinKeys = intMinKeys.toIntArray(),
        intMinValues = intMinValues.toLongArray(),
        intMaxKeys = intMaxKeys.toIntArray(),
        intMaxValues = intMaxValues.toLongArray(),
        intHoleVarIds = intHoleIds.toIntArray(),
        intHoleValues = intHoleValues.toLongArray(),
        intSetKeys = intSetKeys.toIntArray(),
        intSetOffsets = if (intSetKeys.isEmpty()) EmptyIntArray else intSetOffsets.toIntArray(),
        intSetValues = intSetValues.toLongArray(),
    )
}

/** Run the construction-time propagation bake and merge deductions supplied by presolve. */
internal fun rootBake(
    problem: Problem,
    seedDeductions: PropagationResult,
    cancellation: Cancellation,
): PropagationResult {
    val base = runRootPropagation(
        problem,
        Assumptions.None,
        cancellation,
        skipExpensiveBake = true,
    )
    return when {
        base is PropagationResult.Unsat -> base
        seedDeductions is PropagationResult.Unsat -> seedDeductions
        seedDeductions === PropagationResult.Implied.EMPTY -> base
        else -> (base as PropagationResult.Implied).merge(seedDeductions as PropagationResult.Implied)
    }
}
