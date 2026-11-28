package com.eignex.klause.backtrack

// Polarity source for a fresh decision's first-tried value. PhaseSaving rotates through these every
// rephaseInterval conflicts without restarting.
internal enum class RephaseMode {
    // The deepest conflict-free assignment seen, falling back to saved.
    TARGET,

    // The best incumbent's value, falling back to saved.
    SOLUTION,

    // The last value committed for the variable.
    SAVED,

    TRUE,

    FALSE,

    RANDOM,
    ;

    fun next(): RephaseMode = entries[(ordinal + 1) % entries.size]
}
