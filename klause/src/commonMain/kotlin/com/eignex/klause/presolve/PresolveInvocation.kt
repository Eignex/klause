package com.eignex.klause.presolve

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.result.PresolveInvocationOutcome
import com.eignex.klause.solver.result.PresolveInvocationStats
import com.eignex.klause.solver.result.PresolveLane
import com.eignex.klause.solver.result.PresolvePreparationStage
import com.eignex.klause.solver.result.PresolvePreparationStats
import com.eignex.klause.solver.result.PresolveSizeStats
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.IntHashSet
import kotlin.time.Duration
import kotlin.time.TimeSource

internal class PresolveEntrySize(private val budget: PresolveBudget?) {
    private var factors: Array<Factor>? = null
    private var entries = 0L
    var booleanPins: IntHashSet? = null
        private set

    fun units(problem: Problem, token: Cancellation): Long {
        if (budget == null && token.workMeter() == null) return 0L
        if (factors !== problem.factors) {
            entries = budget?.entryUnits(problem.factors) ?: passBaseUnits(problem.factors)
            booleanPins = budget?.entryBooleanPins
            factors = problem.factors
        }
        return entries
    }

    fun isBooleanPinned(variable: Int): Boolean = booleanPins?.contains(variable) == true
}

internal class PresolveBooleanPins {
    private val reported = IntHashSet()
    private var initial: IntHashSet? = null

    fun seed(pins: IntHashSet?) {
        if (initial == null) initial = pins
    }

    fun record(invocation: PresolveInvocation?, added: List<Factor>, alreadyFixed: (Int) -> Boolean) {
        if (invocation == null) return
        for (factor in added) {
            if (factor !is Clause || factor.literals.size != 1) continue
            val variable = Lit.variable(factor.literals[0])
            if (variable >= invocation.input.booleans || initial?.contains(variable) == true) continue
            if (reported.add(variable) && !alreadyFixed(variable)) invocation.fixedColumns++
        }
    }
}

internal class PresolveInvocation(
    val pass: String,
    val lane: PresolveLane,
    var input: PresolveSizeStats,
) {
    val start = TimeSource.Monotonic.markNow()
    var work = 0L
    var rebuildWork = 0L
    var nativeWork = 0L
    var childrenElapsed = Duration.ZERO
    var outcome = PresolveInvocationOutcome.COMPLETE_MISS
    var interrupted = false
    var narrowedColumns = 0
    var fixedColumns = 0
    var outputOccurrences: Long? = null
    var structuralImpact: Map<String, Long> = emptyMap()
}

internal fun Problem.presolveSize() = PresolveSizeStats(factors.size, numBoolVars, numIntVars, reals = numRealVars)

internal fun <T> PresolveBudget?.observe(
    pass: PresolvePass,
    lane: PresolveLane,
    size: () -> PresolveSizeStats,
    action: (PresolveInvocation?) -> T,
): T = observe(pass.id, lane, size, null, action)

internal fun <T> PresolveBudget?.observe(
    id: String,
    lane: PresolveLane,
    size: () -> PresolveSizeStats,
    preparationStage: PresolvePreparationStage?,
    action: (PresolveInvocation?) -> T,
): T {
    if (this == null) return action(null)
    val invocation = PresolveInvocation(id, lane, size())
    if (preparationStage != null) invocation.outcome = PresolveInvocationOutcome.COMPLETE
    val parent = activeInvocation
    activeInvocation = invocation
    var completed = false
    try {
        return action(invocation).also { completed = true }
    } finally {
        val elapsed = invocation.start.elapsedNow()
        activeInvocation = parent
        parent?.let { it.childrenElapsed += elapsed }
        val outcome = if (
            (!completed || invocation.interrupted) && invocation.outcome != PresolveInvocationOutcome.REFUTED
        ) {
            PresolveInvocationOutcome.INTERRUPTED
        } else invocation.outcome
        val output = size().let {
            PresolveSizeStats(it.factors, it.booleans, it.integers, invocation.outputOccurrences, it.reals)
        }
        if (preparationStage != null) {
            recordPreparation(PresolvePreparationStats(
                preparationStage, lane, outcome, elapsed - invocation.childrenElapsed,
                invocation.work, invocation.nativeWork, invocation.input, output,
            ))
        } else recordInvocation(PresolveInvocationStats(
            pass = invocation.pass,
            lane = invocation.lane,
            outcome = outcome,
            elapsed = elapsed - invocation.childrenElapsed,
            work = invocation.work,
            rebuildWork = invocation.rebuildWork,
            input = invocation.input,
            output = output,
            narrowedColumns = invocation.narrowedColumns,
            fixedColumns = invocation.fixedColumns,
            nativeWork = invocation.nativeWork,
            structuralImpact = invocation.structuralImpact,
        ))
    }
}

internal fun PresolveInvocation?.record(outcome: PassOutcome) {
    this?.outcome = when (outcome) {
        PassOutcome.UNCHANGED -> PresolveInvocationOutcome.COMPLETE_MISS
        PassOutcome.SKIPPED -> PresolveInvocationOutcome.ELIGIBILITY_SKIP
        PassOutcome.INTERRUPTED -> PresolveInvocationOutcome.INTERRUPTED
        PassOutcome.CHANGED -> PresolveInvocationOutcome.CHANGED
        PassOutcome.INFEASIBLE -> PresolveInvocationOutcome.REFUTED
    }
}

internal fun PresolveBudget?.observeRoundPass(
    pass: PresolvePass,
    lane: PresolveLane,
    cancellation: Cancellation,
    size: () -> PresolveSizeStats,
    eligible: () -> Boolean,
    action: (PresolveInvocation?) -> PassOutcome,
): PassOutcome = observe(pass, lane, size) { invocation ->
    val outcome = when {
        !eligible() -> PassOutcome.SKIPPED
        cancellation() -> PassOutcome.UNCHANGED
        else -> action(invocation)
    }
    val result = if (outcome == PassOutcome.UNCHANGED && invocation?.interrupted == true) {
        PassOutcome.INTERRUPTED
    } else outcome
    invocation.record(result)
    result
}

internal fun <T> PresolveBudget?.observeTransform(
    pass: PresolvePass,
    input: Problem,
    cancellation: Cancellation,
    eligible: () -> Boolean,
    unchanged: () -> T,
    output: (T) -> Problem,
    refuted: (T) -> Boolean = { false },
    action: () -> T,
): T {
    var current = input
    return observe(pass, PresolveLane.FINITE, { current.presolveSize() }) { invocation ->
        val result = when {
            !pass.namespaceEligible(input) -> {
                invocation?.outcome = PresolveInvocationOutcome.ELIGIBILITY_SKIP
                unchanged()
            }
            cancellation() -> unchanged()
            !eligible() -> {
                invocation?.outcome = PresolveInvocationOutcome.ELIGIBILITY_SKIP
                unchanged()
            }
            else -> {
                val units = PresolveEntrySize(this).units(input, cancellation)
                invocation.recordEntries(units)
                cancellation.charge(pass.baseWorkWeight * units)
                action()
            }
        }
        current = output(result)
        if (current !== input) invocation?.outputOccurrences = null
        when {
            refuted(result) -> invocation?.outcome = PresolveInvocationOutcome.REFUTED
            current !== input -> invocation?.outcome = PresolveInvocationOutcome.CHANGED
        }
        result
    }
}

internal fun Cancellation.interruptPresolveInvocation() {
    presolveBudget()?.activeInvocation?.outcome = PresolveInvocationOutcome.INTERRUPTED
}

internal fun PresolveInvocation?.recordEntries(units: Long) {
    if (this == null) return
    val occurrences = units - input.factors
    input = PresolveSizeStats(input.factors, input.booleans, input.integers, occurrences, input.reals)
    outputOccurrences = occurrences
}

internal fun PresolveInvocation?.recordDelta(inputFactors: Array<Factor>, dropped: IntArray, added: List<Factor>) {
    if (this == null) return
    var occurrences = outputOccurrences ?: return
    for (id in dropped) occurrences -= inputFactors[id].structuralKeyWeight
    for (factor in added) occurrences += factor.structuralKeyWeight
    outputOccurrences = occurrences
}
