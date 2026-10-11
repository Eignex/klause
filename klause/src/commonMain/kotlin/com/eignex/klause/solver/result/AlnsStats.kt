package com.eignex.klause.solver.result

/** ALNS inner-engine observations, separate from the declared outer repair allowance. */
data class AlnsStats(
    /** Search nodes reported by the complete bootstrap. */
    val bootstrapCpNodes: Long = 0,
    /** Moves reported by the local-search bootstrap. */
    val bootstrapLsMoves: Long = 0,
    /** Search nodes reported by complete repair calls, counting a retained handle once. */
    val repairCpNodes: Long = 0,
    /** Moves reported by local-search repair calls. */
    val repairLsMoves: Long = 0,
    /** Declared repair allowances charged by the outer loop; not measured inner work. */
    val outerAllowance: Long = 0,
    /** Wall milliseconds in the complete bootstrap, including construction. */
    val bootstrapCpMillis: Long = 0,
    /** Wall milliseconds in the local-search bootstrap, including seeding. */
    val bootstrapLsMillis: Long = 0,
    /** Wall milliseconds in repair operators, including construction and reseeding. */
    val repairMillis: Long = 0,
) {
    /** Combine independent ALNS runs by adding their observations. */
    fun mergedWith(other: AlnsStats): AlnsStats = AlnsStats(
        bootstrapCpNodes + other.bootstrapCpNodes,
        bootstrapLsMoves + other.bootstrapLsMoves,
        repairCpNodes + other.repairCpNodes,
        repairLsMoves + other.repairLsMoves,
        outerAllowance + other.outerAllowance,
        bootstrapCpMillis + other.bootstrapCpMillis,
        bootstrapLsMillis + other.bootstrapLsMillis,
        repairMillis + other.repairMillis,
    )
}
