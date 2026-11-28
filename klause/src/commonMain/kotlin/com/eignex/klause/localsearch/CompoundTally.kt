package com.eignex.klause.localsearch

// Diagnostic only: reads no random values and changes no candidate.
internal class CompoundTally {
    var offered: Long = 0L
        private set

    var pinRejected: Long = 0L
        private set

    // Lost parts on defined or foreign-owned coordinates.
    var trimmed: Long = 0L
        private set

    var collapsed: Long = 0L
        private set

    // A Boolean closure can still reject an admitted compound.
    var admitted: Long = 0L
        private set

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
