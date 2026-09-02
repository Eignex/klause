package com.eignex.klause.solver.incumbent

/**
 * A single consumer's view of an [IncumbentSource]: hands back the standing incumbent only the first time
 * it is seen. Freshness is decided on [VerifiedIncumbent.version], so a consumer polling a quiet exchange
 * pays one read and adopts nothing, and re-publishing an assignment it already imported still counts as new.
 *
 * Stateful and **not** thread-safe (it remembers the last version it handed out); give each consuming loop
 * its own.
 */
class IncumbentSubscription<out A, out V>(private val source: IncumbentSource<A, V>) {
    private var seen = 0L

    /** The incumbent if it has advanced since the last poll, else null. */
    fun poll(): VerifiedIncumbent<A, V>? {
        val standing = source.current() ?: return null
        if (standing.version <= seen) return null
        seen = standing.version
        return standing
    }
}
