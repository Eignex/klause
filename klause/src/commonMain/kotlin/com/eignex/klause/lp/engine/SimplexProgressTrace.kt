package com.eignex.klause.lp.engine

internal enum class SimplexPhase { DUAL, BOXED_DUAL, PRIMAL_FEASIBILITY, PRIMAL_OBJECTIVE }

internal data class SimplexProgressSample(
    val phase: SimplexPhase,
    val pivots: Int,
    val work: Long,
    val scaledObjective: Double,
    val scaledInfeasibility: Double,
    val scaledMaxViolation: Double,
    val violatedBasics: Int,
)

internal class SimplexProgressTrace {
    val samples = mutableListOf<SimplexProgressSample>()
    val iterations = mutableMapOf<SimplexPhase, Int>()
    val degenerateSteps = mutableMapOf<SimplexPhase, Int>()
    val boundFlips = mutableMapOf<SimplexPhase, Int>()
    var pricedColumns = 0L
    var lastEnteringGain = 0.0
    var lastStep = 0.0

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
