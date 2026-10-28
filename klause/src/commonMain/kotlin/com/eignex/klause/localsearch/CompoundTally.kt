package com.eignex.klause.localsearch

/**
 * Diagnostic counts of compound candidates at the strategy's move sinks and at commitment. A consumer installs
 * one on [LocalSearchEngine.compoundTally]; it reads no random values and changes no candidate.
 */
internal class CompoundTally {
    /** Compounds offered to a strategy candidate sink, before any filter. */
    var offered: Long = 0L
        private set

    /** Offered compounds rejected whole because a part touches a pinned coordinate. */
    var pinRejected: Long = 0L
        private set

    /** Offered compounds that lost parts on defined or foreign-owned coordinates. */
    var trimmed: Long = 0L
        private set

    /** Trimmed compounds left with at most one part, so no compound survived. */
    var collapsed: Long = 0L
        private set

    /** Compounds admitted to a candidate sink; a Boolean closure can still reject them. */
    var admitted: Long = 0L
        private set

    /** Compounds committed by the walk. */
    var applied: Long = 0L
        private set

    internal fun recordOffer(parts: Int, frozen: Boolean, kept: Int) {
        offered++
        when {
            frozen -> pinRejected++
            else -> {
                if (kept < parts) trimmed++
                if (kept < 2) collapsed++ else admitted++
            }
        }
    }

    internal fun recordApplied() {
        applied++
    }
}
