package com.eignex.klause.localsearch.schedule

internal enum class ProgressObservation { Unchanged, Improved, Rewound, NonImproving }

internal class ProgressWindow(
    private val initialProgressStep: Long = 0L,
    private val trackFraction: Boolean = false,
) {
    private var lastSeenStep = -1L
    private var lastProgressStep = initialProgressStep
    private var bestCost = Long.MAX_VALUE
    private var bestFraction = 0.0

    fun observe(step: Long, cost: Long, fraction: Double = 0.0): ProgressObservation {
        if (step == lastSeenStep) return ProgressObservation.Unchanged
        val rewound = step < lastSeenStep
        lastSeenStep = step
        if (rewound || cost < bestCost || (trackFraction && cost == bestCost && fraction < bestFraction)) {
            lastProgressStep = step
            bestCost = cost
            bestFraction = fraction
            return if (rewound) ProgressObservation.Rewound else ProgressObservation.Improved
        }
        return ProgressObservation.NonImproving
    }

    fun elapsed(step: Long): Long = step - lastProgressStep

    fun restartAt(step: Long) {
        lastProgressStep = step
    }

    fun reset() {
        lastSeenStep = -1L
        lastProgressStep = initialProgressStep
        bestCost = Long.MAX_VALUE
        bestFraction = 0.0
    }
}
