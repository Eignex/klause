package com.eignex.klause.lp.engine

internal enum class SimplexProfilePhase {
    PRICING, FTRAN, BTRAN, UPDATE, REFACTOR, FEASIBILITY_REPAIR, OUTPUT,
}

internal enum class SimplexPhase { DUAL, BOXED_DUAL, PRIMAL_FEASIBILITY, PRIMAL_OBJECTIVE }

internal enum class SimplexBasisOperation { DENSE_FTRAN, DENSE_BTRAN, ENTERING_FTRAN, PIVOTAL_BTRAN }

internal data class SimplexDualStart(
    val column: Int,
    val status: VarStatus,
    val scaledReducedCost: Double,
    val hasLower: Boolean,
    val hasUpper: Boolean,
    val entries: Int,
    val singletonCoefficient: Double?,
    val singletonFixedLogical: Boolean?,
)

internal data class SimplexProgressSample(
    val phase: SimplexPhase,
    val pivots: Int,
    val work: Long,
    val scaledObjective: Double,
    val scaledInfeasibility: Double,
    val scaledMaxViolation: Double,
    val violatedBasics: Int,
)

internal class SimplexProgressTrace(val profile: Boolean = false) {
    val phaseNanos = mutableMapOf<SimplexProfilePhase, Long>()
    val phaseCalls = mutableMapOf<SimplexProfilePhase, Long>()
    var sourcePrimalResidual: Double? = null
    var sourceBoundViolation: Double? = null
    var sourceBasicDualResidual: Double? = null

    fun recordPhase(phase: SimplexProfilePhase, nanos: Long) {
        phaseNanos[phase] = phaseNanos.getOrElse(phase) { 0L } + nanos
        phaseCalls[phase] = phaseCalls.getOrElse(phase) { 0L } + 1L
    }

    val samples = mutableListOf<SimplexProgressSample>()
    val iterations = mutableMapOf<SimplexPhase, Int>()
    val degenerateSteps = mutableMapOf<SimplexPhase, Int>()
    val boundFlips = mutableMapOf<SimplexPhase, Int>()
    val basisSolves = mutableMapOf<SimplexBasisOperation, Int>()
    val basisWork = mutableMapOf<SimplexBasisOperation, Long>()
    var pricedColumns = 0L
    var lastEnteringGain = 0.0
    var lastStep = 0.0
    var passedPhaseOneBreakpoints = 0L
    var primalValueUpdates = 0
    var dualStart: SimplexDualStart? = null

    fun basisSolve(operation: SimplexBasisOperation, work: Long) {
        basisSolves[operation] = basisSolves.getOrElse(operation) { 0 } + 1
        basisWork[operation] = basisWork.getOrElse(operation) { 0L } + work
    }

    fun iteration(phase: SimplexPhase): Boolean {
        val count = iterations.getOrElse(phase) { 0 }
        iterations[phase] = count + 1
        return count % 16 == 0 && samples.size < 256
    }

    fun step(phase: SimplexPhase, length: Double, tolerance: Double, flip: Boolean = false) {
        lastStep = length
        if (length <= tolerance) degenerateSteps[phase] = degenerateSteps.getOrElse(phase) { 0 } + 1
        if (flip) boundFlips[phase] = boundFlips.getOrElse(phase) { 0 } + 1
    }
}
