package com.eignex.klause.backtrack

/**
 * Polarity source for a fresh decision's first-tried value (#204). The B&B stepper rotates
 * through these every `rephaseInterval` conflicts via [next], so search periodically samples
 * a different phase bias without popping to root.
 */
internal enum class RephaseMode {
    /** Bias toward the deepest conflict-free assignment seen (falls back to saved). */
    TARGET,

    /** Plain phase saving — the last value committed for the variable. */
    SAVED,

    /** Force all decisions to try `true` first. */
    TRUE,

    /** Force all decisions to try `false` first. */
    FALSE,

    /** Random polarity per decision. */
    RANDOM,
    ;

    /** The next mode in the rotation, wrapping back to [TARGET] after [RANDOM]. */
    fun next(): RephaseMode = entries[(ordinal + 1) % entries.size]
}
