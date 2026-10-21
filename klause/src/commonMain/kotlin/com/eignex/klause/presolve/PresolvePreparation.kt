package com.eignex.klause.presolve

import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.result.PresolveInvocationOutcome
import com.eignex.klause.solver.result.PresolveLane
import com.eignex.klause.solver.result.PresolvePreparationStage
import com.eignex.klause.util.Cancellation

internal fun <T> PresolveBudget?.observePreparation(
    stage: PresolvePreparationStage,
    lane: PresolveLane,
    input: Problem,
    cancellation: Cancellation,
    output: (T) -> Problem = { input },
    action: (Cancellation, PresolveInvocation?) -> T,
): T {
    var current = input
    return observe(stage.name, lane, { current.presolveSize() }, stage) { invocation ->
        val token = this?.preparationCancellation(cancellation) ?: cancellation
        val result = action(token, invocation)
        current = output(result)
        result
    }
}

internal fun PresolveInvocation?.preparationInterrupted() {
    this?.interrupted = true
}

internal fun PresolveInvocation?.preparationRefuted() {
    this?.outcome = PresolveInvocationOutcome.REFUTED
}
