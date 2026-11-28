package com.eignex.klause.solver.incumbent

/**
 * A single consumer's view of an [IncumbentSource]: hands back the standing incumbent only the first time
 * it is seen, by [VerifiedIncumbent.version]. Not thread-safe; give each consuming loop its own.
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
